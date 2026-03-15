package com.winlator.cmod.alsaserver;

import com.winlator.cmod.alsaserver.ALSAClient;
import com.winlator.cmod.sysvshm.SysVSharedMemory;
import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.XConnectorEpoll;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import java.io.IOException;
import java.nio.ByteBuffer;

/* loaded from: classes7.dex */
public class ALSARequestHandler implements RequestHandler {
    private int maxSHMemoryId = 0;

    @Override // com.winlator.cmod.xconnector.RequestHandler
    public boolean handleRequest(Client client) throws IOException {
        ALSAClient alsaClient = (ALSAClient) client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();
        if (inputStream.available() < 5) {
            return false;
        }
        byte requestCode = inputStream.readByte();
        int requestLength = inputStream.readInt();
        switch (requestCode) {
            case 0:
                alsaClient.release();
                return true;
            case 1:
                alsaClient.start();
                return true;
            case 2:
                alsaClient.stop();
                return true;
            case 3:
                alsaClient.pause();
                return true;
            case 4:
                if (inputStream.available() < requestLength) {
                    return false;
                }
                alsaClient.setChannelCount(inputStream.readByte());
                alsaClient.setDataType(ALSAClient.DataType.values()[inputStream.readByte()]);
                alsaClient.setSampleRate(inputStream.readInt());
                alsaClient.setBufferSize(inputStream.readInt());
                alsaClient.prepare();
                createSharedMemory(alsaClient, outputStream);
                return true;
            case 5:
                ByteBuffer buffer = alsaClient.getSharedBuffer();
                if (buffer != null) {
                    buffer.limit(requestLength);
                    alsaClient.writeDataToStream(buffer);
                    return true;
                }
                if (inputStream.available() < requestLength) {
                    return false;
                }
                alsaClient.writeDataToStream(inputStream.readByteBuffer(requestLength));
                return true;
            case 6:
                alsaClient.drain();
                return true;
            case 7:
                XStreamLock lock = outputStream.lock();
                try {
                    outputStream.writeInt(alsaClient.pointer());
                    if (lock != null) {
                        lock.close();
                        return true;
                    }
                    return true;
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
            default:
                return true;
        }
    }

    private void createSharedMemory(ALSAClient alsaClient, XOutputStream outputStream) throws IOException {
        ByteBuffer buffer;
        int size = alsaClient.getBufferSizeInBytes();
        StringBuilder append = new StringBuilder().append("alsa-shm");
        int i = this.maxSHMemoryId + 1;
        this.maxSHMemoryId = i;
        int fd = SysVSharedMemory.createMemoryFd(append.append(i).toString(), size);
        if (fd >= 0 && (buffer = SysVSharedMemory.mapSHMSegment(fd, size, 0, true)) != null) {
            alsaClient.setSharedBuffer(buffer);
        }
        try {
            XStreamLock lock = outputStream.lock();
            try {
                outputStream.writeByte((byte) 0);
                outputStream.setAncillaryFd(fd);
                if (lock != null) {
                    lock.close();
                }
            } finally {
            }
        } finally {
            if (fd >= 0) {
                XConnectorEpoll.closeFd(fd);
            }
        }
    }
}
