package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;
import java.io.IOException;

/* loaded from: classes6.dex */
public class Expose extends Event {
    private final short height;
    private final short width;
    private final Window window;
    private final short x;
    private final short y;

    public Expose(Window window) {
        super(12);
        this.window = window;
        this.y = (short) 0;
        this.x = (short) 0;
        this.width = window.getWidth();
        this.height = window.getHeight();
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(this.window.id);
            outputStream.writeShort(this.x);
            outputStream.writeShort(this.y);
            outputStream.writeShort(this.width);
            outputStream.writeShort(this.height);
            outputStream.writeShort((short) 0);
            outputStream.writePad(14);
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
