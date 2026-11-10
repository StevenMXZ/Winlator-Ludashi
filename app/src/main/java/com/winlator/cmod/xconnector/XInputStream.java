package com.winlator.cmod.xconnector;

import com.winlator.cmod.xserver.XServer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

public class XInputStream {
    private long nativePtr;
    private int remaining;
    private ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
    static { System.loadLibrary("winlator"); registerNatives(); }

    public XInputStream(int fd, int initialCapacity) {
        nativePtr = nativeAllocate(fd, initialCapacity);
        if (nativePtr == 0) throw new OutOfMemoryError("Unable to allocate XInputStream");
    }
    private void consume(int length) {
        if (length < 0 || length > remaining) throw new BufferUnderflowException();
        remaining -= length;
    }
    public int readMoreData(boolean ancillary) {
        int result = readMoreData(nativePtr, ancillary);
        remaining = available(nativePtr);
        return result;
    }
    public void setByteOrder(ByteOrder order) {
        byteOrder = order;
        setByteOrder(nativePtr, order == ByteOrder.BIG_ENDIAN);
    }
    public int getAncillaryFd() { return getAncillaryFd(nativePtr); }
    public int getActivePosition() { return getActivePosition(nativePtr); }
    public void setActivePosition(int position) {
        setActivePosition(nativePtr, position); remaining = available(nativePtr);
    }
    public int available() { return remaining; }
    public byte readByte() { consume(1); return readByte(nativePtr); }
    public int readUnsignedByte() { return Byte.toUnsignedInt(readByte()); }
    public short readShort() { consume(2); return readShort(nativePtr); }
    public int readUnsignedShort() { return Short.toUnsignedInt(readShort()); }
    public int readInt() { consume(4); return readInt(nativePtr); }
    public long readUnsignedInt() { return Integer.toUnsignedLong(readInt()); }
    public long readLong() { consume(8); return readLong(nativePtr); }
    public void read(byte[] data) { consume(data.length); readBytes(nativePtr, data); }
    public ByteBuffer readByteBuffer(int length) {
        consume(length); return readByteBuffer(nativePtr, length).order(byteOrder);
    }
    public String readString8(int length) {
        byte[] data = new byte[length]; read(data);
        int pad = -length & 3; if (pad > 0) skip(pad);
        return new String(data, XServer.LATIN1_CHARSET);
    }
    public void skip(int length) { consume(length); skip(nativePtr, length); }
    public void destroy() {
        long pointer = nativePtr; nativePtr = 0; remaining = 0;
        if (pointer != 0) destroy(pointer);
    }
    private native long nativeAllocate(int fd, int initialCapacity);
    private static native void registerNatives();
    // Socket I/O can block and must use the ordinary JNI transition.
    private static native int readMoreData(long pointer, boolean ancillary);
    private static native void destroy(long pointer);
    @FastNative private static native void readBytes(long pointer, byte[] output);
    @FastNative private static native ByteBuffer readByteBuffer(long pointer, int length);
    @CriticalNative private static native byte readByte(long nativePtr);
    @CriticalNative private static native short readShort(long nativePtr);
    @CriticalNative private static native int readInt(long nativePtr);
    @CriticalNative private static native long readLong(long nativePtr);
    @CriticalNative private static native void skip(long nativePtr, int length);
    @CriticalNative private static native int available(long nativePtr);
    @CriticalNative private static native int getActivePosition(long nativePtr);
    @CriticalNative private static native void setActivePosition(long nativePtr, int position);
    @CriticalNative private static native int getAncillaryFd(long nativePtr);
    @CriticalNative private static native void setByteOrder(long nativePtr, boolean bigEndian);
}
