package com.winlator.cmod.xserver;

import com.winlator.cmod.xconnector.XInputStream;
import java.util.Iterator;

/* loaded from: classes11.dex */
public class WindowAttributes {
    public static final int FLAG_BACKGROUND_PIXEL = 2;
    public static final int FLAG_BACKGROUND_PIXMAP = 1;
    public static final int FLAG_BACKING_PIXEL = 256;
    public static final int FLAG_BACKING_PLANES = 128;
    public static final int FLAG_BACKING_STORE = 64;
    public static final int FLAG_BIT_GRAVITY = 16;
    public static final int FLAG_BORDER_PIXEL = 8;
    public static final int FLAG_BORDER_PIXMAP = 4;
    public static final int FLAG_COLORMAP = 8192;
    public static final int FLAG_CURSOR = 16384;
    public static final int FLAG_DO_NOT_PROPAGATE_MASK = 4096;
    public static final int FLAG_EVENT_MASK = 2048;
    public static final int FLAG_OVERRIDE_REDIRECT = 512;
    public static final int FLAG_SAVE_UNDER = 1024;
    public static final int FLAG_WIN_GRAVITY = 32;
    private Cursor cursor;
    public final Window window;
    private int backingPixel = 0;
    private int backingPlanes = 1;
    private BackingStore backingStore = BackingStore.NOT_USEFUL;
    private BitGravity bitGravity = BitGravity.CENTER;
    private Bitmask doNotPropagateMask = new Bitmask(0);
    private Bitmask eventMask = new Bitmask(0);
    private boolean mapped = false;
    private boolean overrideRedirect = false;
    private boolean saveUnder = false;
    private boolean enabled = true;
    private WinGravity winGravity = WinGravity.CENTER;
    private WindowClass windowClass = WindowClass.INPUT_OUTPUT;

    public enum BackingStore {
        NOT_USEFUL,
        WHEN_MAPPED,
        ALWAYS
    }

    public enum BitGravity {
        FORGET,
        NORTH_WEST,
        NORTH,
        NORTH_EAST,
        WEST,
        CENTER,
        EAST,
        SOUTH_WEST,
        SOUTH,
        SOUTH_EAST,
        STATIC
    }

    public enum WinGravity {
        UNMAP,
        NORTH_WEST,
        NORTH,
        NORTH_EAST,
        WEST,
        CENTER,
        EAST,
        SOUTH_WEST,
        SOUTH,
        SOUTH_EAST,
        STATIC
    }

    public enum WindowClass {
        COPY_FROM_PARENT,
        INPUT_OUTPUT,
        INPUT_ONLY
    }

    public WindowAttributes(Window window) {
        this.window = window;
    }

    public int getBackingPixel() {
        return this.backingPixel;
    }

    public int getBackingPlanes() {
        return this.backingPlanes;
    }

    public BackingStore getBackingStore() {
        return this.backingStore;
    }

    public BitGravity getBitGravity() {
        return this.bitGravity;
    }

    public Cursor getCursor() {
        Window parent = this.window.getParent();
        return (this.cursor != null || parent == null) ? this.cursor : parent.attributes.getCursor();
    }

    public Bitmask getEventMask() {
        return this.eventMask;
    }

    public Bitmask getDoNotPropagateMask() {
        return this.doNotPropagateMask;
    }

    public boolean isMapped() {
        return this.mapped;
    }

    public void setMapped(boolean mapped) {
        this.mapped = mapped;
    }

    public boolean isOverrideRedirect() {
        return this.overrideRedirect;
    }

    public boolean isSaveUnder() {
        return this.saveUnder;
    }

    public WinGravity getWinGravity() {
        return this.winGravity;
    }

    public WindowClass getWindowClass() {
        return this.windowClass;
    }

    public void setWindowClass(WindowClass windowClass) {
        this.windowClass = windowClass;
    }

    public Window getWindow() {
        return this.window;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void update(Bitmask valueMask, XInputStream inputStream, XClient client) {
        Iterator<Integer> it = valueMask.iterator();
        while (it.hasNext()) {
            int index = it.next().intValue();
            switch (index) {
                case 1:
                case 4:
                case 8:
                case 8192:
                    inputStream.skip(4);
                    break;
                case 2:
                    this.window.getContent().fillColor(inputStream.readInt());
                    break;
                case 16:
                    this.bitGravity = BitGravity.values()[inputStream.readInt()];
                    break;
                case 32:
                    this.winGravity = WinGravity.values()[inputStream.readInt()];
                    break;
                case 64:
                    this.backingStore = BackingStore.values()[inputStream.readInt()];
                    break;
                case 128:
                    this.backingPlanes = inputStream.readInt();
                    break;
                case 256:
                    this.backingPixel = inputStream.readInt();
                    break;
                case 512:
                    this.overrideRedirect = inputStream.readInt() == 1;
                    break;
                case 1024:
                    this.saveUnder = inputStream.readInt() == 1;
                    break;
                case 2048:
                    this.eventMask = new Bitmask(inputStream.readInt());
                    break;
                case 4096:
                    this.doNotPropagateMask = new Bitmask(inputStream.readInt());
                    break;
                case 16384:
                    this.cursor = client.xServer.cursorManager.getCursor(inputStream.readInt());
                    break;
            }
        }
        client.xServer.windowManager.triggerOnUpdateWindowAttributes(this.window, valueMask);
    }
}
