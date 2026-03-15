package com.winlator.cmod.xconnector;

import com.winlator.cmod.xserver.XServer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/* loaded from: classes13.dex */
public class XInputStream {
    private ByteBuffer activeBuffer;
    private ByteBuffer buffer;
    public final ClientSocket clientSocket;

    public XInputStream(int initialCapacity) {
        this(null, initialCapacity);
    }

    public XInputStream(ClientSocket clientSocket, int initialCapacity) {
        this.clientSocket = clientSocket;
        this.buffer = ByteBuffer.allocateDirect(initialCapacity);
    }

    public int readMoreData(boolean canReceiveAncillaryMessages) throws IOException {
        if (this.activeBuffer != null) {
            if (!this.activeBuffer.hasRemaining()) {
                this.buffer.clear();
            } else if (this.activeBuffer.position() > 0) {
                int newLimit = this.buffer.position();
                this.buffer.position(this.activeBuffer.position()).limit(newLimit);
                this.buffer.compact();
            }
            this.activeBuffer = null;
        }
        growInputBufferIfNecessary();
        ClientSocket clientSocket = this.clientSocket;
        ByteBuffer byteBuffer = this.buffer;
        int bytesRead = canReceiveAncillaryMessages ? clientSocket.recvAncillaryMsg(byteBuffer) : clientSocket.read(byteBuffer);
        if (bytesRead > 0) {
            int position = this.buffer.position();
            this.buffer.flip();
            this.activeBuffer = this.buffer.slice().order(this.buffer.order());
            this.buffer.limit(this.buffer.capacity()).position(position);
        }
        return bytesRead;
    }

    public int getAncillaryFd() {
        return this.clientSocket.getAncillaryFd();
    }

    private void growInputBufferIfNecessary() {
        if (this.buffer.position() == this.buffer.capacity()) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(this.buffer.capacity() * 2).order(this.buffer.order());
            this.buffer.rewind();
            newBuffer.put(this.buffer);
            this.buffer = newBuffer;
        }
    }

    public void setByteOrder(ByteOrder byteOrder) {
        this.buffer.order(byteOrder);
        if (this.activeBuffer != null) {
            this.activeBuffer.order(byteOrder);
        }
    }

    public int getActivePosition() {
        return this.activeBuffer.position();
    }

    public void setActivePosition(int activePosition) {
        this.activeBuffer.position(activePosition);
    }

    public int available() {
        return this.activeBuffer.remaining();
    }

    public byte readByte() {
        return this.activeBuffer.get();
    }

    public int readUnsignedByte() {
        return Byte.toUnsignedInt(this.activeBuffer.get());
    }

    public short readShort() {
        return this.activeBuffer.getShort();
    }

    public int readUnsignedShort() {
        return Short.toUnsignedInt(this.activeBuffer.getShort());
    }

    public int readInt() {
        return this.activeBuffer.getInt();
    }

    public long readUnsignedInt() {
        return Integer.toUnsignedLong(this.activeBuffer.getInt());
    }

    public long readLong() {
        return this.activeBuffer.getLong();
    }

    public void read(byte[] result) {
        this.activeBuffer.get(result);
    }

    public ByteBuffer readByteBuffer(int length) {
        ByteBuffer newBuffer = this.activeBuffer.slice().order(this.activeBuffer.order());
        newBuffer.limit(length);
        this.activeBuffer.position(this.activeBuffer.position() + length);
        return newBuffer;
    }

    public String readString8(int length) {
        byte[] bytes = new byte[length];
        read(bytes);
        String str = new String(bytes, XServer.LATIN1_CHARSET);
        if (((-length) & 3) > 0) {
            skip((-length) & 3);
        }
        return str;
    }

    public void skip(int length) {
        this.activeBuffer.position(this.activeBuffer.position() + length);
    }
}
