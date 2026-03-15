package com.winlator.cmod.xserver;

import com.winlator.cmod.math.Mathf;
import java.util.ArrayList;

/* loaded from: classes11.dex */
public class Pointer {
    public static final byte MAX_BUTTONS = 7;
    private short x;
    private final XServer xServer;
    private short y;
    private final ArrayList<OnPointerMotionListener> onPointerMotionListeners = new ArrayList<>();
    private final Bitmask buttonMask = new Bitmask();

    public enum Button {
        BUTTON_LEFT,
        BUTTON_MIDDLE,
        BUTTON_RIGHT,
        BUTTON_SCROLL_UP,
        BUTTON_SCROLL_DOWN,
        BUTTON_SCROLL_CLICK_LEFT,
        BUTTON_SCROLL_CLICK_RIGHT;

        public byte code() {
            return (byte) (ordinal() + 1);
        }

        public int flag() {
            return 1 << (code() + 7);
        }
    }

    public interface OnPointerMotionListener {
        default void onPointerButtonPress(Button button) {
        }

        default void onPointerButtonRelease(Button button) {
        }

        default void onPointerMove(short x, short y) {
        }
    }

    public Pointer(XServer xServer) {
        this.xServer = xServer;
    }

    public void setX(int x) {
        this.x = (short) x;
    }

    public void setY(int y) {
        this.y = (short) y;
    }

    public short getX() {
        return this.x;
    }

    public short getY() {
        return this.y;
    }

    public short getClampedX() {
        return (short) Mathf.clamp((int) this.x, 0, this.xServer.screenInfo.width - 1);
    }

    public short getClampedY() {
        return (short) Mathf.clamp((int) this.y, 0, this.xServer.screenInfo.height - 1);
    }

    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
        triggerOnPointerMove(this.x, this.y);
    }

    public Bitmask getButtonMask() {
        return this.buttonMask;
    }

    public void setButton(Button button, boolean pressed) {
        boolean oldPressed = isButtonPressed(button);
        this.buttonMask.set(button.flag(), pressed);
        if (oldPressed != pressed) {
            if (pressed) {
                triggerOnPointerButtonPress(button);
            } else {
                triggerOnPointerButtonRelease(button);
            }
        }
    }

    public boolean isButtonPressed(Button button) {
        return this.buttonMask.isSet(button.flag());
    }

    public void addOnPointerMotionListener(OnPointerMotionListener onPointerMotionListener) {
        this.onPointerMotionListeners.add(onPointerMotionListener);
    }

    public void removeOnPointerMotionListener(OnPointerMotionListener onPointerMotionListener) {
        this.onPointerMotionListeners.remove(onPointerMotionListener);
    }

    private void triggerOnPointerButtonPress(Button button) {
        for (int i = this.onPointerMotionListeners.size() - 1; i >= 0; i--) {
            this.onPointerMotionListeners.get(i).onPointerButtonPress(button);
        }
    }

    private void triggerOnPointerButtonRelease(Button button) {
        for (int i = this.onPointerMotionListeners.size() - 1; i >= 0; i--) {
            this.onPointerMotionListeners.get(i).onPointerButtonRelease(button);
        }
    }

    private void triggerOnPointerMove(short x, short y) {
        for (int i = this.onPointerMotionListeners.size() - 1; i >= 0; i--) {
            this.onPointerMotionListeners.get(i).onPointerMove(x, y);
        }
    }
}
