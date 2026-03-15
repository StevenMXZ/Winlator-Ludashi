package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.extensions.PresentExtension;
import java.io.IOException;

/* loaded from: classes6.dex */
public class PresentCompleteNotify extends Event {
    private final int eventId;
    private final PresentExtension.Kind kind;
    private final PresentExtension.Mode mode;
    private final long msc;
    private final int serial;
    private final long ust;
    private final Window window;

    public PresentCompleteNotify(int eventId, Window window, int serial, PresentExtension.Kind kind, PresentExtension.Mode mode, long ust, long msc) {
        super(35);
        this.eventId = eventId;
        this.window = window;
        this.serial = serial;
        this.kind = kind;
        this.mode = mode;
        this.ust = ust;
        this.msc = msc;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte(PresentExtension.MAJOR_OPCODE);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(2);
            outputStream.writeShort(getEventType());
            outputStream.writeByte((byte) this.kind.ordinal());
            outputStream.writeByte((byte) this.mode.ordinal());
            outputStream.writeInt(this.eventId);
            outputStream.writeInt(this.window.id);
            outputStream.writeInt(this.serial);
            outputStream.writeLong(this.ust);
            outputStream.writeLong(this.msc);
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
        return (short) 1;
    }

    public static int getEventMask() {
        return 1 << getEventType();
    }
}
