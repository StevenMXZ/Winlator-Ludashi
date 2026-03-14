package com.winlator.cmod.xserver.extensions;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;
import java.io.IOException;

/* loaded from: classes11.dex */
public class BigReqExtension implements Extension {
    public static final byte MAJOR_OPCODE = -100;
    private static final int MAX_REQUEST_LENGTH = 4194303;

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public String getName() {
        return "BIG-REQUESTS";
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public byte getFirstErrorId() {
        return (byte) 0;
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public byte getFirstEventId() {
        return (byte) 0;
    }

    @Override // com.winlator.cmod.xserver.extensions.Extension
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte((byte) 1);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAX_REQUEST_LENGTH);
            outputStream.writePad(20);
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
