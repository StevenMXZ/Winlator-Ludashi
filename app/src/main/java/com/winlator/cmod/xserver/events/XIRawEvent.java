package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.extensions.XInputExtension;

import java.io.IOException;

/* XI_RawMotion / XI_RawButtonPress / XI_RawButtonRelease sent as GenericEvent:
 * xXIRawEvent (32 bytes) + valuator mask + FP3232 values (normal, then raw). */
public class XIRawEvent extends Event {
    private static final byte GENERIC_EVENT = 35;
    private final short evtype;
    private final int timestamp;
    private final int detail;
    private final boolean hasMotion;
    private final double dx;
    private final double dy;

    private XIRawEvent(short evtype, int timestamp, int detail, boolean hasMotion, double dx, double dy) {
        super(GENERIC_EVENT);
        this.evtype = evtype;
        this.timestamp = timestamp;
        this.detail = detail;
        this.hasMotion = hasMotion;
        this.dx = dx;
        this.dy = dy;
    }

    public static XIRawEvent motion(int timestamp, double dx, double dy) {
        return new XIRawEvent(XInputExtension.XI_RAW_MOTION, timestamp, 0, true, dx, dy);
    }

    public static XIRawEvent button(int timestamp, int button, boolean pressed) {
        return new XIRawEvent(pressed ? XInputExtension.XI_RAW_BUTTON_PRESS : XInputExtension.XI_RAW_BUTTON_RELEASE, timestamp, button, false, 0, 0);
    }

    public short getEvtype() {
        return evtype;
    }

    @Override
    public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
        short valuatorsLen = (short)(hasMotion ? 1 : 0);
        int length = hasMotion ? valuatorsLen + 2 * 2 * 2 : 0;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(code);
            outputStream.writeByte(XInputExtension.MAJOR_OPCODE);
            outputStream.writeShort(sequenceNumber);
            outputStream.writeInt(length);
            outputStream.writeShort(evtype);
            outputStream.writeShort(XInputExtension.DEVICE_MASTER_POINTER);
            outputStream.writeInt(timestamp);
            outputStream.writeInt(detail);
            outputStream.writeShort(XInputExtension.DEVICE_SLAVE_POINTER);
            outputStream.writeShort(valuatorsLen);
            outputStream.writeInt(0);
            outputStream.writePad(4);

            if (hasMotion) {
                outputStream.writeInt(0x3);
                outputStream.writeFP3232(dx);
                outputStream.writeFP3232(dy);
                outputStream.writeFP3232(dx);
                outputStream.writeFP3232(dy);
            }
        }
    }
}
