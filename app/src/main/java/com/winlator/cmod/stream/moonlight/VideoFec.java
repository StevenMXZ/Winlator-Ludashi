package com.winlator.cmod.stream.moonlight;

/** Reed-Solomon parity shards for Moonlight video FEC (nanors via JNI). */
final class VideoFec {
    static {
        System.loadLibrary("ludashi_stream");
    }

    private VideoFec() {}

    /** buf = (data + parity) shards of blockSize bytes each; parity is filled in place. */
    static boolean encode(byte[] buf, int dataShards, int parityShards, int blockSize) {
        return nativeEncode(buf, dataShards, parityShards, blockSize) == 0;
    }

    private static native int nativeEncode(byte[] buf, int dataShards, int parityShards, int blockSize);
}
