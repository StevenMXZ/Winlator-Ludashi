package com.winlator.cmod.xconnector;

import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/* loaded from: classes13.dex */
public class ClientSocket {
    private final ArrayDeque<Integer> ancillaryFds = new ArrayDeque<>();
    public final int fd;

    private native int read(int i, ByteBuffer byteBuffer, int i2, int i3);

    private native int recvAncillaryMsg(int i, ByteBuffer byteBuffer, int i2, int i3);

    private native int sendAncillaryMsg(int i, ByteBuffer byteBuffer, int i2, int i3);

    private native int write(int i, ByteBuffer byteBuffer, int i2);

    static {
        System.loadLibrary("winlator");
    }

    public ClientSocket(int fd) {
        this.fd = fd;
    }

    public boolean hasAncillaryFds() {
        return !this.ancillaryFds.isEmpty();
    }

    public int getAncillaryFd() {
        if (hasAncillaryFds()) {
            return this.ancillaryFds.poll().intValue();
        }
        return -1;
    }

    public void addAncillaryFd(int ancillaryFd) {
        this.ancillaryFds.add(Integer.valueOf(ancillaryFd));
    }

    public int read(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = read(this.fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        if (bytesRead == 0) {
            return -1;
        }
        throw new IOException("Failed to read data.");
    }

    public void write(ByteBuffer data) throws IOException {
        int bytesWritten = write(this.fd, data, data.limit());
        if (bytesWritten >= 0) {
            data.position(bytesWritten);
        } else {
            Log.d("ClientSocket", "Failed to write data.");
        }
    }

    public int recvAncillaryMsg(ByteBuffer data) throws IOException {
        int position = data.position();
        int bytesRead = recvAncillaryMsg(this.fd, data, position, data.remaining());
        if (bytesRead > 0) {
            data.position(position + bytesRead);
            return bytesRead;
        }
        if (bytesRead == 0) {
            return -1;
        }
        throw new IOException("Failed to receive ancillary messages.");
    }

    public void sendAncillaryMsg(ByteBuffer data, int ancillaryFd) throws IOException {
        int bytesSent = sendAncillaryMsg(this.fd, data, data.limit(), ancillaryFd);
        if (bytesSent >= 0) {
            data.position(bytesSent);
            return;
        }
        throw new IOException("Failed to send ancillary messages.");
    }
}
