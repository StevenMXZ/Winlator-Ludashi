package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Window;
import java.io.IOException;

/* loaded from: classes6.dex */
public class ConfigureRequest extends Event {
    private final short borderWidth;
    private final short height;
    private final Window parent;
    private final Window sibling;
    private final Window.StackMode stackMode;
    private final Bitmask valueMask;
    private final short width;
    private final Window window;
    private final short x;
    private final short y;

    public ConfigureRequest(Window parent, Window window, Window sibling, short x, short y, short width, short height, short borderWidth, Window.StackMode stackMode, Bitmask valueMask) {
        super(23);
        this.parent = parent;
        this.window = window;
        this.sibling = sibling;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.borderWidth = borderWidth;
        this.stackMode = stackMode != null ? stackMode : Window.StackMode.ABOVE;
        this.valueMask = valueMask;
    }

    @Override // com.winlator.cmod.xserver.events.Event
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        XStreamLock lock = outputStream.lock();
        try {
            outputStream.writeByte(this.code);
            outputStream.writeByte((byte) this.stackMode.ordinal());
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(this.parent.id);
            outputStream.writeInt(this.window.id);
            outputStream.writeInt(this.sibling != null ? this.sibling.id : 0);
            outputStream.writeShort(this.x);
            outputStream.writeShort(this.y);
            outputStream.writeShort(this.width);
            outputStream.writeShort(this.height);
            outputStream.writeShort(this.borderWidth);
            outputStream.writeShort((short) this.valueMask.getBits());
            outputStream.writePad(4);
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
