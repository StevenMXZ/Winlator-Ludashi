package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import java.io.IOException;

/* loaded from: classes6.dex */
public class MappingNotify extends Event {
    private final byte count;
    private final byte firstKeycode;
    private final Request request;

    public enum Request {
        MODIFIER,
        KEYBOARD,
        POINTER
    }

    public MappingNotify(Request request, byte firstKeycode, int count) {
        super(34);
        this.request = request;
        this.firstKeycode = firstKeycode;
        this.count = (byte) count;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeByte((byte) this.request.ordinal());
            outputStream.writeByte(this.firstKeycode);
            outputStream.writeByte(this.count);
            outputStream.writePad(25);
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
