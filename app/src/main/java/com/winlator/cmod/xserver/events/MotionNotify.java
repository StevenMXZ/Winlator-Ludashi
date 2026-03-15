package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Window;

/* loaded from: classes6.dex */
public class MotionNotify extends InputDeviceEvent {
    public MotionNotify(boolean z, Window window, Window window2, Window window3, short s, short s2, short s3, short s4, Bitmask bitmask) {
        super(6, z ? (byte) 1 : (byte) 0, window, window2, window3, s, s2, s3, s4, bitmask);
    }
}
