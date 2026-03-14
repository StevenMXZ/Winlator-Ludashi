package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.extensions.PresentExtension;
import java.io.IOException;

/* loaded from: classes6.dex */
public class PresentIdleNotify extends Event {
    private final int eventId;
    private final int idleFence;
    private final Pixmap pixmap;
    private final int serial;
    private final Window window;

    public PresentIdleNotify(int eventId, Window window, Pixmap pixmap, int serial, int idleFence) {
        super(35);
        this.eventId = eventId;
        this.window = window;
        this.serial = serial;
        this.pixmap = pixmap;
        this.idleFence = idleFence;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte(PresentExtension.MAJOR_OPCODE);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(0);
            outputStream.writeShort(getEventType());
            outputStream.writeShort((short) 0);
            outputStream.writeInt(this.eventId);
            outputStream.writeInt(this.window.id);
            outputStream.writeInt(this.serial);
            outputStream.writeInt(this.pixmap.id);
            outputStream.writeInt(this.idleFence);
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

    public static short getEventType() {
        return (short) 2;
    }

    public static int getEventMask() {
        return 1 << getEventType();
    }
}
