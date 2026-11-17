package com.winlator.cmod.stream;

import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * "Stream to TV" session: the game renderer is shown on a private virtual display whose
 * surface is the input of a hardware video encoder. The phone keeps the activity (touch
 * controls); only the game picture goes to the encoder. Encoded frames are handed to the
 * Moonlight session (see stream.moonlight).
 */
public final class StreamSession implements com.winlator.cmod.stream.moonlight.MoonlightSession.VideoSource {
    private static final String TAG = "LudashiStream";

    public static final class Config {
        public int width = 1920;
        public int height = 1080;
        public int dpi = 240;
        public int fps = 60;
        public int bitrateMbps = 30;
        public boolean hevc = true;

        /** Parses LUDASHI_STREAM_* env values; missing or bad values keep defaults. */
        public static Config fromEnv(String res, String bitrate, String codec) {
            Config c = new Config();
            if (res != null && res.matches("\\d{3,4}x\\d{3,4}")) {
                String[] p = res.split("x");
                c.width = Integer.parseInt(p[0]) & ~1;
                c.height = Integer.parseInt(p[1]) & ~1;
            }
            try { if (bitrate != null && !bitrate.isEmpty()) c.bitrateMbps = Math.max(2, Math.min(100, Integer.parseInt(bitrate))); } catch (NumberFormatException ignored) {}
            if (codec != null && codec.equalsIgnoreCase("h264")) c.hevc = false;
            return c;
        }
    }

    private final Context context;
    private final Config config;
    private MediaCodec encoder;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private Presentation presentation;
    private FrameLayout presentationRoot;
    private View heartbeat;
    private View rendererView;
    private Thread drainThread;
    private volatile boolean running;
    private volatile byte[] codecConfig;
    private final Object encoderLock = new Object();
    private volatile int bitrateKbps;
    private volatile com.winlator.cmod.stream.moonlight.MoonlightSession.FrameListener frameListener;
    private AudioTap audioTap;

    public StreamSession(Context context, Config config) {
        this.context = context;
        this.config = config;
    }

    public Config getConfig() { return config; }

    /** Creates encoder, virtual display and presentation. Must run on the UI thread. */
    public void start() throws IOException {
        bitrateKbps = config.bitrateMbps * 1000;
        createEncoder();
        running = true;
        buildDisplay();
        drainThread = new Thread(this::drainLoop, "LudashiStream-drain");
        drainThread.start();
        Log.i(TAG, "stream started " + config.width + "x" + config.height + "@" + config.fps
                + " " + (config.hevc ? "HEVC" : "H.264") + " " + config.bitrateMbps + "Mbps");
    }

    /** Builds and starts a new encoder from the current config (sets encoder and inputSurface). */
    private void createEncoder() throws IOException {
        String mime = config.hevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat format = MediaFormat.createVideoFormat(mime, config.width, config.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
        // No periodic key frames (big bursts on Wi-Fi): clients request one when they need it.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3600);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / 10);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
        if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);

        encoder = MediaCodec.createEncoderByType(mime);
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (RuntimeException e) {
            // Some encoders reject optional keys; retry with the bare minimum.
            Log.w(TAG, "encoder rejected low-latency keys, retrying plain config", e);
            format.removeKey(MediaFormat.KEY_PRIORITY);
            if (Build.VERSION.SDK_INT >= 30) format.removeKey(MediaFormat.KEY_LOW_LATENCY);
            encoder.release();
            encoder = MediaCodec.createEncoderByType(mime);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        inputSurface = encoder.createInputSurface();
        encoder.start();
    }

    /** Virtual display (fed into the current encoder surface) + presentation. UI thread. */
    private void buildDisplay() {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        // Flags 0: private display owned by this app; only our presentation is shown on it.
        virtualDisplay = dm.createVirtualDisplay("LudashiStream", config.width, config.height,
                config.dpi, inputSurface, 0);

        presentation = new Presentation(context, virtualDisplay.getDisplay());
        presentationRoot = new FrameLayout(presentation.getContext());
        presentationRoot.setBackgroundColor(0xFF000000);
        // 1px "heartbeat" view: while a client watches, it changes every vsync so the
        // display is recomposited and the encoder gets a steady frame rate even when the
        // game picture is static (Qualcomm ignores KEY_REPEAT_PREVIOUS_FRAME_AFTER here).
        heartbeat = new View(presentation.getContext());
        presentationRoot.addView(heartbeat, new FrameLayout.LayoutParams(1, 1));
        presentation.setContentView(presentationRoot);
        if (presentation.getWindow() != null)
            presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        presentation.show();
    }

    // ---- MoonlightSession.VideoSource ----

    @Override
    public void configure(int width, int height, int fps, int kbps, boolean hevc) {
        int w = Math.max(640, Math.min(3840, width)) & ~1;
        int h = Math.max(360, Math.min(2160, height)) & ~1;
        if (w != config.width || h != config.height || hevc != config.hevc) {
            // New size or codec: new encoder + new virtual display + new presentation.
            runOnUiThreadAndWait(() -> rebuild(w, h, fps, kbps, hevc));
            return;
        }
        synchronized (encoderLock) {
            // Same size and codec: adjust on the fly, no encoder restart.
            config.fps = Math.max(10, Math.min(120, fps));
            bitrateKbps = kbps;
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000);
            try { encoder.setParameters(b); } catch (IllegalStateException e) { Log.w(TAG, "bitrate change failed", e); }
            requestKeyFrame();
            Log.i(TAG, "encoder updated in place: " + (hevc ? "HEVC" : "H.264") + " " + config.fps
                    + "fps " + kbps + "kbps");
        }
    }

    /** UI thread: swaps encoder, virtual display and presentation, moving the game view over. */
    private void rebuild(int w, int h, int fps, int kbps, boolean hevc) {
        synchronized (encoderLock) {
            MediaCodec oldEnc = encoder;
            Surface oldSurface = inputSurface;
            VirtualDisplay oldVd = virtualDisplay;
            Presentation oldPres = presentation;
            FrameLayout oldRoot = presentationRoot;
            int ow = config.width, oh = config.height, ofps = config.fps, okbps = bitrateKbps;
            boolean ohevc = config.hevc;
            config.width = w; config.height = h; config.hevc = hevc;
            config.fps = Math.max(10, Math.min(120, fps));
            bitrateKbps = kbps;
            try {
                createEncoder();
            } catch (Exception e) {
                Log.e(TAG, "encoder for " + w + "x" + h + " failed, keeping the old stream", e);
                config.width = ow; config.height = oh; config.fps = ofps; config.hevc = ohevc; bitrateKbps = okbps;
                encoder = oldEnc; inputSurface = oldSurface;
                return;
            }
            codecConfig = null;
            if (rendererView != null && oldRoot != null) oldRoot.removeView(rendererView);
            try { oldPres.dismiss(); } catch (RuntimeException ignored) {}
            buildDisplay();
            if (rendererView != null) {
                presentationRoot.addView(rendererView, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                heartbeat.bringToFront();
            }
            oldVd.release();
            try { oldEnc.stop(); } catch (RuntimeException ignored) {}
            oldEnc.release();
            oldSurface.release();
            Log.i(TAG, "stream rebuilt: " + w + "x" + h + " " + (hevc ? "HEVC" : "H.264") + " "
                    + config.fps + "fps " + kbps + "kbps");
        }
    }

    private void runOnUiThreadAndWait(Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { r.run(); return; }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try { r.run(); } finally { done.countDown(); }
        });
        try { done.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    @Override
    public void requestIdr() { requestKeyFrame(); }

    @Override
    public void setFrameListener(com.winlator.cmod.stream.moonlight.MoonlightSession.FrameListener listener) {
        frameListener = listener;
        if (listener != null) requestKeyFrame();
    }

    public void setAudioTap(AudioTap tap) { audioTap = tap; }

    @Override
    public void setPcmListener(AudioTap.PcmListener listener) {
        AudioTap tap = audioTap;
        if (tap != null) tap.setListener(listener);
    }

    // ---- stats for the phone status bar ----
    private long statWindowStart;
    private int statFrames;
    private long statBytes;
    private volatile int lastFps;
    private volatile float lastMbps;

    private void countStats(int bytes) {
        long now = System.nanoTime();
        if (statWindowStart == 0) statWindowStart = now;
        statFrames++;
        statBytes += bytes;
        long dt = now - statWindowStart;
        if (dt >= 1_000_000_000L) {
            lastFps = (int) Math.round(statFrames * 1e9 / dt);
            lastMbps = (float) (statBytes * 8 * 1e3 / dt);
            statFrames = 0;
            statBytes = 0;
            statWindowStart = now;
        }
    }

    public String getLocalIp() {
        String ip = localIpv4();
        return ip != null ? ip : "?";
    }

    public int getFps() { return lastFps; }
    public float getMbps() { return lastMbps; }
    public int getWidth() { return config.width; }
    public int getHeight() { return config.height; }

    /** Shows the game renderer view on the stream display instead of the phone. */
    public void attachRendererView(View rendererView) {
        this.rendererView = rendererView;
        presentationRoot.addView(rendererView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Heartbeat must be above the renderer SurfaceView's hole to be composited.
        heartbeat.bringToFront();
        Choreographer.getInstance().postFrameCallback(heartbeatTick);
    }

    private final Choreographer.FrameCallback heartbeatTick = new Choreographer.FrameCallback() {
        private boolean odd;
        private long last;
        @Override public void doFrame(long frameTimeNanos) {
            if (!running || heartbeat == null) return;
            // The phone panel may run at 120 Hz; pace the heartbeat to the stream fps.
            long interval = 1_000_000_000L / config.fps - 2_000_000L;
            if (frameListener != null && frameTimeNanos - last >= interval) {
                last = frameTimeNanos;
                odd = !odd;
                heartbeat.setBackgroundColor(odd ? 0xFF000001 : 0xFF000000);
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void requestKeyFrame() {
        MediaCodec enc = encoder;
        if (enc == null) return;
        Bundle b = new Bundle();
        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try { enc.setParameters(b); } catch (IllegalStateException ignored) {}
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] chunk = new byte[0];
        while (running) {
            MediaCodec enc = encoder;
            int index;
            try {
                index = enc.dequeueOutputBuffer(info, 20_000);
            } catch (IllegalStateException e) {
                if (running && enc != encoder) continue; // encoder was swapped by rebuild()
                Log.e(TAG, "drain loop stopped", e);
                break;
            }
            if (index < 0) continue;
            try {
                ByteBuffer buf = enc.getOutputBuffer(index);
                if (buf != null && info.size > 0) {
                    if (chunk.length < info.size) chunk = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(chunk, 0, info.size);
                    boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (isConfig) {
                        byte[] cfg = new byte[info.size];
                        System.arraycopy(chunk, 0, cfg, 0, info.size);
                        codecConfig = cfg;
                    } else {
                        countStats(info.size);
                        boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        com.winlator.cmod.stream.moonlight.MoonlightSession.FrameListener fl = frameListener;
                        if (fl != null) fl.onFrame(chunk, info.size, info.presentationTimeUs, isKey, codecConfig);
                    }
                }
                enc.releaseOutputBuffer(index, false);
            } catch (IllegalStateException swapped) {
                // The encoder was stopped by rebuild() while we held this buffer.
            } catch (RuntimeException e) {
                Log.e(TAG, "drain: frame handling failed", e);
            }
        }
    }

    public void stop() {
        if (!running && encoder == null) return;
        running = false;
        if (presentation != null) {
            try { presentation.dismiss(); } catch (RuntimeException ignored) {}
            presentation = null;
        }
        if (drainThread != null) {
            try { drainThread.join(500); } catch (InterruptedException ignored) {}
        }
        if (encoder != null) {
            try { encoder.stop(); } catch (RuntimeException ignored) {}
            encoder.release();
            encoder = null;
        }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
        Log.i(TAG, "stream stopped");
    }

    private static String localIpv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                boolean preferred = ni.getName().startsWith("wlan");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && preferred)
                        return a.getHostAddress();
                }
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
