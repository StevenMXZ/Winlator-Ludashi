package com.winlator.cmod.xserver.events;

import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.events.PointerWindowEvent;

/* loaded from: classes6.dex */
public class EnterNotify extends PointerWindowEvent {
    public EnterNotify(PointerWindowEvent.Detail detail, Window root, Window event, Window child, short rootX, short rootY, short eventX, short eventY, Bitmask state, PointerWindowEvent.Mode mode, boolean sameScreenAndFocus) {
        super(7, detail, root, event, child, rootX, rootY, eventX, eventY, state, mode, sameScreenAndFocus);
    }
}
