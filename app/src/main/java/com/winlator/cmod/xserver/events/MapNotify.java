package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;
import java.io.IOException;

/* loaded from: classes6.dex */
public class MapNotify extends Event {
    private final Window event;
    private final Window window;

    public MapNotify(Window event, Window window) {
        super(19);
        this.event = event;
        this.window = window;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(this.event.id);
            outputStream.writeInt(this.window.id);
            outputStream.writeByte((byte) (this.window.attributes.isOverrideRedirect() ? 1 : 0));
            outputStream.writePad(19);
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
