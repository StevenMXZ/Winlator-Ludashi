package com.winlator.cmod.inputcontrols;

import java.nio.ByteBuffer;

/* loaded from: classes14.dex */
public class GamepadState {
    public float thumbLX = 0.0f;
    public float thumbLY = 0.0f;
    public float thumbRX = 0.0f;
    public float thumbRY = 0.0f;
    public float triggerL = 0.0f;
    public float triggerR = 0.0f;
    public final boolean[] dpad = new boolean[4];
    public short buttons = 0;

    public byte getPovHat() {
        if (this.dpad[0] && this.dpad[1]) {
            return (byte) 1;
        }
        if (this.dpad[1] && this.dpad[2]) {
            return (byte) 3;
        }
        if (this.dpad[2] && this.dpad[3]) {
            return (byte) 5;
        }
        if (this.dpad[3] && this.dpad[0]) {
            return (byte) 7;
        }
        if (this.dpad[0]) {
            return (byte) 0;
        }
        if (this.dpad[1]) {
            return (byte) 2;
        }
        if (this.dpad[2]) {
            return (byte) 4;
        }
        return this.dpad[3] ? (byte) 6 : (byte) -1;
    }

    public void writeTo(ByteBuffer buffer) {
        buffer.putShort(this.buttons);
        buffer.put(getPovHat());
        buffer.putShort((short) (this.thumbLX * 32767.0f));
        buffer.putShort((short) (this.thumbLY * 32767.0f));
        buffer.putShort((short) (this.thumbRX * 32767.0f));
        buffer.putShort((short) (this.thumbRY * 32767.0f));
        buffer.put((byte) (this.triggerL * 255.0f));
        buffer.put((byte) (this.triggerR * 255.0f));
    }

    public void setPressed(int buttonIdx, boolean pressed) {
        int flag = 1 << buttonIdx;
        if (pressed) {
            this.buttons = (short) (this.buttons | flag);
        } else {
            this.buttons = (short) (this.buttons & (~flag));
        }
    }

    public boolean isPressed(int buttonIdx) {
        return (this.buttons & (1 << buttonIdx)) != 0;
    }

    public byte getDPadX() {
        return (byte) (this.dpad[1] ? 1 : this.dpad[3] ? -1 : 0);
    }

    public byte getDPadY() {
        int i = 0;
        if (this.dpad[0]) {
            i = -1;
        } else if (this.dpad[2]) {
            i = 1;
        }
        return (byte) i;
    }

    public void copy(GamepadState other) {
        this.thumbLX = other.thumbLX;
        this.thumbLY = other.thumbLY;
        this.thumbRX = other.thumbRX;
        this.thumbRY = other.thumbRY;
        this.triggerL = other.triggerL;
        this.triggerR = other.triggerR;
        this.buttons = other.buttons;
        System.arraycopy(other.dpad, 0, this.dpad, 0, 4);
    }
}
