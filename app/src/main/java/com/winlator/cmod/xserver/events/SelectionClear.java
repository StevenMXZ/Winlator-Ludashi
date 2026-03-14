package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;
import java.io.IOException;

/* loaded from: classes6.dex */
public class SelectionClear extends Event {
    private final Window owner;
    private final int selection;
    private final int timestamp;

    public SelectionClear(int timestamp, Window owner, int selection) {
        super(29);
        this.timestamp = timestamp;
        this.owner = owner;
        this.selection = selection;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(this.timestamp);
            outputStream.writeInt(this.owner.id);
            outputStream.writeInt(this.selection);
            outputStream.writePad(16);
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
