package com.winlator.cmod.xserver;

import android.hardware.HardwareBuffer;
import android.util.SparseArray;

import com.winlator.cmod.core.CursorLocker;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.extensions.BigReqExtension;
import com.winlator.cmod.xserver.extensions.DRI3Extension;
import com.winlator.cmod.xserver.extensions.Extension;
import com.winlator.cmod.xserver.extensions.GLXExtension;
import com.winlator.cmod.xserver.extensions.MITSHMExtension;
import com.winlator.cmod.xserver.extensions.PresentExtension;
import com.winlator.cmod.xserver.extensions.SyncExtension;
import com.winlator.cmod.xserver.extensions.XInputExtension;

import com.winlator.cmod.xserver.extensions.XComposite;
import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private String displayDriver;
    private SHMSegmentManager shmSegmentManager;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private boolean simulateTouchScreen = false;
    private boolean disableMouse = false;
    private boolean isGrabbed = false;
    private int surfaceFormat = Drawable.HAL_PIXEL_FORMAT_BGRA_8888;
    private XServerView xServerView;
    private XClient grabbingClient = null;
    private XInputExtension xInputExtension;
    // Last position of an absolute source (touchscreen, stylus, uncaptured mouse), raw motion is measured from it
    private boolean absolutePointerAnchored = false;
    private int absolutePointerX;
    private int absolutePointerY;

    public XServer(ScreenInfo screenInfo, String displayDriver, KeyValueSet displayConfig) {
        this.screenInfo = screenInfo;
        this.displayDriver = displayDriver;
        
        if (displayConfig != null) {
            String surfaceFormat = displayConfig.get("surfaceFormat");
            if (surfaceFormat.equals("rgba8"))
                this.surfaceFormat = HardwareBuffer.RGBA_8888;
            else 
                this.surfaceFormat = Drawable.HAL_PIXEL_FORMAT_BGRA_8888;    
        }
        
        cursorLocker = new CursorLocker(this);
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        setupExtensions();
    }
    
    public String getDisplayDriver() {
        return this.displayDriver;
    }
    
    public void setDisplayDriver(String displayDriver) {
        this.displayDriver = displayDriver;
    }
   
    public int getSurfaceFormat() {
        return this.surfaceFormat;
    }
    
    public boolean isDisplayX() {
        return this.displayDriver.toLowerCase().equals("displayx");
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public boolean isSimulateTouchScreen() { return simulateTouchScreen; }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }
    
    public void setXServerView(XServerView view) {
        this.xServerView = view;
    }
    
    public XServerView getXServerView() {
        return this.xServerView;
    }
    
    public void setMouseDisabled(boolean mouseDisabled) {
        this.disableMouse = mouseDisabled;
        xServerView.setCursorVisible(!mouseDisabled);
    }
    
    public boolean isMouseDisabled() {
        return this.disableMouse;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.valueAt(i);
            if (extension.getName().equals(name)) return extension;
        }
        return null;
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            if (absolutePointerAnchored) xInputExtension.sendRawMotion(x - absolutePointerX, y - absolutePointerY);
            setAbsolutePointerAnchor(x, y);
            pointer.setPosition(x, y);
        }
    }

    // Jump to a new position (new touch, stylus down): moves the cursor without raw motion
    public void injectPointerWarp(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            setAbsolutePointerAnchor(x, y);
            pointer.setPosition(x, y);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            xInputExtension.sendRawMotion(dx, dy);
            absolutePointerAnchored = false;
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
        }
    }

    private void setAbsolutePointerAnchor(int x, int y) {
        absolutePointerAnchored = true;
        absolutePointerX = x;
        absolutePointerY = y;
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    private void setupExtensions() {
        extensions.put(BigReqExtension.MAJOR_OPCODE, new BigReqExtension());
        extensions.put(MITSHMExtension.MAJOR_OPCODE, new MITSHMExtension());
        extensions.put(DRI3Extension.MAJOR_OPCODE, new DRI3Extension(this));
        extensions.put(PresentExtension.MAJOR_OPCODE, new PresentExtension(this));
        extensions.put(SyncExtension.MAJOR_OPCODE, new SyncExtension(this));
        extensions.put(GLXExtension.MAJOR_OPCODE, new GLXExtension(this));
        extensions.put(XComposite.MAJOR_OPCODE, new XComposite(this));
        xInputExtension = new XInputExtension(this);
        extensions.put(XInputExtension.MAJOR_OPCODE, xInputExtension);
    }

    public XInputExtension getXInputExtension() {
        return xInputExtension;
    }

    public <T extends Extension> T getExtension(int opcode) {
        return (T)extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        return isGrabbed && grabbingClient == client;
    }
}
