package com.winlator.cmod.stream;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Copies the game's sound for the stream. An extra ALSA config makes Wine's default device
 * "plug -> file(tee to FIFO) -> android_alsa (Oboe)": the phone keeps playing (and keeps the
 * real-time clock), and a copy as 48 kHz stereo s16le arrives in our FIFO.
 */
public final class AudioTap {
    private static final String TAG = "LudashiStream";

    public interface PcmListener {
        /** Interleaved stereo s16le, 48 kHz. */
        void onPcm(byte[] data, int len);
    }

    private final File fifo;
    private final File conf;
    private FileDescriptor fd;
    private volatile boolean running;
    private volatile PcmListener listener;

    public AudioTap(File imageFsRoot) {
        // Not under usr/tmp: Winlator clears that directory when the environment starts.
        fifo = new File(imageFsRoot.getParentFile(), "ludashi_stream/audio.fifo");
        conf = new File(imageFsRoot, "usr/etc/alsa/conf.d/ludashi_stream.conf");
    }

    /** Creates FIFO + ALSA config and starts reading. Returns the config file to append to ALSA_CONFIG_PATH. */
    public String start() throws Exception {
        fifo.getParentFile().mkdirs();
        if (fifo.exists()) fifo.delete();
        Os.mkfifo(fifo.getPath(), 0600);
        String cfg = "pcm.ludashi_tap {\n"
                + "    type file\n"
                + "    slave.pcm \"android_alsa\"\n"
                + "    file \"" + fifo.getPath() + "\"\n"
                + "    format \"raw\"\n"
                + "}\n"
                + "pcm.!default {\n"
                + "    type plug\n"
                + "    slave {\n"
                + "        pcm \"ludashi_tap\"\n"
                // S16_LE conversion makes the Oboe plugin reject hw_params (EINVAL);
                // float32 is what Wine picks anyway, we convert to s16 ourselves.
                + "        format FLOAT_LE\n"
                + "        rate 48000\n"
                + "        channels 2\n"
                + "    }\n"
                + "}\n";
        try (FileOutputStream out = new FileOutputStream(conf)) {
            out.write(cfg.getBytes(StandardCharsets.US_ASCII));
        }
        // O_RDWR: the writer (Wine) never blocks on open, and we never see EOF between streams.
        fd = Os.open(fifo.getPath(), OsConstants.O_RDWR, 0);
        running = true;
        Thread t = new Thread(this::readLoop, "LudashiStream-audio");
        t.setDaemon(true);
        t.start();
        Log.i(TAG, "audio tap ready: " + fifo);
        return conf.getPath();
    }

    public void setListener(PcmListener l) { listener = l; }

    private void readLoop() {
        byte[] buf = new byte[7680 + 4];   // 20 ms of 48 kHz stereo float32 (+ carried bytes)
        byte[] s16 = new byte[3840 + 4];
        int carry = 0;                     // bytes of an incomplete float kept at buf[0..carry)
        try (FileInputStream in = new FileInputStream(fd)) {
            while (running) {
                int n = in.read(buf, carry, buf.length - carry);
                if (n <= 0) continue;
                int total = carry + n;
                int whole = total & ~3;
                PcmListener l = listener;
                if (l != null) {
                    int o = 0;
                    for (int i = 0; i < whole; i += 4) {
                        int bits = (buf[i] & 0xFF) | (buf[i + 1] & 0xFF) << 8 | (buf[i + 2] & 0xFF) << 16 | buf[i + 3] << 24;
                        float f = Float.intBitsToFloat(bits);
                        int v = (int) (f * 32767f);
                        if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
                        s16[o++] = (byte) v;
                        s16[o++] = (byte) (v >> 8);
                    }
                    l.onPcm(s16, o);
                } // otherwise drained and dropped
                carry = total - whole;
                if (carry > 0) System.arraycopy(buf, whole, buf, 0, carry);
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "audio tap read failed", e);
        }
    }

    public void stop() {
        running = false;
        listener = null;
        // Unblock the reader, then remove the files so normal (non-stream) runs are unaffected.
        try { Os.write(fd, new byte[4], 0, 4); } catch (Exception ignored) {}
        conf.delete();
        fifo.delete();
    }
}
