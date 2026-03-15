package com.winlator.cmod.xconnector;

import com.winlator.cmod.xserver.XServer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.locks.ReentrantLock;

/* loaded from: classes13.dex */
public class XOutputStream {
    private static final byte[] ZERO = new byte[64];
    private int ancillaryFd;
    public ByteBuffer buffer;
    public final ClientSocket clientSocket;
    private final ReentrantLock lock;

    public XOutputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XOutputStream(ClientSocket clientSocket, int initialCapacity) {
        this.lock = new ReentrantLock();
        this.ancillaryFd = -1;
        this.clientSocket = clientSocket;
        this.buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public void setByteOrder(ByteOrder byteOrder) {
        this.buffer.order(byteOrder);
    }

    public void setAncillaryFd(int ancillaryFd) {
        this.ancillaryFd = ancillaryFd;
    }

    public void writeByte(byte value) {
        ensureSpaceIsAvailable(1);
        this.buffer.put(value);
    }

    public void writeShort(short value) {
        ensureSpaceIsAvailable(2);
        this.buffer.putShort(value);
    }

    public void writeInt(int value) {
        ensureSpaceIsAvailable(4);
        this.buffer.putInt(value);
    }

    public void writeLong(long value) {
        ensureSpaceIsAvailable(8);
        this.buffer.putLong(value);
    }

    public void writeString8(String str) {
        byte[] bytes = str.getBytes(XServer.LATIN1_CHARSET);
        int length = (-str.length()) & 3;
        ensureSpaceIsAvailable(bytes.length + length);
        this.buffer.put(bytes);
        if (length > 0) {
            writePad(length);
        }
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void write(byte[] data, int offset, int length) {
        ensureSpaceIsAvailable(length);
        this.buffer.put(data, offset, length);
    }

    public void write(ByteBuffer data) {
        ensureSpaceIsAvailable(data.remaining());
        this.buffer.put(data);
    }

    public void writePad(int length) {
        write(ZERO, 0, length);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void flush() throws IOException {
        if (this.buffer.position() != 0) {
            this.buffer.flip();
            if (this.ancillaryFd != -1) {
                this.clientSocket.sendAncillaryMsg(this.buffer, this.ancillaryFd);
                this.ancillaryFd = -1;
            } else {
                this.clientSocket.write(this.buffer);
            }
            this.buffer.clear();
        }
    }

    public XStreamLock lock() {
        return new OutputStreamLock();
    }

    private void ensureSpaceIsAvailable(int length) {
        int position = this.buffer.position();
        if (this.buffer.capacity() - position >= length) {
            return;
        }
        ByteBuffer newBuffer = ByteBuffer.allocateDirect(this.buffer.capacity() + length).order(this.buffer.order());
        this.buffer.rewind();
        newBuffer.put(this.buffer).position(position);
        this.buffer = newBuffer;
    }

    private class OutputStreamLock implements XStreamLock {
        public OutputStreamLock() {
            XOutputStream.this.lock.lock();
        }

        @Override // com.winlator.cmod.xconnector.XStreamLock, java.lang.AutoCloseable
        public void close() throws IOException {
            try {
                XOutputStream.this.flush();
            } finally {
                XOutputStream.this.lock.unlock();
            }
        }
    }

    public void writeSuccessReply(int sequenceNumber, int replyLength) throws IOException {
        XStreamLock lock = lock();
        try {
            writeByte((byte) 1);
            writeByte((byte) 0);
            writeShort((short) sequenceNumber);
            writeInt(replyLength);
            writePad(24);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }
}
