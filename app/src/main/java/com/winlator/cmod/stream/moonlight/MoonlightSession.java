package com.winlator.cmod.stream.moonlight;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One Moonlight streaming session after /launch: RTSP handshake (TCP 48010), control stream
 * over ENet (UDP 47999, AES-GCM encrypted) and video RTP packets (UDP 47998).
 * Wire formats follow Sunshine's rtsp.cpp / stream.cpp and moonlight-common-c (GPL-3.0).
 */
public final class MoonlightSession {
    private static final String TAG = "LudashiMoonlight";
    public static final int PORT_RTSP = 48010, PORT_VIDEO = 47998, PORT_CONTROL = 47999, PORT_AUDIO = 48000;

    // Control message types (Sunshine stream.cpp packetTypes)
    private static final int CT_START_A = 0x0305, CT_START_B = 0x0307, CT_INVALIDATE_REF = 0x0301,
            CT_LOSS_STATS = 0x0201, CT_INPUT = 0x0206, CT_TERMINATION = 0x0109, CT_PING = 0x0200,
            CT_IDR = 0x0302, CT_ENCRYPTED = 0x0001;
    private static final int SS_ENC_CONTROL_V2 = 0x01;
    private static final int ML_FF_SESSION_ID_V1 = 0x02;

    /** Encoder side, implemented by StreamSession. */
    public interface VideoSource {
        void configure(int width, int height, int fps, int bitrateKbps, boolean hevc);
        void requestIdr();
        void setFrameListener(FrameListener listener);
        /** Game sound as 48 kHz stereo s16le; no-op if there is no audio tap. */
        void setPcmListener(com.winlator.cmod.stream.AudioTap.PcmListener listener);
    }

    public interface FrameListener {
        /** Annex-B access unit; for key frames codecConfig (VPS/SPS/PPS) must be prepended. */
        void onFrame(byte[] data, int len, long ptsUs, boolean keyFrame, byte[] codecConfig);
    }

    /** Raw decrypted input packets (stage 3), and session end. */
    public interface Listener {
        void onInput(byte[] plaintext, int off, int len);
        void onSessionEnded();
    }

    private final VideoSource video;
    private final Listener listener;
    private final byte[] riKey;
    private final int riKeyId;
    private final int connectData;
    private final String pingPayload; // 16 ASCII chars echoed in client pings
    private volatile boolean running;
    private volatile boolean playing;

    // Negotiated in ANNOUNCE
    private int width = 1920, height = 1080, fps = 60, bitrateKbps = 20000, packetSize = 1024;
    private boolean hevc = true;
    private int controlProtocol = 13;
    private int encryptionFlags = 0;
    private int mlFeatureFlags = 0;
    private int audioPacketMs = 5;
    private boolean audioEncrypted;

    // Audio
    private DatagramSocket audioSocket;
    private volatile InetSocketAddress audioPeer;
    private OpusEncoderJni opus;
    private short[] pcmFrame;
    private int pcmFill;          // samples (per all channels) in pcmFrame
    private int pendingByte = -1; // odd byte carried between reads
    private int audioSeq;
    private int audioTs;
    private final byte[] opusOut = new byte[1400];

    private ServerSocket rtspSocket;
    private Thread controlThread;
    private EnetHost enet;
    private volatile long controlPeer;
    private DatagramSocket videoSocket;
    private volatile InetSocketAddress videoPeer;
    private InetAddress expectedClient;

    private int controlSeqOut;
    private int lowSeq;
    private int frameIndex = 1;
    private final Object sendLock = new Object();

    public MoonlightSession(VideoSource video, Listener listener, Map<String, String> launchArgs, InetAddress client) {
        this.video = video;
        this.listener = listener;
        this.riKey = Hex.decode(launchArgs.get("rikey"));
        this.riKeyId = (int) Long.parseLong(launchArgs.get("rikeyid"));
        SecureRandom rnd = new SecureRandom();
        this.connectData = rnd.nextInt();
        StringBuilder p = new StringBuilder();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 16; i++) p.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        this.pingPayload = p.toString();
        this.expectedClient = client;
    }

    public void start() throws IOException {
        running = true;
        rtspSocket = new ServerSocket();
        rtspSocket.setReuseAddress(true);
        rtspSocket.bind(new InetSocketAddress(PORT_RTSP));
        videoSocket = new DatagramSocket(null);
        videoSocket.setReuseAddress(true);
        videoSocket.bind(new InetSocketAddress(PORT_VIDEO));
        videoSocket.setSendBufferSize(4 << 20);
        audioSocket = new DatagramSocket(null);
        audioSocket.setReuseAddress(true);
        audioSocket.bind(new InetSocketAddress(PORT_AUDIO));
        enet = new EnetHost(PORT_CONTROL, 4);
        new Thread(this::rtspLoop, "Moonlight-rtsp").start();
        new Thread(this::videoPingLoop, "Moonlight-videoping").start();
        new Thread(this::audioPingLoop, "Moonlight-audioping").start();
        controlThread = new Thread(this::controlLoop, "Moonlight-control");
        controlThread.start();
        Log.i(TAG, "session ready: rtsp " + PORT_RTSP + ", control " + PORT_CONTROL + ", video " + PORT_VIDEO);
    }

    public boolean isActive() { return running; }

    /** True once the client sent PLAY and video is flowing to it. */
    public boolean isPlaying() { return running && playing && videoPeer != null; }

    public String clientAddress() {
        InetSocketAddress p = videoPeer;
        return p != null ? p.getAddress().getHostAddress() : "";
    }

    public void stop() {
        if (!running) return;
        running = false;
        playing = false;
        video.setFrameListener(null);
        video.setPcmListener(null);
        try { if (rtspSocket != null) rtspSocket.close(); } catch (IOException ignored) {}
        if (videoSocket != null) videoSocket.close();
        if (audioSocket != null) audioSocket.close();
        synchronized (this) {
            if (opus != null) { opus.destroy(); opus = null; }
        }
        Thread t = controlThread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        Log.i(TAG, "session stopped");
    }

    // ------------------------------------------------------------------ RTSP

    private void rtspLoop() {
        while (running) {
            try (Socket s = rtspSocket.accept()) {
                s.setSoTimeout(10000);
                s.setTcpNoDelay(true);
                handleRtsp(s);
            } catch (IOException e) {
                if (running) Log.w(TAG, "rtsp: " + e);
            }
        }
    }

    private void handleRtsp(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        String requestLine = readLine(in);
        if (requestLine == null) return;
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
        }
        int contentLength = 0;
        try { contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (NumberFormatException ignored) {}
        byte[] body = new byte[contentLength];
        int off = 0;
        while (off < contentLength) {
            int n = in.read(body, off, contentLength - off);
            if (n < 0) break;
            off += n;
        }
        String[] parts = requestLine.split(" ");
        String method = parts[0];
        String target = parts.length > 1 ? parts[1] : "";
        String cseq = headers.getOrDefault("cseq", "0");
        Log.d(TAG, "RTSP " + method + " " + target);

        StringBuilder extra = new StringBuilder();
        String payload = "";
        int status = 200;
        switch (method) {
            case "OPTIONS":
                break;
            case "DESCRIBE": {
                StringBuilder sdp = new StringBuilder();
                sdp.append("a=x-ss-general.featureFlags:0\r\n");
                sdp.append("a=x-ss-general.encryptionSupported:").append(SS_ENC_CONTROL_V2).append("\r\n");
                sdp.append("a=x-ss-general.encryptionRequested:").append(SS_ENC_CONTROL_V2).append("\r\n");
                sdp.append("sprop-parameter-sets=AAAAAU\r\n"); // advertises HEVC support
                sdp.append("a=fmtp:97 surround-params=21101\r\n"); // stereo opus
                payload = sdp.toString();
                break;
            }
            case "SETUP": {
                int eq = target.indexOf('=');
                String type = eq >= 0 ? target.substring(eq + 1).split("/")[0] : "";
                int port;
                if (type.equals("audio")) port = PORT_AUDIO;
                else if (type.equals("video")) port = PORT_VIDEO;
                else if (type.equals("control")) port = PORT_CONTROL;
                else { status = 404; break; }
                extra.append("Session: DEADBEEFCAFE;timeout = 90\r\n");
                extra.append("Transport: server_port=").append(port).append("\r\n");
                if (type.equals("control")) extra.append("X-SS-Connect-Data: ").append(connectData & 0xFFFFFFFFL).append("\r\n");
                else extra.append("X-SS-Ping-Payload: ").append(pingPayload).append("\r\n");
                break;
            }
            case "ANNOUNCE":
                parseAnnounce(new String(body, StandardCharsets.UTF_8));
                break;
            case "PLAY":
                startPlaying();
                break;
            default:
                status = 404;
        }
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String resp = "RTSP/1.0 " + status + (status == 200 ? " OK" : " NOT FOUND") + "\r\n"
                + "CSeq: " + cseq + "\r\n" + extra
                + (payloadBytes.length > 0 ? "Content-length: " + payloadBytes.length + "\r\n" : "")
                + "\r\n";
        OutputStream out = s.getOutputStream();
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.write(payloadBytes);
        out.flush();
        // The client reads until we close the connection (try-with-resources closes it).
    }

    private void parseAnnounce(String sdp) {
        Map<String, String> a = new HashMap<>();
        for (String raw : sdp.split("[\r\n]+")) {
            if (!raw.startsWith("a=")) continue;
            int c = raw.indexOf(':');
            if (c < 0) continue;
            a.put(raw.substring(2, c), raw.substring(c + 1).trim());
        }
        width = intArg(a, "x-nv-video[0].clientViewportWd", width);
        height = intArg(a, "x-nv-video[0].clientViewportHt", height);
        fps = intArg(a, "x-nv-video[0].maxFPS", fps);
        bitrateKbps = intArg(a, "x-nv-vqos[0].bw.maximumBitrateKbps", bitrateKbps);
        packetSize = intArg(a, "x-nv-video[0].packetSize", packetSize);
        hevc = intArg(a, "x-nv-vqos[0].bitStreamFormat", 0) == 1;
        controlProtocol = intArg(a, "x-nv-general.useReliableUdp", 1);
        encryptionFlags = intArg(a, "x-ss-general.encryptionEnabled", 0);
        mlFeatureFlags = intArg(a, "x-ml-general.featureFlags", 0);
        minFecPackets = Math.max(0, Math.min(16, intArg(a, "x-nv-vqos[0].fec.minRequiredFecPackets", 0)));
        audioPacketMs = intArg(a, "x-nv-aqos.packetDuration", 5);
        if (audioPacketMs != 5 && audioPacketMs != 10) audioPacketMs = 5;
        // NVFF_AUDIO_ENCRYPTION (0x20) in the client's general feature flags.
        audioEncrypted = (intArg(a, "x-nv-general.featureFlags", 0) & 0x20) != 0;
        Log.i(TAG, "audio: " + audioPacketMs + " ms packets, encrypted " + audioEncrypted);
        Log.i(TAG, "ANNOUNCE: " + width + "x" + height + "@" + fps + " " + bitrateKbps + "kbps "
                + (hevc ? "HEVC" : "H.264") + " packet " + packetSize + " ctrl " + controlProtocol
                + " enc " + encryptionFlags + " mlff " + mlFeatureFlags);
    }

    private static int intArg(Map<String, String> a, String key, int def) {
        String v = a.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private void startPlaying() {
        if (playing) return;
        playing = true;
        // Leave some headroom under the client's maximum for audio/FEC/overhead.
        int videoKbps = Math.max(2000, bitrateKbps * 90 / 100);
        video.configure(width, height, fps, videoKbps, hevc);
        video.setFrameListener(this::sendFrame);
        video.requestIdr();
        synchronized (this) {
            try {
                opus = new OpusEncoderJni(48000, 2, 128000);
                pcmFrame = new short[48 * audioPacketMs * 2];
                pcmFill = 0;
                video.setPcmListener(this::onPcm);
            } catch (Throwable e) {
                Log.e(TAG, "audio disabled: opus init failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ Audio

    private void audioPingLoop() {
        byte[] buf = new byte[256];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                audioSocket.receive(p);
                InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();
                boolean ours = p.getLength() >= 16
                        && new String(buf, 0, 16, StandardCharsets.US_ASCII).equals(pingPayload);
                boolean legacy = p.getLength() >= 4 && buf[0] == 'P' && buf[1] == 'I' && buf[2] == 'N' && buf[3] == 'G';
                if ((ours || legacy) && !from.equals(audioPeer)) {
                    audioPeer = from;
                    Log.i(TAG, "audio peer: " + from);
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "audio ping: " + e);
            }
        }
    }

    /** PCM from the audio tap (any chunk size) -> fixed opus frames -> RTP. */
    private synchronized void onPcm(byte[] data, int len) {
        if (opus == null || !playing) return;
        int i = 0;
        if (pendingByte >= 0 && len > 0) {
            pushSample((short) ((pendingByte & 0xFF) | (data[0] << 8)));
            pendingByte = -1;
            i = 1;
        }
        for (; i + 1 < len; i += 2) pushSample((short) ((data[i] & 0xFF) | (data[i + 1] << 8)));
        if (i < len) pendingByte = data[i];
    }

    private void pushSample(short s) {
        pcmFrame[pcmFill++] = s;
        if (pcmFill == pcmFrame.length) {
            pcmFill = 0;
            sendAudioFrame();
        }
    }

    private void sendAudioFrame() {
        int n = opus.encode(pcmFrame, pcmFrame.length / 2, opusOut);
        int seq = audioSeq & 0xFFFF;
        int ts = audioTs;
        audioSeq++;
        audioTs += audioPacketMs;
        InetSocketAddress peer = audioPeer;
        if (n <= 0 || peer == null) return;
        byte[] payload = opusOut;
        int payloadLen = n;
        if (audioEncrypted) {
            try {
                byte[] iv = new byte[16];
                int ivSeq = riKeyId + seq;
                iv[0] = (byte) (ivSeq >> 24); iv[1] = (byte) (ivSeq >> 16); iv[2] = (byte) (ivSeq >> 8); iv[3] = (byte) ivSeq;
                Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(riKey, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
                payload = c.doFinal(opusOut, 0, n);
                payloadLen = payload.length;
            } catch (Exception e) {
                Log.w(TAG, "audio encrypt failed: " + e);
                return;
            }
        }
        byte[] pkt = new byte[12 + payloadLen];
        pkt[0] = (byte) 0x80;
        pkt[1] = 97;
        pkt[2] = (byte) (seq >> 8); pkt[3] = (byte) seq;
        pkt[4] = (byte) (ts >> 24); pkt[5] = (byte) (ts >> 16); pkt[6] = (byte) (ts >> 8); pkt[7] = (byte) ts;
        System.arraycopy(payload, 0, pkt, 12, payloadLen);
        try {
            audioSocket.send(new DatagramPacket(pkt, pkt.length, peer));
        } catch (IOException e) {
            if (running) Log.w(TAG, "audio send: " + e);
        }
        if (audioSeq == 1 || audioSeq % 12000 == 0) Log.i(TAG, "audio: sent " + audioSeq + " packets (" + n + " B opus)");
    }

    // ------------------------------------------------------------------ Video

    private void videoPingLoop() {
        byte[] buf = new byte[256];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                videoSocket.receive(p);
                InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();
                boolean ours = p.getLength() >= 16
                        && new String(buf, 0, 16, StandardCharsets.US_ASCII).equals(pingPayload);
                boolean legacy = p.getLength() >= 4 && buf[0] == 'P' && buf[1] == 'I' && buf[2] == 'N' && buf[3] == 'G';
                if ((ours || legacy) && !from.equals(videoPeer)) {
                    videoPeer = from;
                    Log.i(TAG, "video peer: " + from);
                    video.requestIdr();
                }
            } catch (IOException e) {
                if (running) Log.w(TAG, "video ping: " + e);
            }
        }
    }

    /** Splits one encoded frame into Moonlight video packets (no FEC parity yet). */
    private void sendFrame(byte[] data, int len, long ptsUs, boolean keyFrame, byte[] codecConfig) {
        InetSocketAddress peer = videoPeer;
        if (!running || !playing || peer == null) return;
        if (keyFrame && codecConfig == null) Log.w(TAG, "key frame without codec config");
        int cfgLen = keyFrame && codecConfig != null ? codecConfig.length : 0;

        int payloadBlock = packetSize - 16;      // video bytes per packet
        int blockSize = packetSize + 16;         // whole UDP payload
        int frameLen = 8 + cfgLen + len;         // short frame header + config + AU
        int totalPackets = (frameLen + payloadBlock - 1) / payloadBlock;

        int lastPayloadLen = frameLen % payloadBlock;
        if (lastPayloadLen == 0) lastPayloadLen = payloadBlock;

        // FEC blocks (Sunshine stream.cpp): data + parity <= 255 shards, at most 4 blocks.
        int pct = FEC_PERCENT;
        int maxData = 255 * 100 / (100 + pct);
        int blocks = (totalPackets + maxData - 1) / maxData;
        if (blocks > 4) {
            pct = 0; // enormous frame: send without parity like Sunshine
            blocks = (totalPackets + 254) / 255;
            if (blocks > 4) {
                Log.w(TAG, "frame too large: " + totalPackets + " packets");
                return;
            }
        }
        int perBlock = (totalPackets + blocks - 1) / blocks;

        byte[] frameHeader = new byte[8];
        frameHeader[0] = 0x01;                       // short header
        frameHeader[3] = (byte) (keyFrame ? 2 : 1);  // frame type
        frameHeader[4] = (byte) lastPayloadLen;
        frameHeader[5] = (byte) (lastPayloadLen >> 8);

        long rtpTs = (ptsUs * 9 / 100) & 0xFFFFFFFFL;
        int curFrame = frameIndex++;
        int sentPackets = 0;

        synchronized (sendLock) {
            int packetIndex = 0;
            for (int block = 0; block < blocks; block++) {
                int first = block * perBlock;
                int count = Math.min(perBlock, totalPackets - first);
                if (count <= 0) break;
                int parity = 0, blockPct = 0;
                if (pct > 0) {
                    parity = (count * pct + 99) / 100;
                    blockPct = pct;
                    if (parity < minFecPackets) {
                        parity = minFecPackets;
                        blockPct = 100 * parity / count;
                    }
                    if (count + parity > 255) parity = 255 - count;
                }
                int shards = count + parity;
                if (shardBuf.length < shards * blockSize) shardBuf = new byte[shards * blockSize];
                java.util.Arrays.fill(shardBuf, 0, shards * blockSize, (byte) 0);
                ByteBuffer bb = ByteBuffer.wrap(shardBuf);

                // 1) Data shards: NV header (without RTP/fecInfo) + payload, as Sunshine
                //    has them when it computes parity.
                bb.order(ByteOrder.LITTLE_ENDIAN);
                for (int x = 0; x < count; x++, packetIndex++) {
                    int o = x * blockSize;
                    bb.putInt(o + 16, ((lowSeq + x) & 0xFFFFFF) << 8);
                    bb.putInt(o + 20, curFrame);
                    int flags = 0x1;                   // FLAG_CONTAINS_PIC_DATA
                    if (x == 0) flags |= 0x4;          // SOF of this FEC block
                    if (x == count - 1) flags |= 0x2;  // EOF of this FEC block
                    shardBuf[o + 24] = (byte) flags;
                    shardBuf[o + 26] = 0x10;                                          // multiFecFlags
                    shardBuf[o + 27] = (byte) ((block << 4) | ((blocks - 1) << 6));   // multiFecBlocks
                    copyFrameBytes(frameHeader, codecConfig, cfgLen, data, len,
                            packetIndex * payloadBlock, shardBuf, o + 32, payloadBlock);
                }
                // 2) Parity shards over the whole packets (Reed-Solomon, nanors).
                if (parity > 0 && !VideoFec.encode(shardBuf, count, parity, blockSize)) {
                    Log.w(TAG, "FEC encode failed, sending block without parity");
                    shards = count;
                    parity = 0;
                    blockPct = 0;
                }
                // 3) Fields filled after FEC (the client fixes these up on recovered packets).
                for (int x = 0; x < shards; x++) {
                    int o = x * blockSize;
                    bb.order(ByteOrder.BIG_ENDIAN);
                    shardBuf[o] = (byte) (0x80 | 0x10);
                    shardBuf[o + 1] = 0;
                    bb.putShort(o + 2, (short) ((lowSeq + x) & 0xFFFF));
                    bb.putInt(o + 4, (int) rtpTs);
                    bb.putInt(o + 8, 0);
                    bb.order(ByteOrder.LITTLE_ENDIAN);
                    bb.putInt(o + 20, curFrame);
                    shardBuf[o + 27] = (byte) ((block << 4) | ((blocks - 1) << 6));
                    bb.putInt(o + 28, (x << 12) | (count << 22) | (blockPct << 4));
                    try {
                        videoSocket.send(new DatagramPacket(shardBuf, o, blockSize, peer));
                    } catch (IOException e) {
                        Log.w(TAG, "video send: " + e);
                        return;
                    }
                }
                lowSeq += shards;
                sentPackets += shards;
            }
        }
        framesSent++;
        packetsSent += sentPackets;
        if (framesSent == 1 || framesSent % 3600 == 0 || keyFrame) {
            Log.i(TAG, "video: frame " + curFrame + (keyFrame ? " IDR" : "") + " " + len + "B in "
                    + totalPackets + " pkts; sent frames " + framesSent + " pkts " + packetsSent + " -> " + peer);
        }
    }

    private long framesSent, packetsSent;
    private static final int FEC_PERCENT = 20; // Sunshine's default
    private int minFecPackets = 0;
    private byte[] shardBuf = new byte[0];

    /** Copies bytes [pos, pos+n) of header+config+au into dst, zero-padding past the end. */
    private static void copyFrameBytes(byte[] hdr, byte[] cfg, int cfgLen, byte[] au, int auLen,
                                       int pos, byte[] dst, int dstOff, int n) {
        int total = hdr.length + cfgLen + auLen;
        while (n > 0 && pos < total) {
            int c;
            if (pos < hdr.length) {
                c = Math.min(n, hdr.length - pos);
                System.arraycopy(hdr, pos, dst, dstOff, c);
            } else if (pos < hdr.length + cfgLen) {
                int o = pos - hdr.length;
                c = Math.min(n, cfgLen - o);
                System.arraycopy(cfg, o, dst, dstOff, c);
            } else {
                int o = pos - hdr.length - cfgLen;
                c = Math.min(n, auLen - o);
                System.arraycopy(au, o, dst, dstOff, c);
            }
            pos += c; dstOff += c; n -= c;
        }
    }

    // ------------------------------------------------------------------ Control (ENet)

    private void controlLoop() {
        try {
            while (running) {
                int type = enet.service(5);
                if (type <= 0) continue;
                long peer = enet.peer;
                if (type == EnetHost.EVENT_CONNECT) {
                    String addr = EnetHost.peerAddress(peer);
                    boolean sessionIdOk = (mlFeatureFlags & ML_FF_SESSION_ID_V1) == 0 || (int) enet.data == connectData;
                    if (controlPeer != 0 || !sessionIdOk) {
                        Log.w(TAG, "rejecting control connection from " + addr);
                        enet.disconnect(peer);
                        continue;
                    }
                    controlPeer = peer;
                    Log.i(TAG, "control connected: " + addr);
                } else if (type == EnetHost.EVENT_DISCONNECT) {
                    if (peer == controlPeer) {
                        Log.i(TAG, "control disconnected");
                        controlPeer = 0;
                        stop();
                        listener.onSessionEnded();
                    }
                } else if (type == EnetHost.EVENT_RECEIVE && peer == controlPeer) {
                    handleControl(enet.payload(), enet.payloadLength());
                }
            }
        } finally {
            enet.destroy();
        }
    }

    private void handleControl(byte[] buf, int len) {
        if (len < 2) return;
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        int type = bb.getShort(0) & 0xFFFF;
        if (controlProtocol == 13) {
            if (type != CT_ENCRYPTED) {
                Log.w(TAG, "dropping unencrypted control message " + Integer.toHexString(type));
                return;
            }
            int length = bb.getShort(2) & 0xFFFF;
            int seq = bb.getInt(4);
            if (length < 24 || 4 + length > len) return;
            byte[] plain = decryptControl(buf, 8, length - 4, seq);
            if (plain == null) {
                Log.e(TAG, "control: tag verification failed, ending session");
                stop();
                listener.onSessionEnded();
                return;
            }
            ByteBuffer pb = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
            int innerType = pb.getShort(0) & 0xFFFF;
            dispatchControl(innerType, plain, 4, plain.length - 4);
        } else {
            dispatchControl(type, buf, 4, len - 4);
        }
    }

    private void dispatchControl(int type, byte[] data, int off, int len) {
        switch (type) {
            case CT_IDR:
            case CT_INVALIDATE_REF:
                Log.d(TAG, "client requested IDR (" + Integer.toHexString(type) + ")");
                video.requestIdr();
                break;
            case CT_INPUT:
                listener.onInput(data, off, len);
                break;
            case CT_TERMINATION:
                Log.i(TAG, "client terminated the session");
                stop();
                listener.onSessionEnded();
                break;
            case CT_PING:
            case CT_START_A:
            case CT_START_B:
            case CT_LOSS_STATS:
            default:
                break;
        }
    }

    /** Gamepad rumble to the client (control type 0x010B, Sunshine control_rumble_t). */
    public void sendRumble(int controller, int lowFreq, int highFreq) {
        long peer = controlPeer;
        if (!running || peer == 0) return;
        ByteBuffer p = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        p.putShort((short) 0x010B);       // type
        p.putShort((short) 10);           // payload length
        p.putInt(0);                      // reserved
        p.putShort((short) controller);
        p.putShort((short) lowFreq);
        p.putShort((short) highFreq);
        sendControl(p.array());
    }

    /** Encrypts (control protocol 13) and sends one control message over ENet. */
    private void sendControl(byte[] plain) {
        long peer = controlPeer;
        if (peer == 0) return;
        byte[] out;
        if (controlProtocol == 13) {
            try {
                int seq = controlSeqOut++;
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(riKey, "AES"), new GCMParameterSpec(128, controlIv(seq, 'H')));
                byte[] ctTag = c.doFinal(plain);           // ciphertext || tag
                int ctLen = ctTag.length - 16;
                out = new byte[8 + 16 + ctLen];
                ByteBuffer o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
                o.putShort((short) CT_ENCRYPTED);
                o.putShort((short) (4 + 16 + ctLen));     // seq + tag + data
                o.putInt(seq);
                System.arraycopy(ctTag, ctLen, out, 8, 16);       // tag first (Sunshine layout)
                System.arraycopy(ctTag, 0, out, 24, ctLen);
            } catch (Exception e) {
                Log.w(TAG, "control encrypt failed: " + e);
                return;
            }
        } else {
            out = plain;
        }
        enet.send(peer, out, out.length);
    }

    /** Sunshine control_encrypted_t: [tag 16][ciphertext], IV from seq + 'C''C' (V2). */
    private byte[] decryptControl(byte[] buf, int off, int taggedLen, int seq) {
        try {
            byte[] iv = controlIv(seq, 'C');
            byte[] tag = java.util.Arrays.copyOfRange(buf, off, off + 16);
            byte[] ct = java.util.Arrays.copyOfRange(buf, off + 16, off + taggedLen);
            byte[] joined = new byte[ct.length + 16];
            System.arraycopy(ct, 0, joined, 0, ct.length);
            System.arraycopy(tag, 0, joined, ct.length, 16);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(riKey, "AES"), new GCMParameterSpec(128, iv));
            return c.doFinal(joined);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] controlIv(int seq, char origin) {
        if ((encryptionFlags & SS_ENC_CONTROL_V2) != 0) {
            byte[] iv = new byte[12];
            iv[0] = (byte) seq; iv[1] = (byte) (seq >> 8); iv[2] = (byte) (seq >> 16); iv[3] = (byte) (seq >> 24);
            iv[10] = (byte) origin;
            iv[11] = 'C';
            return iv;
        }
        byte[] iv = new byte[16]; // GFE legacy style
        iv[0] = (byte) seq;
        return iv;
    }

    // ------------------------------------------------------------------ helpers

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("US-ASCII");
    }
}
