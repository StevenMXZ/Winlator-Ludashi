package com.winlator.cmod.xconnector;

import com.winlator.cmod.xserver.XServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

import dalvik.annotation.optimization.CriticalNative;

public class XOutputStream implements XStreamLock {
    private final ReentrantLock lock = new ReentrantLock();
    private final long nativePtr;

    static {
        System.loadLibrary("winlator");
    }

    public XOutputStream(int clientFd, int initialCapacity) {
        nativePtr = nativeAllocate(clientFd, initialCapacity);
    }

    public void setAncillaryFd(int ancillaryFd) {
        setAncillaryFd(nativePtr, ancillaryFd);
    }

    public void writeByte(byte value) {
        writeByte(nativePtr, value);
    }

    public void writeShort(short value) {
        writeShort(nativePtr, value);
    }

    public void writeInt(int value) {
        writeInt(nativePtr, value);
    }
    
    public void writeIntPair(int p1, int p2) {
         writeInt(p1);
         writeInt(p2);
    }

    public void writeLong(long value) {
        writeLong(nativePtr, value);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = -str.length() & 3;
        write(bytes);
        if (length > 0) writePad(length);
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        for (int i = offset; i < length; i++) writeByte(nativePtr, data[i]);
    }

    public void writeShortAt(int position, short value) {
        final byte[] data = {
            (byte)(value & 0xff),
            (byte)((value >> 8) & 0xff)
        };
        writeAt(nativePtr, position, data);
    }

    public void writeIntAt(int position, int value) {
        final byte[] data = {
            (byte)(value & 0xff),
            (byte)((value >>> 8) & 0xff),
            (byte)((value >>> 16) & 0xff),
            (byte)((value >>> 24) & 0xff)
        };
        writeAt(nativePtr, position, data);
    }

    public void writeAt(int position, byte[] data) {
        writeAt(nativePtr, position, data);
    }

    public void write(ByteBuffer data) {
        if (data.isDirect()) {
            writeByteBuffer(nativePtr, data, data.position(), data.remaining());
        }
        else {
            for (int i = data.position(), length = data.remaining(); i < length; i++) {
                writeByte(nativePtr, data.get(i));
            }
        }
    }

    public void writePad(int length) {
        writePad(nativePtr, length);
    }

    public void writeFP3232(double value) {
        double integral = Math.floor(value);
        long frac = (long)((value - integral) * (1L<<32));
        writeInt((int)integral);
        writeInt((int)frac);
    }

    public XStreamLock lock() {
        lock.lock();
        return this;
    }
    
    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        try (XStreamLock lock = lock()) {
            writeByte((byte) 1);
            writeByte((byte) 0);     
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
         }
    }

    public void destroy() {
        destroy(nativePtr);
    }

    @Override
    public void close() throws IOException {
        try {
            if (!sendData(nativePtr)) throw new IOException("Failed to send data.");
        }
        finally {
            lock.unlock();
        }
    }

    public int length() {
        return length(nativePtr);
    }

    private native long nativeAllocate(int fd, int initialCapacity);

    @CriticalNative
    private static native void setAncillaryFd(long nativePtr, int ancillaryFd);

    @CriticalNative
    private static native void writeByte(long nativePtr, byte value);

    @CriticalNative
    private static native void writeShort(long nativePtr, short value);

    @CriticalNative
    private static native void writeInt(long nativePtr, int value);

    @CriticalNative
    private static native void writeLong(long nativePtr, long value);

    @CriticalNative
    private static native void writePad(long nativePtr, int length);

    private static native void writeAt(long nativePtr, int position, byte[] data);

    private static native void writeByteBuffer(long nativePtr, ByteBuffer data, int offset, int length);

    @CriticalNative
    private static native boolean sendData(long nativePtr);

    @CriticalNative
    private static native void destroy(long nativePtr);

    @CriticalNative
    private static native int length(long nativePtr);
}