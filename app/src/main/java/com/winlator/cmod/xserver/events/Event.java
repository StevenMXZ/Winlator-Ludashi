package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xconnector.XOutputStream;
import java.io.IOException;

/* loaded from: classes6.dex */
public abstract class Event {
    public static final int BUTTON1_MOTION = 256;
    public static final int BUTTON2_MOTION = 512;
    public static final int BUTTON3_MOTION = 1024;
    public static final int BUTTON4_MOTION = 2048;
    public static final int BUTTON5_MOTION = 4096;
    public static final int BUTTON_MOTION = 8192;
    public static final int BUTTON_PRESS = 4;
    public static final int BUTTON_RELEASE = 8;
    public static final int COLORMAP_CHANGE = 8388608;
    public static final int ENTER_WINDOW = 16;
    public static final int EXPOSURE = 32768;
    public static final int FOCUS_CHANGE = 2097152;
    public static final int KEYMAP_STATE = 16384;
    public static final int KEY_PRESS = 1;
    public static final int KEY_RELEASE = 2;
    public static final int LEAVE_WINDOW = 32;
    public static final int OWNER_GRAB_BUTTON = 16777216;
    public static final int POINTER_MOTION = 64;
    public static final int POINTER_MOTION_HINT = 128;
    public static final int PROPERTY_CHANGE = 4194304;
    public static final int RESIZE_REDIRECT = 262144;
    public static final int STRUCTURE_NOTIFY = 131072;
    public static final int SUBSTRUCTURE_NOTIFY = 524288;
    public static final int SUBSTRUCTURE_REDIRECT = 1048576;
    public static final int VISIBILITY_CHANGE = 65536;
    protected final byte code;

    public abstract void send(short s, XOutputStream xOutputStream) throws IOException;

    public Event(int code) {
        this.code = (byte) code;
    }
}
