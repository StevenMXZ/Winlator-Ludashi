package com.winlator.cmod.winhandler;

import com.winlator.cmod.xserver.Pointer;

/* loaded from: classes12.dex */
public abstract class MouseEventFlags {
    public static final int ABSOLUTE = 32768;
    public static final int LEFTDOWN = 2;
    public static final int LEFTUP = 4;
    public static final int MIDDLEDOWN = 32;
    public static final int MIDDLEUP = 64;
    public static final int MOVE = 1;
    public static final int RIGHTDOWN = 8;
    public static final int RIGHTUP = 16;
    public static final int VIRTUALDESK = 16384;
    public static final int WHEEL = 2048;
    public static final int XDOWN = 128;
    public static final int XUP = 256;

    public static int getFlagFor(Pointer.Button button, boolean isActionDown) {
        switch (button) {
            case BUTTON_LEFT:
                return isActionDown ? 2 : 4;
            case BUTTON_MIDDLE:
                return isActionDown ? 32 : 64;
            case BUTTON_RIGHT:
                return isActionDown ? 8 : 16;
            case BUTTON_SCROLL_DOWN:
            case BUTTON_SCROLL_UP:
                return 2048;
            default:
                return 0;
        }
    }
}
