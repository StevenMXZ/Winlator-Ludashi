package com.winlator.cmod.stream.moonlight;

/** Minimal Java side of the ENet JNI bridge (libludashi_stream.so). */
final class EnetHost {
    static final int EVENT_NONE = 0, EVENT_CONNECT = 1, EVENT_DISCONNECT = 2, EVENT_RECEIVE = 3;

    static {
        System.loadLibrary("ludashi_stream");
    }

    private long host;
    private final byte[] buf = new byte[64 * 1024];
    private final long[] info = new long[4];

    /** Last event: peer handle, data (received length or connect data), channel. */
    long peer;
    long data;
    byte[] payload() { return buf; }
    int payloadLength() { return (int) data; }

    EnetHost(int port, int peers) {
        host = nativeCreate(port, peers);
        if (host == 0) throw new IllegalStateException("ENet host create failed on port " + port);
    }

    /** Waits up to timeoutMs for one event; returns EVENT_* or -1 on error. */
    synchronized int service(int timeoutMs) {
        if (host == 0) return -1;
        int type = nativeService(host, timeoutMs, buf, info);
        if (type > 0) {
            peer = info[0];
            data = info[2];
        }
        return type;
    }

    synchronized boolean send(long peerHandle, byte[] data, int len) {
        return host != 0 && nativeSend(host, peerHandle, data, len, true) == 0;
    }

    synchronized void disconnect(long peerHandle) {
        if (host != 0) nativeDisconnect(host, peerHandle);
    }

    static String peerAddress(long peerHandle) { return nativePeerAddress(peerHandle); }

    synchronized void destroy() {
        if (host != 0) {
            nativeDestroy(host);
            host = 0;
        }
    }

    private static native long nativeCreate(int port, int peers);
    private static native int nativeService(long host, int timeoutMs, byte[] buf, long[] info);
    private static native int nativeSend(long host, long peer, byte[] data, int len, boolean reliable);
    private static native void nativeDisconnect(long host, long peer);
    private static native String nativePeerAddress(long peer);
    private static native void nativeDestroy(long host);
}
