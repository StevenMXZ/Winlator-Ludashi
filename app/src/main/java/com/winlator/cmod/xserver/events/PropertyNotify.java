package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;
import java.io.IOException;

/* loaded from: classes6.dex */
public class PropertyNotify extends Event {
    private final int atom;
    private final boolean deleted;
    private final int timestamp;
    private final Window window;

    public PropertyNotify(Window window, int atom, boolean deleted) {
        super(28);
        this.window = window;
        this.atom = atom;
        this.timestamp = (int) System.currentTimeMillis();
        this.deleted = deleted;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(this.window.id);
            outputStream.writeInt(this.atom);
            outputStream.writeInt(this.timestamp);
            outputStream.writeByte((byte) (this.deleted ? 1 : 0));
            outputStream.writePad(15);
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
