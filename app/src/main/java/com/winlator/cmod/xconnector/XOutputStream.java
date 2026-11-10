package com.winlator.cmod.xconnector;

import com.winlator.cmod.xserver.XServer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;
import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

public class XOutputStream implements XStreamLock {
    private final ReentrantLock lock = new ReentrantLock();
    private long nativePtr;
    private ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
    static { System.loadLibrary("winlator"); registerNatives(); }

    public XOutputStream(int fd, int initialCapacity) {
        nativePtr = nativeAllocate(fd, initialCapacity);
        if (nativePtr == 0) throw new OutOfMemoryError("Unable to allocate XOutputStream");
    }
    public void setByteOrder(ByteOrder order) {
        byteOrder = order; setByteOrder(nativePtr, order == ByteOrder.BIG_ENDIAN);
    }
    public void setAncillaryFd(int fd) { setAncillaryFd(nativePtr, fd); }
    public void writeByte(byte value) { writeByte(nativePtr, value); }
    public void writeShort(short value) { writeShort(nativePtr, value); }
    public void writeInt(int value) { writeInt(nativePtr, value); }
    public void writeIntPair(int first, int second) { writeInt(first); writeInt(second); }
    public void writeLong(long value) { writeLong(nativePtr, value); }
    public void writeFP3232(double value) {
        double integral = Math.floor(value);
        writeInt((int)integral); writeInt((int)(long)((value - integral) * (1L << 32)));
    }
    public void writeString8(String value) {
        byte[] data = value.getBytes(XServer.LATIN1_CHARSET); write(data);
        int padding = -data.length & 3; if (padding > 0) writePad(padding);
    }
    public void write(byte[] data) { write(data, 0, data.length); }
    public void write(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length || length > data.length - offset)
            throw new IndexOutOfBoundsException();
        writeBytes(nativePtr, data, offset, length);
    }
    public void write(ByteBuffer data) {
        int remaining = data.remaining();
        if (data.isDirect()) {
            writeByteBuffer(nativePtr, data, data.position(), remaining);
            data.position(data.limit());
        } else {
            byte[] bytes = new byte[remaining]; data.get(bytes); write(bytes);
        }
    }
    public void writeAt(int position, byte[] data) {
        if (position < 0 || position > length() || data.length > length() - position)
            throw new IndexOutOfBoundsException();
        writeAt(nativePtr, position, data);
    }
    public void writeShortAt(int position, short value) {
        writeAt(position, ByteBuffer.allocate(2).order(byteOrder).putShort(value).array());
    }
    public void writeIntAt(int position, int value) {
        writeAt(position, ByteBuffer.allocate(4).order(byteOrder).putInt(value).array());
    }
    public void writePad(int length) {
        if (length < 0) throw new IllegalArgumentException("Negative padding");
        writePad(nativePtr, length);
    }
    public XStreamLock lock() { lock.lock(); return this; }
    public void destroy() {
        lock.lock();
        try { long pointer = nativePtr; nativePtr = 0; if (pointer != 0) destroy(pointer); }
        finally { lock.unlock(); }
    }
    @Override public void close() throws IOException {
        try { if (!sendData(nativePtr)) throw new IOException("Unable to send X11 data"); }
        finally { lock.unlock(); }
    }
    public int length() { return length(nativePtr); }
    public void writeSuccessReply(int sequence, int replyLength) throws IOException {
        try (XStreamLock ignored = lock()) {
            writeByte((byte)1); writeByte((byte)0); writeShort((short)sequence);
            writeInt(replyLength); writePad(24);
        }
    }
    private native long nativeAllocate(int fd, int initialCapacity);
    private static native void registerNatives();
    // Sending to a slow reader must not pin the runtime in CriticalNative.
    private static native boolean sendData(long nativePtr);
    private static native void destroy(long nativePtr);
    @FastNative private static native void writeBytes(long nativePtr, byte[] data, int offset, int length);
    @FastNative private static native void writeAt(long nativePtr, int position, byte[] data);
    @FastNative private static native void writeByteBuffer(long nativePtr, ByteBuffer data, int offset, int length);
    @CriticalNative private static native void setAncillaryFd(long nativePtr, int fd);
    @CriticalNative private static native void setByteOrder(long nativePtr, boolean bigEndian);
    @CriticalNative private static native void writeByte(long nativePtr, byte value);
    @CriticalNative private static native void writeShort(long nativePtr, short value);
    @CriticalNative private static native void writeInt(long nativePtr, int value);
    @CriticalNative private static native void writeLong(long nativePtr, long value);
    @CriticalNative private static native void writePad(long nativePtr, int length);
    @CriticalNative private static native int length(long nativePtr);
}
