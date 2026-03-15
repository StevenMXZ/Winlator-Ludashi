package com.winlator.cmod.xserver;

import android.util.SparseArray;
import com.winlator.cmod.core.CursorLocker;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.extensions.BigReqExtension;
import com.winlator.cmod.xserver.extensions.DRI3Extension;
import com.winlator.cmod.xserver.extensions.Extension;
import com.winlator.cmod.xserver.extensions.MITSHMExtension;
import com.winlator.cmod.xserver.extensions.PresentExtension;
import com.winlator.cmod.xserver.extensions.SyncExtension;
import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

/* loaded from: classes11.dex */
public class XServer {
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final short VERSION = 11;
    public final CursorManager cursorManager;
    public final DrawableManager drawableManager;
    public final GrabManager grabManager;
    public final InputDeviceManager inputDeviceManager;
    public final PixmapManager pixmapManager;
    private GLRenderer renderer;
    public final ScreenInfo screenInfo;
    public final SelectionManager selectionManager;
    private SHMSegmentManager shmSegmentManager;
    private WinHandler winHandler;
    public final WindowManager windowManager;
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private boolean simulateTouchScreen = false;
    private boolean isGrabbed = false;
    private XClient grabbingClient = null;
    public final CursorLocker cursorLocker = new CursorLocker(this);

    public enum Lockable {
        WINDOW_MANAGER,
        PIXMAP_MANAGER,
        DRAWABLE_MANAGER,
        GRAPHIC_CONTEXT_MANAGER,
        INPUT_DEVICE,
        CURSOR_MANAGER,
        SHMSEGMENT_MANAGER
    }

    public XServer(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
        for (Lockable lockable : Lockable.values()) {
            this.locks.put((EnumMap<Lockable, ReentrantLock>) lockable, (Lockable) new ReentrantLock());
        }
        this.pixmapManager = new PixmapManager();
        this.drawableManager = new DrawableManager(this);
        this.cursorManager = new CursorManager(this.drawableManager);
        this.windowManager = new WindowManager(screenInfo, this.drawableManager);
        this.selectionManager = new SelectionManager(this.windowManager);
        this.inputDeviceManager = new InputDeviceManager(this);
        this.grabManager = new GrabManager(this);
        DesktopHelper.attachTo(this);
        setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return this.relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        this.cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public boolean isSimulateTouchScreen() {
        return this.simulateTouchScreen;
    }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }

    public GLRenderer getRenderer() {
        return this.renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public WinHandler getWinHandler() {
        return this.winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return this.shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = (ReentrantLock) XServer.this.locks.get(lockable);
            this.lock.lock();
        }

        @Override // com.winlator.cmod.xserver.XLock, java.lang.AutoCloseable
        public void close() {
            this.lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) {
                ((ReentrantLock) XServer.this.locks.get(lockable)).lock();
            }
        }

        @Override // com.winlator.cmod.xserver.XLock, java.lang.AutoCloseable
        public void close() {
            for (int i = this.lockables.length - 1; i >= 0; i--) {
                ((ReentrantLock) XServer.this.locks.get(this.lockables[i])).unlock();
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
        for (int i = 0; i < this.extensions.size(); i++) {
            Extension extension = this.extensions.valueAt(i);
            if (extension.getName().equals(name)) {
                return extension;
            }
        }
        return null;
    }

    public void injectPointerMove(int x, int y) {
        XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE);
        try {
            this.pointer.setPosition(x, y);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE);
        try {
            this.pointer.setPosition(this.pointer.getX() + dx, this.pointer.getY() + dy);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE);
        try {
            this.pointer.setButton(buttonCode, true);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE);
        try {
            this.pointer.setButton(buttonCode, false);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE);
        try {
            this.keyboard.setKeyPress(xKeycode.id, keysym);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE);
        try {
            this.keyboard.setKeyRelease(xKeycode.id);
            if (lock != null) {
                lock.close();
            }
        } catch (Throwable th) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private void setupExtensions() {
        this.extensions.put(-100, new BigReqExtension());
        this.extensions.put(-101, new MITSHMExtension());
        this.extensions.put(-102, new DRI3Extension());
        this.extensions.put(-103, new PresentExtension());
        this.extensions.put(-104, new SyncExtension());
    }

    public <T extends Extension> T getExtension(int opcode) {
        return (T) this.extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        boolean z;
        if (this.isGrabbed) {
            z = this.grabbingClient == client;
        }
        return z;
    }
}
