package com.winlator.cmod.widget;

import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.XServer;

/** UI input state only. No X11 connector is started for this object. */
public final class NativeX11InputProxy extends XServer {
    public NativeX11InputProxy(ScreenInfo info, KeyValueSet config) {
        super(info, "egl", config);
    }

    @Override public void setRelativeMouseMovement(boolean enabled) {
        super.setRelativeMouseMovement(enabled);
        cursorLocker.setEnabled(false);
        if (getXServerView() instanceof NativeX11View)
            ((NativeX11View) getXServerView()).setRawInputEnabled(!enabled);
    }

    @Override public void injectPointerWarp(int x, int y) {
        if (isMouseDisabled()) return;
        pointer.setX(x);
        pointer.setY(y);
        if (getXServerView() instanceof NativeX11View)
            ((NativeX11View) getXServerView()).warpPointer(x, y);
    }

    @Override public void injectPointerMoveDelta(int dx, int dy) {
        if (isMouseDisabled()) return;
        // Preserve the UI's local coordinates without deriving a new delta from them:
        // Wine may have independently warped the native pointer in the meantime.
        pointer.setX(pointer.getX() + dx);
        pointer.setY(pointer.getY() + dy);
        if (getXServerView() instanceof NativeX11View)
            ((NativeX11View) getXServerView()).moveRelative(dx, dy);
    }
}
