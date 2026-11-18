package com.winlator.cmod.stream.moonlight;

import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * GameStream "nvhttp" endpoints (what Moonlight talks to before streaming):
 * HTTP 47989: serverinfo, pair (PIN pairing), unpair.
 * HTTPS 47984 (mutual TLS with the client's paired cert): serverinfo, pair, applist,
 * launch, resume, cancel, appasset.
 * Protocol mirrors Sunshine's nvhttp.cpp (GPL-3.0).
 */
public final class NvHttpServer {
    private static final String TAG = "LudashiMoonlight";
    public static final int PORT_HTTP = 47989;
    public static final int PORT_HTTPS = 47984;
    public static final int PORT_RTSP = 48010;
    private static final String APP_VERSION = "7.1.431.-1";
    private static final String GFE_VERSION = "3.23.0.74";
    // Codec flags (moonlight-common-c Limelight.h): H.264 | HEVC Main.
    private static final int SCM_H264 = 0x00001, SCM_HEVC = 0x00100;

    /** Asks the phone user for the PIN that Moonlight shows. */
    public interface PinProvider {
        void requestPin(String deviceName, PinCallback callback);
    }

    public interface PinCallback {
        void onPin(String pin);   // null = cancelled
    }

    /** Called when a paired client asks to start or resume streaming (stage 2). */
    public interface LaunchHandler {
        /** Returns the RTSP session URL, or null if streaming is not available. */
        String onLaunch(Map<String, String> args, InetAddress localAddress, boolean resume);
        void onCancel();
        boolean isStreaming();
    }

    private final HostIdentity identity;
    private final PinProvider pinProvider;
    private final String hostName;
    private volatile LaunchHandler launchHandler;
    private volatile boolean running;
    private ServerSocket httpSocket;
    private SSLServerSocket httpsSocket;
    private final Map<String, PairSession> pairSessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private static final class PairSession {
        X509Certificate clientCert;
        String deviceName;
        byte[] salt;
        byte[] aesKey;
        byte[] serverSecret;
        byte[] serverChallenge;
        byte[] clientHash;
        int phase; // 0 none, 1 getservercert, 2 clientchallenge, 3 serverchallengeresp
    }

    public NvHttpServer(HostIdentity identity, PinProvider pinProvider) {
        this.identity = identity;
        this.pinProvider = pinProvider;
        this.hostName = "Ludashi " + Build.MODEL;
    }

    public String getHostName() { return hostName; }

    public void setLaunchHandler(LaunchHandler handler) { this.launchHandler = handler; }

    public void start() throws Exception {
        running = true;
        httpSocket = new ServerSocket();
        httpSocket.setReuseAddress(true);
        httpSocket.bind(new java.net.InetSocketAddress(PORT_HTTP));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        char[] pw = "ludashi".toCharArray();
        ks.setKeyEntry("host", identity.privateKey, pw, new Certificate[]{identity.certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), new TrustManager[]{new AcceptAllClients()}, random);
        httpsSocket = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket();
        httpsSocket.setReuseAddress(true);
        httpsSocket.bind(new java.net.InetSocketAddress(PORT_HTTPS));
        httpsSocket.setWantClientAuth(true);

        new Thread(() -> acceptLoop(httpSocket, false), "Moonlight-http").start();
        new Thread(() -> acceptLoop(httpsSocket, true), "Moonlight-https").start();
        Log.i(TAG, "nvhttp listening on " + PORT_HTTP + "/" + PORT_HTTPS + " as \"" + hostName + "\"");
    }

    public void stop() {
        running = false;
        try { if (httpSocket != null) httpSocket.close(); } catch (IOException ignored) {}
        try { if (httpsSocket != null) httpsSocket.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop(ServerSocket server, boolean https) {
        while (running) {
            try {
                Socket s = server.accept();
                new Thread(() -> handle(s, https), "Moonlight-req").start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket s, boolean https) {
        try {
            s.setSoTimeout(15000);
            X509Certificate clientCert = null;
            if (https) {
                SSLSocket ssl = (SSLSocket) s;
                ssl.startHandshake();
                try {
                    Certificate[] peer = ssl.getSession().getPeerCertificates();
                    if (peer.length > 0) clientCert = (X509Certificate) peer[0];
                } catch (javax.net.ssl.SSLPeerUnverifiedException ignored) {}
            }
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) { /* headers ignored */ }
            String target = requestLine.split(" ")[1];
            String path = target;
            Map<String, String> args = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String kv : target.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) args.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                            URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }
            Log.d(TAG, (https ? "HTTPS " : "HTTP ") + path + " " + args.keySet());
            Response r = route(path, args, https, clientCert, s.getLocalAddress());
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + r.code + " OK\r\nContent-Type: " + r.type + "\r\nContent-Length: "
                    + r.body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(r.body);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "request failed: " + e);
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static final class Response {
        int code = 200;
        String type = "application/xml";
        byte[] body;
        Response(String xml) { body = xml.getBytes(StandardCharsets.UTF_8); }
        Response(int code, byte[] body, String type) { this.code = code; this.body = body; this.type = type; }
    }

    private Response route(String path, Map<String, String> args, boolean https, X509Certificate cert, InetAddress local) {
        boolean paired = https && identity.isPaired(cert);
        switch (path) {
            case "/serverinfo":
                return new Response(serverInfo(https && paired, local));
            case "/pair":
                return new Response(pair(args, https, paired));
            case "/unpair":
                return new Response(xml(200, ""));
        }
        if (!https) return new Response(xml(404, ""));
        if (!paired) {
            Response r = new Response(xml(401, "<status_message>The client is not authorized</status_message>"));
            return r;
        }
        switch (path) {
            case "/applist":
                return new Response(xml(200, "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Ludashi</AppTitle><ID>1</ID></App>"));
            case "/launch":
            case "/resume":
                return new Response(launch(args, local, path.equals("/resume")));
            case "/cancel": {
                LaunchHandler h = launchHandler;
                if (h != null) h.onCancel();
                return new Response(xml(200, "<cancel>1</cancel>"));
            }
            case "/appasset":
                return new Response(404, new byte[0], "image/png");
            default:
                return new Response(xml(404, ""));
        }
    }

    private String serverInfo(boolean paired, InetAddress local) {
        LaunchHandler h = launchHandler;
        boolean busy = h != null && h.isStreaming();
        String localIp = local != null && local.getAddress().length == 4 ? local.getHostAddress() : "127.0.0.1";
        return xml(200,
                "<hostname>" + esc(hostName) + "</hostname>"
                        + "<appversion>" + APP_VERSION + "</appversion>"
                        + "<GfeVersion>" + GFE_VERSION + "</GfeVersion>"
                        + "<uniqueid>" + identity.uniqueId + "</uniqueid>"
                        + "<HttpsPort>" + PORT_HTTPS + "</HttpsPort>"
                        + "<ExternalPort>" + PORT_HTTP + "</ExternalPort>"
                        + "<MaxLumaPixelsHEVC>1869449984</MaxLumaPixelsHEVC>"
                        + "<mac>00:00:00:00:00:00</mac>"
                        + "<LocalIP>" + localIp + "</LocalIP>"
                        + "<ServerCodecModeSupport>" + (SCM_H264 | SCM_HEVC) + "</ServerCodecModeSupport>"
                        + "<PairStatus>" + (paired ? 1 : 0) + "</PairStatus>"
                        + "<currentgame>" + (busy ? 1 : 0) + "</currentgame>"
                        + "<state>" + (busy ? "SUNSHINE_SERVER_BUSY" : "SUNSHINE_SERVER_FREE") + "</state>");
    }

    private String launch(Map<String, String> args, InetAddress local, boolean resume) {
        LaunchHandler h = launchHandler;
        String url = h != null ? h.onLaunch(args, local, resume) : null;
        if (url == null) {
            return xml(503, "<status_message>Streaming is not ready on the phone yet</status_message><gamesession>0</gamesession>");
        }
        return xml(200, "<sessionUrl0>" + esc(url) + "</sessionUrl0><gamesession>1</gamesession>"
                + (resume ? "<resume>1</resume>" : ""));
    }

    // ---- Pairing (see Sunshine nvhttp.cpp getservercert/clientchallenge/...) ----

    private String pair(Map<String, String> args, boolean https, boolean paired) {
        String uid = args.get("uniqueid");
        if (uid == null) return xml(400, "<status_message>Missing uniqueid</status_message>");
        try {
            if ("getservercert".equals(args.get("phrase"))) return getServerCert(uid, args);
            if ("pairchallenge".equals(args.get("phrase"))) {
                return xml(200, "<paired>" + (https && paired ? 1 : 0) + "</paired>");
            }
            PairSession sess = pairSessions.get(uid);
            if (sess == null) return xml(400, "<status_message>Invalid uniqueid</status_message>");
            String result;
            if (args.containsKey("clientchallenge")) result = clientChallenge(sess, Hex.decode(args.get("clientchallenge")));
            else if (args.containsKey("serverchallengeresp")) result = serverChallengeResp(sess, Hex.decode(args.get("serverchallengeresp")));
            else if (args.containsKey("clientpairingsecret")) {
                result = clientPairingSecret(sess, Hex.decode(args.get("clientpairingsecret")));
                pairSessions.remove(uid);
            } else {
                pairSessions.remove(uid);
                result = failPair("Invalid pairing request");
            }
            return result;
        } catch (Exception e) {
            Log.w(TAG, "pairing failed", e);
            pairSessions.remove(uid);
            return failPair("Pairing error: " + e.getMessage());
        }
    }

    private String getServerCert(String uid, Map<String, String> args) throws Exception {
        PairSession sess = new PairSession();
        sess.clientCert = HostIdentity.parsePem(Hex.decode(args.get("clientcert")));
        sess.deviceName = args.containsKey("devicename") ? args.get("devicename") : "Moonlight";
        String saltHex = args.get("salt");
        if (saltHex == null || saltHex.length() < 32) return failPair("Salt too short");
        sess.salt = Hex.decode(saltHex.substring(0, 32));

        // Wait (like Sunshine) until the user types the PIN shown by Moonlight.
        final String[] pin = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        pinProvider.requestPin(sess.deviceName, p -> { pin[0] = p; latch.countDown(); });
        if (!latch.await(5, TimeUnit.MINUTES) || pin[0] == null) return failPair("Pairing cancelled");

        byte[] saltPin = concat(sess.salt, pin[0].getBytes(StandardCharsets.UTF_8));
        sess.aesKey = java.util.Arrays.copyOf(sha256(saltPin), 16);
        sess.phase = 1;
        pairSessions.put(uid, sess);
        return xml(200, "<paired>1</paired><plaincert>" + Hex.encode(identity.certPem) + "</plaincert>");
    }

    private String clientChallenge(PairSession sess, byte[] challenge) throws Exception {
        if (sess.phase != 1) return failPair("Out of order call to clientchallenge");
        byte[] decrypted = aes(Cipher.DECRYPT_MODE, sess.aesKey, challenge);
        byte[] serverSecret = randomBytes(16);
        byte[] hash = sha256(concat(decrypted, identity.certificate.getSignature(), serverSecret));
        byte[] serverChallenge = randomBytes(16);
        byte[] encrypted = aes(Cipher.ENCRYPT_MODE, sess.aesKey, concat(hash, serverChallenge));
        sess.serverSecret = serverSecret;
        sess.serverChallenge = serverChallenge;
        sess.phase = 2;
        return xml(200, "<paired>1</paired><challengeresponse>" + Hex.encode(encrypted) + "</challengeresponse>");
    }

    private String serverChallengeResp(PairSession sess, byte[] encryptedResponse) throws Exception {
        if (sess.phase != 2) return failPair("Out of order call to serverchallengeresp");
        sess.clientHash = aes(Cipher.DECRYPT_MODE, sess.aesKey, encryptedResponse);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(identity.privateKey);
        sig.update(sess.serverSecret);
        byte[] pairingSecret = concat(sess.serverSecret, sig.sign());
        sess.phase = 3;
        return xml(200, "<pairingsecret>" + Hex.encode(pairingSecret) + "</pairingsecret><paired>1</paired>");
    }

    private String clientPairingSecret(PairSession sess, byte[] clientPairingSecret) throws Exception {
        if (sess.phase != 3) return failPair("Out of order call to clientpairingsecret");
        if (clientPairingSecret.length <= 16) return failPair("Client pairing secret too short");
        byte[] secret = java.util.Arrays.copyOfRange(clientPairingSecret, 0, 16);
        byte[] sign = java.util.Arrays.copyOfRange(clientPairingSecret, 16, clientPairingSecret.length);
        byte[] hash = sha256(concat(sess.serverChallenge, sess.clientCert.getSignature(), secret));
        // Compare only the bytes the client sent (it may pad the decrypted hash).
        boolean sameHash = sess.clientHash != null && sess.clientHash.length >= hash.length
                && MessageDigest.isEqual(hash, java.util.Arrays.copyOf(sess.clientHash, hash.length));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(sess.clientCert.getPublicKey());
        sig.update(secret);
        boolean verified = sig.verify(sign);
        boolean ok = sameHash && verified;
        if (ok) identity.addPairedClient(sess.clientCert, sess.deviceName);
        else Log.w(TAG, "pairing rejected: hash " + sameHash + ", signature " + verified + " (wrong PIN?)");
        return xml(200, "<paired>" + (ok ? 1 : 0) + "</paired>");
    }

    private static String failPair(String msg) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"400\" status_message=\""
                + esc(msg) + "\"><paired>0</paired></root>";
    }

    // ---- helpers ----

    private static String xml(int status, String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"" + status + "\">" + inner + "</root>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static byte[] aes(int mode, byte[] key, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"));
        int padded = (data.length + 15) / 16 * 16;
        return c.doFinal(padded == data.length ? data : java.util.Arrays.copyOf(data, padded));
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

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

    /** Client certs are checked against the paired list after the handshake. */
    private static final class AcceptAllClients implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
