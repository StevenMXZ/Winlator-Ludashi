package com.winlator.cmod.xserver;

import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.events.PointerWindowEvent;

/* loaded from: classes11.dex */
public class GrabManager implements WindowManager.OnWindowModificationListener {
    private EventListener eventListener;
    private boolean ownerEvents;
    private boolean releaseWithButtons;
    private Window window;
    private final XServer xServer;

    public GrabManager(XServer xServer) {
        this.xServer = xServer;
        xServer.windowManager.addOnWindowModificationListener(this);
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onUnmapWindow(Window window) {
        if (window != null && window.getMapState() != Window.MapState.VIEWABLE) {
            deactivatePointerGrab();
        }
    }

    public Window getWindow() {
        return this.window;
    }

    public boolean isOwnerEvents() {
        return this.ownerEvents;
    }

    public boolean isReleaseWithButtons() {
        return this.releaseWithButtons;
    }

    public EventListener getEventListener() {
        return this.eventListener;
    }

    public XClient getClient() {
        if (this.eventListener != null) {
            return this.eventListener.client;
        }
        return null;
    }

    public void deactivatePointerGrab() {
        if (this.window != null) {
            this.xServer.inputDeviceManager.sendEnterLeaveNotify(this.window, this.xServer.inputDeviceManager.getPointWindow(), PointerWindowEvent.Mode.UNGRAB);
            this.window = null;
            this.eventListener = null;
        }
    }

    private void activatePointerGrab(Window window, EventListener eventListener, boolean ownerEvents, boolean releaseWithButtons) {
        if (this.window == null) {
            this.xServer.inputDeviceManager.sendEnterLeaveNotify(this.xServer.inputDeviceManager.getPointWindow(), window, PointerWindowEvent.Mode.GRAB);
        }
        this.window = window;
        this.releaseWithButtons = releaseWithButtons;
        this.ownerEvents = ownerEvents;
        this.eventListener = eventListener;
    }

    public void activatePointerGrab(Window window, boolean ownerEvents, Bitmask eventMask, XClient client) {
        activatePointerGrab(window, new EventListener(client, eventMask), ownerEvents, false);
    }

    public void activatePointerGrab(Window window) {
        EventListener eventListener = window.getButtonPressListener();
        activatePointerGrab(window, eventListener, eventListener.isInterestedIn(16777216), true);
    }
}
