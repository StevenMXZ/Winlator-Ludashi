package com.winlator.cmod.stream.moonlight;

/** libopus encoder (stereo, 48 kHz, restricted low delay). Not thread-safe. */
final class OpusEncoderJni {
    static {
        System.loadLibrary("ludashi_stream");
    }

    private long handle;

    OpusEncoderJni(int sampleRate, int channels, int bitrate) {
        handle = nativeCreate(sampleRate, channels, bitrate);
        if (handle == 0) throw new IllegalStateException("opus_encoder_create failed");
    }

    /** pcm holds frameSize * channels interleaved samples. Returns encoded length or < 0. */
    int encode(short[] pcm, int frameSize, byte[] out) {
        return handle == 0 ? -1 : nativeEncode(handle, pcm, frameSize, out);
    }

    void destroy() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }

    private static native long nativeCreate(int sampleRate, int channels, int bitrate);
    private static native int nativeEncode(long handle, short[] pcm, int frameSize, byte[] out);
    private static native void nativeDestroy(long handle);
}
