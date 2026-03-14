package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Window;
import java.io.IOException;

/* loaded from: classes6.dex */
public abstract class PointerWindowEvent extends Event {
    private final Window child;
    private final Detail detail;
    private final Window event;
    private final short eventX;
    private final short eventY;
    private final Mode mode;
    private final Window root;
    private final short rootX;
    private final short rootY;
    private final boolean sameScreenAndFocus;
    private final Bitmask state;
    private final int timestamp;

    public enum Detail {
        ANCESTOR,
        VIRTUAL,
        INFERIOR,
        NONLINEAR,
        NONLINEAR_VIRTUAL
    }

    public enum Mode {
        NORMAL,
        GRAB,
        UNGRAB
    }

    public PointerWindowEvent(int code, Detail detail, Window root, Window event, Window child, short rootX, short rootY, short eventX, short eventY, Bitmask state, Mode mode, boolean sameScreenAndFocus) {
        super(code);
        this.detail = detail;
        this.timestamp = (int) System.currentTimeMillis();
        this.root = root;
        this.event = event;
        this.child = child;
        this.rootX = rootX;
        this.rootY = rootY;
        this.eventX = eventX;
        this.eventY = eventY;
        this.state = state;
        this.mode = mode;
        this.sameScreenAndFocus = sameScreenAndFocus;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte((byte) this.detail.ordinal());
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(this.timestamp);
            outputStream.writeInt(this.root.id);
            outputStream.writeInt(this.event.id);
            outputStream.writeInt(this.child != null ? this.child.id : 0);
            outputStream.writeShort(this.rootX);
            outputStream.writeShort(this.rootY);
            outputStream.writeShort(this.eventX);
            outputStream.writeShort(this.eventY);
            outputStream.writeShort((short) this.state.getBits());
            outputStream.writeByte((byte) this.mode.ordinal());
            outputStream.writeByte((byte) (this.sameScreenAndFocus ? 1 : 0));
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
