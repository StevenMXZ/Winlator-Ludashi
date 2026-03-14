package com.winlator.cmod.sysvshm;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import java.io.IOException;

/* loaded from: classes11.dex */
public class SysVSHMRequestHandler implements RequestHandler {
    @Override // com.winlator.cmod.xconnector.RequestHandler
    public boolean handleRequest(Client client) throws IOException {
        XStreamLock lock;
        SysVSharedMemory sysVSharedMemory = (SysVSharedMemory) client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();
        if (inputStream.available() < 5) {
            return false;
        }
        byte requestCode = inputStream.readByte();
        switch (requestCode) {
            case 0:
                long size = inputStream.readUnsignedInt();
                int shmid = sysVSharedMemory.get(size);
                lock = outputStream.lock();
                try {
                    outputStream.writeInt(shmid);
                    if (lock != null) {
                        lock.close();
                        return true;
                    }
                    return true;
                } finally {
                }
            case 1:
                int shmid2 = inputStream.readInt();
                lock = outputStream.lock();
                try {
                    outputStream.writeByte((byte) 0);
                    outputStream.setAncillaryFd(sysVSharedMemory.getFd(shmid2));
                    if (lock != null) {
                        lock.close();
                        return true;
                    }
                    return true;
                } finally {
                }
            case 2:
                int shmid3 = inputStream.readInt();
                sysVSharedMemory.delete(shmid3);
                return true;
            default:
                return true;
        }
    }
}
