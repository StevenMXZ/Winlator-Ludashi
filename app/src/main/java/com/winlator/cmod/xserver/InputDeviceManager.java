package com.winlator.cmod.xserver;

import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.events.ButtonPress;
import com.winlator.cmod.xserver.events.ButtonRelease;
import com.winlator.cmod.xserver.events.EnterNotify;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.KeyPress;
import com.winlator.cmod.xserver.events.KeyRelease;
import com.winlator.cmod.xserver.events.LeaveNotify;
import com.winlator.cmod.xserver.events.MappingNotify;
import com.winlator.cmod.xserver.events.MotionNotify;
import com.winlator.cmod.xserver.events.PointerWindowEvent;

/* loaded from: classes11.dex */
public class InputDeviceManager implements Pointer.OnPointerMotionListener, Keyboard.OnKeyboardListener, WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private Window pointWindow;
    private final XServer xServer;

    public InputDeviceManager(XServer xServer) {
        this.xServer = xServer;
        this.pointWindow = xServer.windowManager.rootWindow;
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.keyboard.addOnKeyboardListener(this);
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onMapWindow(Window window) {
        updatePointWindow();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onUnmapWindow(Window window) {
        updatePointWindow();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onChangeWindowZOrder(Window window) {
        updatePointWindow();
    }

    @Override // com.winlator.cmod.xserver.WindowManager.OnWindowModificationListener
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        updatePointWindow();
    }

    @Override // com.winlator.cmod.xserver.XResourceManager.OnResourceLifecycleListener
    public void onCreateResource(XResource resource) {
        updatePointWindow();
    }

    @Override // com.winlator.cmod.xserver.XResourceManager.OnResourceLifecycleListener
    public void onFreeResource(XResource resource) {
        updatePointWindow();
    }

    private void updatePointWindow() {
        Window pointWindow = this.xServer.windowManager.findPointWindow(this.xServer.pointer.getClampedX(), this.xServer.pointer.getClampedY());
        this.pointWindow = pointWindow != null ? pointWindow : this.xServer.windowManager.rootWindow;
    }

    public Window getPointWindow() {
        return this.pointWindow;
    }

    private void sendEvent(Window window, int eventId, Event event) {
        Window grabWindow = this.xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = this.xServer.grabManager.getEventListener();
            if (this.xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventId, event, this.xServer.grabManager.getClient());
                return;
            } else {
                if (eventListener.isInterestedIn(eventId)) {
                    eventListener.sendEvent(event);
                    return;
                }
                return;
            }
        }
        if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventId, event);
        }
    }

    private void sendEvent(Window window, Bitmask eventMask, Event event) {
        Window grabWindow = this.xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = this.xServer.grabManager.getEventListener();
            if (this.xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventMask, event, eventListener.client);
                return;
            } else {
                if (eventListener.isInterestedIn(eventMask)) {
                    eventListener.sendEvent(event);
                    return;
                }
                return;
            }
        }
        if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventMask, event);
        }
    }

    public void sendEnterLeaveNotify(Window windowA, Window windowB, PointerWindowEvent.Mode mode) {
        PointerWindowEvent.Detail detailA;
        PointerWindowEvent.Detail detailB;
        if (windowA == windowB) {
            return;
        }
        short x = this.xServer.pointer.getX();
        short y = this.xServer.pointer.getY();
        short[] localPointA = windowA.rootPointToLocal(x, y);
        short[] localPointB = windowB.rootPointToLocal(x, y);
        boolean sameScreenAndFocus = windowB.isAncestorOf(this.xServer.windowManager.getFocusedWindow());
        PointerWindowEvent.Detail detailA2 = PointerWindowEvent.Detail.NONLINEAR;
        PointerWindowEvent.Detail detailB2 = PointerWindowEvent.Detail.NONLINEAR;
        if (windowA.isAncestorOf(windowB)) {
            PointerWindowEvent.Detail detailA3 = PointerWindowEvent.Detail.ANCESTOR;
            PointerWindowEvent.Detail detailB3 = PointerWindowEvent.Detail.INFERIOR;
            detailA = detailA3;
            detailB = detailB3;
        } else if (!windowB.isAncestorOf(windowA)) {
            detailA = detailA2;
            detailB = detailB2;
        } else {
            PointerWindowEvent.Detail detailB4 = PointerWindowEvent.Detail.ANCESTOR;
            PointerWindowEvent.Detail detailA4 = PointerWindowEvent.Detail.INFERIOR;
            detailA = detailA4;
            detailB = detailB4;
        }
        Bitmask keyButMask = getKeyButMask();
        sendEvent(windowA, 32, new LeaveNotify(detailA, this.xServer.windowManager.rootWindow, windowA, null, x, y, localPointA[0], localPointA[1], keyButMask, mode, sameScreenAndFocus));
        sendEvent(windowB, 16, new EnterNotify(detailB, this.xServer.windowManager.rootWindow, windowB, null, x, y, localPointB[0], localPointB[1], keyButMask, mode, sameScreenAndFocus));
    }

    @Override // com.winlator.cmod.xserver.Pointer.OnPointerMotionListener
    public void onPointerButtonPress(Pointer.Button button) {
        if (this.xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = this.xServer.getWinHandler();
            int wheelDelta = button == Pointer.Button.BUTTON_SCROLL_UP ? 120 : button == Pointer.Button.BUTTON_SCROLL_DOWN ? -120 : 0;
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, true), 0, 0, wheelDelta);
            return;
        }
        Window grabWindow = this.xServer.grabManager.getWindow();
        if (grabWindow == null && (grabWindow = this.pointWindow.getAncestorWithEventId(4)) != null) {
            this.xServer.grabManager.activatePointerGrab(grabWindow);
        }
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            Bitmask eventMask = createPointerEventMask();
            eventMask.unset(button.flag());
            short x = this.xServer.pointer.getX();
            short y = this.xServer.pointer.getY();
            short[] localPoint = grabWindow.rootPointToLocal(x, y);
            Window child = grabWindow.isAncestorOf(this.pointWindow) ? this.pointWindow : null;
            grabWindow.sendEvent(4, new ButtonPress(button.code(), this.xServer.windowManager.rootWindow, grabWindow, child, x, y, localPoint[0], localPoint[1], eventMask));
        }
    }

    @Override // com.winlator.cmod.xserver.Pointer.OnPointerMotionListener
    public void onPointerButtonRelease(Pointer.Button button) {
        Window ancestorWithEventMask;
        if (this.xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = this.xServer.getWinHandler();
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, false), 0, 0, 0);
            return;
        }
        Bitmask eventMask = createPointerEventMask();
        Window grabWindow = this.xServer.grabManager.getWindow();
        Window window = null;
        if (grabWindow == null || this.xServer.grabManager.isOwnerEvents()) {
            ancestorWithEventMask = this.pointWindow.getAncestorWithEventMask(eventMask);
        } else {
            ancestorWithEventMask = null;
        }
        Window window2 = ancestorWithEventMask;
        if (grabWindow != null || window2 != null) {
            Window eventWindow = window2 != null ? window2 : grabWindow;
            short x = this.xServer.pointer.getX();
            short y = this.xServer.pointer.getY();
            short[] localPoint = eventWindow.rootPointToLocal(x, y);
            if (eventWindow.isAncestorOf(this.pointWindow)) {
                window = this.pointWindow;
            }
            Window child = window;
            ButtonRelease buttonRelease = new ButtonRelease(button.code(), this.xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], eventMask);
            sendEvent(window2, eventMask, buttonRelease);
        }
        if (this.xServer.pointer.getButtonMask().isEmpty() && this.xServer.grabManager.isReleaseWithButtons()) {
            this.xServer.grabManager.deactivatePointerGrab();
        }
    }

    @Override // com.winlator.cmod.xserver.Pointer.OnPointerMotionListener
    public void onPointerMove(short x, short y) {
        Window window;
        updatePointWindow();
        Bitmask eventMask = createPointerEventMask();
        Window grabWindow = this.xServer.grabManager.getWindow();
        Window window2 = null;
        if (grabWindow == null || this.xServer.grabManager.isOwnerEvents()) {
            window = this.pointWindow.getAncestorWithEventMask(eventMask);
        } else {
            window = null;
        }
        if (grabWindow != null || window != null) {
            Window eventWindow = window != null ? window : grabWindow;
            short[] localPoint = eventWindow.rootPointToLocal(x, y);
            if (eventWindow.isAncestorOf(this.pointWindow)) {
                window2 = this.pointWindow;
            }
            Window child = window2;
            sendEvent(window, eventMask, new MotionNotify(false, this.xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], getKeyButMask()));
        }
    }

    @Override // com.winlator.cmod.xserver.Keyboard.OnKeyboardListener
    public void onKeyPress(byte keycode, int keysym) {
        Window child;
        Window eventWindow;
        Window focusedWindow = this.xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) {
            return;
        }
        updatePointWindow();
        Window eventWindow2 = null;
        if (!focusedWindow.isAncestorOf(this.pointWindow)) {
            child = null;
        } else {
            eventWindow2 = this.pointWindow.getAncestorWithEventId(1, focusedWindow);
            Window child2 = eventWindow2.isAncestorOf(this.pointWindow) ? this.pointWindow : null;
            child = child2;
        }
        if (eventWindow2 != null) {
            eventWindow = eventWindow2;
        } else if (!focusedWindow.hasEventListenerFor(1)) {
            return;
        } else {
            eventWindow = focusedWindow;
        }
        if (eventWindow.attributes.isEnabled()) {
            Bitmask keyButMask = getKeyButMask();
            short x = this.xServer.pointer.getX();
            short y = this.xServer.pointer.getY();
            short[] localPoint = eventWindow.rootPointToLocal(x, y);
            if (keysym != 0 && !this.xServer.keyboard.hasKeysym(keycode, keysym)) {
                this.xServer.keyboard.setKeysyms(keycode, keysym, keysym);
                eventWindow.sendEvent(new MappingNotify(MappingNotify.Request.KEYBOARD, keycode, 1));
            }
            eventWindow.sendEvent(1, new KeyPress(keycode, this.xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
        }
    }

    @Override // com.winlator.cmod.xserver.Keyboard.OnKeyboardListener
    public void onKeyRelease(byte keycode) {
        Window focusedWindow = this.xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) {
            return;
        }
        updatePointWindow();
        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(this.pointWindow)) {
            eventWindow = this.pointWindow.getAncestorWithEventId(2, focusedWindow);
            child = eventWindow.isAncestorOf(this.pointWindow) ? this.pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(2)) {
                return;
            } else {
                eventWindow = focusedWindow;
            }
        }
        if (eventWindow.attributes.isEnabled()) {
            Bitmask keyButMask = getKeyButMask();
            short x = this.xServer.pointer.getX();
            short y = this.xServer.pointer.getY();
            short[] localPoint = eventWindow.rootPointToLocal(x, y);
            eventWindow.sendEvent(2, new KeyRelease(keycode, this.xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
        }
    }

    private Bitmask createPointerEventMask() {
        Bitmask eventMask = new Bitmask();
        eventMask.set(64);
        Bitmask buttonMask = this.xServer.pointer.getButtonMask();
        if (!buttonMask.isEmpty()) {
            eventMask.set(8192);
            if (buttonMask.isSet(Pointer.Button.BUTTON_LEFT.flag())) {
                eventMask.set(256);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_MIDDLE.flag())) {
                eventMask.set(512);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_RIGHT.flag())) {
                eventMask.set(1024);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_UP.flag())) {
                eventMask.set(2048);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_DOWN.flag())) {
                eventMask.set(4096);
            }
        }
        return eventMask;
    }

    public Bitmask getKeyButMask() {
        Bitmask keyButMask = new Bitmask();
        keyButMask.join(this.xServer.pointer.getButtonMask());
        keyButMask.join(this.xServer.keyboard.getModifiersMask());
        return keyButMask;
    }
}
