package com.winlator.cmod.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.lsfg.LosslessScaling;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.renderer.MagnifierPan;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dalvik.annotation.optimization.FastNative;

@SuppressLint("ViewConstructor")
public class VulkanXServerView extends XServerRendererView implements SurfaceHolder.Callback,
        WindowManager.OnWindowModificationListener, Pointer.OnPointerMotionListener, VulkanRendererControls {

    private long nativeHandle = 0;
    private final Object lock = new Object();
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private final Set<Integer> compositeRedirectedWindows = ConcurrentHashMap.newKeySet();

    private boolean fullscreen = false;
    private float magnifierZoom = 1.0f;
    private final MagnifierPan magnifierPan = new MagnifierPan();
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private boolean cursorVisible = false;
    private String driverPath = null;
    private ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;
    private volatile boolean inPipMode = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private boolean validationEnabled = false;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private static volatile boolean gpuImageChecked = false;

    private int     pendingPresentMode    = 2;
    private int     pendingStretchMode    = 0;
    private int     pendingFilterMode     = 0;
    private int     pendingPostFXMode     = 0;
    private float   pendingSharpness      = 0.5f;
    private boolean pendingSwapRB         = false;
    private boolean tuningFrameGen = false;
    private int     tuningFrameGenMode = 0;
    private int     tuningFrameGenQuality = 1;
    private int     tuningFrameGenMultiplier = 2;
    private volatile boolean pendingFrameGenEnabled = false;
    private volatile boolean effectiveFrameGenEnabled = false;
    private volatile Runnable frameGenerationStateListener = null;
    private String pendingFrameGenEngine = "lsfg";
    private String  pendingFrameGenCache = null;
    private int     pendingFrameGenMultiplier = 2;
    private int     pendingFrameGenTargetRate = -1;
    private int     pendingFrameGenFlowScale = 70;
    private float   pendingFrameGenRefreshRate = 0.0f;
    private final AtomicLong sourceFrames = new AtomicLong();

    private WinlatorHUD hudRef = null;
    private FrameRating classicHudRef = null;
    private volatile int fpsWindowId = -1;
    private volatile int fpsLimit = 0;
    private volatile int frameGenSourceCap = 0;

    // Performance frame generation decides per vsync, so it needs the display's timestamps.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean vsyncTracking = false;
    private final Choreographer.FrameCallback vsyncCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!vsyncTracking) return;
            nativeNoteVsync(frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private static volatile boolean nativeLibLoaded = false;

    public static synchronized void loadNativeLibrary() {
        if (!nativeLibLoaded) {
            System.loadLibrary("vulkan_renderer");
            nativeLibLoaded = true;
        }
    }

    public VulkanXServerView(Context context, XServer xServer) {
        super(context, xServer);
        setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);

        rootCursorDrawable = createRootCursorDrawable();
        xServer.setXServerView(this);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(getContext().getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) { return null; }
    }

    private native long nativeInit(Surface surface,
                                   int screenWidth, int screenHeight,
                                   String driverPath, String libraryName, String nativeLibDir,
                                   boolean validationEnabled);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, Surface surface);
    private native int[] nativeGetSwapchainSize(long handle);

    @FastNative private native void nativeUpdateWindowContent(long handle, long id,
        ByteBuffer pixels, short width, short height, short stride, int x, int y);
    @FastNative private native void nativeUpdateWindowContentAHB(long handle, long id,
        long ahbPtr, short width, short height, int x, int y);
    @FastNative private native void nativeSetTransformAndScissor(long handle,
        float ox, float oy, float sx, float sy,
        boolean hasScissor, int scX, int scY, int scW, int scH);
    @FastNative private native void nativeSetPointerPos(long handle, short x, short y);
    @FastNative private native void nativeSetCursorVisible(long handle, boolean visible);
    @FastNative private native void nativeUpdateCursorImage(long handle,
        ByteBuffer pixels, short width, short height, short stride, short hotX, short hotY);
    @FastNative private native void nativeRemoveWindow(long handle, long id);
    @FastNative private native void nativeSetVerboseLog(long handle, boolean v);
    @FastNative private native void nativeSetSharpness(long handle, float sharpness);

    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetStretchMode(long handle, int mode);
    private native void nativeSetPostFXMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native int[] nativeGetSupportedPresentModes(long handle);
    private native void nativeSetFrameGen(long handle, boolean enabled, int mode, int quality,
                                          int multiplier, float targetHz);
    private native float[] nativeGetFrameGenStats(long handle);
    @FastNative private static native void nativeNoteVsync(long frameTimeNanos);
    @FastNative private static native boolean nativeFrameGenSourceUncapped();
    private native void nativeSetFrameGenerationEngine(long handle, int engine);
    private native boolean nativeConfigureFrameGeneration(long handle, boolean enabled,
        String cachePath, int multiplier, int targetRate, int flowScalePercent,
        float refreshRate);
    @FastNative private native void nativeSetFrameGenerationSourceCount(long handle, long count);
    private native boolean nativeIsFrameGenerationSupported(long handle);
    private native long nativeGetGeneratedFrameCount(long handle);

    private native void nativeInitRootWindow(long handle, long rootId, long contentId, int width, int height);
    private native void nativeCreateWindow(long handle, long id, long parentId, long contentId,
                                           int x, int y, int width, int height);
    private native void nativeDestroyWindow(long handle, long id);
    private native void nativeMapWindow(long handle, long id);
    private native void nativeUnmapWindow(long handle, long id);
    private native void nativeReparentWindow(long handle, long id, long newParentId);
    private native void nativeUpdateWindowGeometry(long handle, long id, long contentId,
                                                   int x, int y, int width, int height);
    private native void nativeSyncChildOrder(long handle, long parentId, long[] orderedChildIds, int count);
    private native void nativeSetWindowViewable(long handle, long id, boolean viewable);

    private static long did(Drawable d) {
        return (long) System.identityHashCode(d);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        onSurfaceCreated(holder.getSurface());
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        onSurfaceDestroyed();
    }

    public void onSurfaceCreated(Surface surface) {
        if (!gpuImageChecked) { GPUImage.checkIsSupported(); gpuImageChecked = true; }
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        initExecutor = Executors.newSingleThreadExecutor();

        final boolean isColdInit = (nativeHandle == 0);

        initExecutor.execute(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                    } else {

                        nativeSetPresentMode(nativeHandle, pendingPresentMode);
                        nativeSetFilterMode(nativeHandle, pendingFilterMode);
                        nativeSetSwapRB(nativeHandle, pendingSwapRB);
                        nativeSetStretchMode(nativeHandle, pendingStretchMode);
                        nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                        nativeSetSharpness(nativeHandle, pendingSharpness);
                        applyPendingFrameGeneration();
                        applyFrameGen();
                        updateTransform();
                        nativeSetCursorVisible(nativeHandle, cursorVisible);
                        initComplete = true;
                        return;
                    }
                }

                nativeHandle = nativeInit(
                    surface,
                    xServer.screenInfo.width, xServer.screenInfo.height,
                    driverPath, driverLibraryName, nativeLibDir, validationEnabled);

                if (nativeHandle != 0) {
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetFilterMode(nativeHandle, pendingFilterMode);
                    nativeSetSwapRB(nativeHandle, pendingSwapRB);
                    nativeSetPostFXMode(nativeHandle, pendingPostFXMode);
                    nativeSetSharpness(nativeHandle, pendingSharpness);
                    applyPendingFrameGeneration();
                    applyFrameGen();
                    updateTransform();
                    nativeSetCursorVisible(nativeHandle, cursorVisible);

                    if (isColdInit) {
                        Window root = xServer.windowManager.rootWindow;
                        nativeInitRootWindow(nativeHandle, root.id, did(root.getContent()),
                            xServer.screenInfo.width, xServer.screenInfo.height);
                    }
                }
            }
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeSetVerboseLog(nativeHandle, true);
                    nativeDumpRendererInfo(nativeHandle);
                }
            }
            initComplete = true;
        });
    }

    public void onSurfaceChanged(int width, int height) {
        if (inPipMode) return;
        surfaceWidth = width; surfaceHeight = height;
        viewTransformation.update(width, height, xServer.screenInfo.width, xServer.screenInfo.height);
        synchronized (lock) {
            if (nativeHandle != 0) { nativeResize(nativeHandle, width, height); updateTransform(); }
        }
    }

    public void onSurfaceDestroyed() {
        initComplete = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            if (nativeHandle != 0) nativeDetachSurface(nativeHandle);
        }
    }

    @Override
    public void onDestroy() {
        forceCleanup();
    }

    public void forceCleanup() {
        initComplete = false;
        setVsyncTracking(false);
        frameGenerationStateListener = null;
        effectiveFrameGenEnabled = false;
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        compositeRedirectedWindows.clear();
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeDestroy(nativeHandle);
                nativeHandle = 0;
            }
        }
    }

    public void queueEvent(Runnable r) { eventExecutor.execute(r); }
    @Override
    public void onPause()  {}
    @Override
    public void onResume() {}

    private void updateTransform() {
        if (nativeHandle == 0) return;
        // Zoom is a render-only scene transform. Do not resize the Surface,
        // call RandR, or change the resolution seen by the game.
        final float zoom = magnifierZoom;
        final float ptrX = xServer.pointer.getX(), ptrY = xServer.pointer.getY();
        final int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;
        magnifierPan.update(ptrX, ptrY, screenW, screenH, zoom);

        final float baseScaleX = fullscreen ? 1f : viewTransformation.sceneScaleX;
        final float baseScaleY = fullscreen ? 1f : viewTransformation.sceneScaleY;
        final float baseX = fullscreen ? 0f : viewTransformation.sceneOffsetX;
        final float baseY = fullscreen ? 0f : viewTransformation.sceneOffsetY;
        float offsetY = baseY - magnifierPan.panY() * baseScaleY;
        if (screenOffsetYRelativeToCursor && zoom <= 1f && !fullscreen) {
            final float halfH = screenH * .5f;
            offsetY -= Math.max(0f, Math.min(ptrY - halfH * .5f, halfH));
        }
        nativeSetTransformAndScissor(nativeHandle,
            baseX - magnifierPan.panX() * baseScaleX, offsetY,
            baseScaleX * zoom, baseScaleY * zoom,
            !fullscreen,
            viewTransformation.viewOffsetX, viewTransformation.viewOffsetY,
            viewTransformation.viewWidth, viewTransformation.viewHeight);
    }

    private void sendCursorToNative(Cursor cursor) {
        if (nativeHandle == 0) return;
        Drawable cd; short hotX = 0, hotY = 0;
        boolean effVis = cursorVisible;
        if (cursor != null) {
            if (!cursor.isVisible()) effVis = false;
            cd = cursor.cursorImage; hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY;
        } else { cd = rootCursorDrawable; }
        nativeSetCursorVisible(nativeHandle, effVis);
        if (effVis && cd != null && cd.backingAHB != 0) {
            synchronized (cd.renderLock) {
                ByteBuffer pixels = cd.lockBuffer(cd.backingAHB);
                if (pixels != null) {
                    try {
                        nativeUpdateCursorImage(nativeHandle, pixels, cd.width, cd.height,
                            cd.getStride(), hotX, hotY);
                    } finally {
                        cd.unlockBuffer(cd.backingAHB);
                    }
                }
            }
        }
    }

    private void checkViewable(Window window) {
        if (unviewableWMClasses == null || window == xServer.windowManager.rootWindow) return;
        boolean viewable = true;
        String wc = window.getClassName();
        for (String cls : unviewableWMClasses) {
            if (wc.contains(cls)) {
                if (window.attributes.isEnabled()) window.disableAllDescendants();
                viewable = false;
                break;
            }
        }
        final long id = window.id;
        final boolean v = viewable;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeSetWindowViewable(nativeHandle, id, v);
            }
        });
    }

    @Override
    public void onCreateWindow(Window window, Window parent) {
        final long id = window.id;
        final long parentId = parent != null ? parent.id : 0;
        final long contentId = did(window.getContent());
        final int x = window.getX(), y = window.getY(), w = window.getWidth(), h = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeCreateWindow(nativeHandle, id, parentId, contentId, x, y, w, h);
            }
        });
        checkViewable(window);
    }

    @Override
    public void onDestroyWindow(Window window) {
        final long id = window.id;
        compositeRedirectedWindows.remove(window.id);
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeDestroyWindow(nativeHandle, id);
            }
        });
    }

    @Override
    public void onMapWindow(Window window) {
        final long id = window.id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeMapWindow(nativeHandle, id);
            }
        });
        checkViewable(window);
    }

    @Override
    public void onUnmapWindow(Window window) {
        final long id = window.id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeUnmapWindow(nativeHandle, id);
            }
        });
    }

    @Override
    public void onReparentWindow(Window window, Window newParent, short x, short y) {
        final long id = window.id;
        final long newParentId = newParent != null ? newParent.id : 0;
        final long contentId = did(window.getContent());
        final int width = window.getWidth(), height = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    nativeReparentWindow(nativeHandle, id, newParentId);
                    nativeUpdateWindowGeometry(nativeHandle, id, contentId, x, y, width, height);
                }
            }
        });
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        Window parent = window.getParent();
        if (parent == null) return;
        List<Window> children = parent.getChildren();
        final long parentId = parent.id;
        final long[] orderedIds = new long[children.size()];
        for (int i = 0; i < orderedIds.length; i++) orderedIds[i] = children.get(i).id;
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeSyncChildOrder(nativeHandle, parentId, orderedIds, orderedIds.length);
            }
        });
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        final long id = window.id;
        final long contentId = did(window.getContent());
        final int x = window.getX(), y = window.getY(), w = window.getWidth(), h = window.getHeight();
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeUpdateWindowGeometry(nativeHandle, id, contentId, x, y, w, h);
            }
        });
    }

    @Override
    public void onModifyWindowProperty(Window window, Property property) {

        checkViewable(window);
    }

    @Override
    public void onUpdateWindowAttributes(Window window, Bitmask mask) {
        if (mask.isSet(WindowAttributes.FLAG_CURSOR)) {
            synchronized (lock) {
                Window pw = xServer.inputDeviceManager.getPointWindow();
                if (pw == window) { lastCursor = window.attributes.getCursor(); sendCursorToNative(lastCursor); }
            }
        }
    }

    @Override
    public void onUpdateWindowContentDirect(Window window, Drawable drawable) {
        onUpdateWindowContentDirect(window, drawable, (short) 0, (short) 0);
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
        if (window.id == fpsWindowId) {
            if (hudRef != null) hudRef.onFrame();
            if (classicHudRef != null) classicHudRef.update();
        }
        synchronized (lock) {
            if (nativeHandle == 0 || pixmap == null) return;
            if (isFrameGenerationSource(window)) {
                nativeSetFrameGenerationSourceCount(nativeHandle,
                    sourceFrames.incrementAndGet());
            }
            final int rx = window.getRootX() + xOff, ry = window.getRootY() + yOff;
            synchronized (pixmap.renderLock) {
                if (pixmap.backingAHB != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, did(window.getContent()),
                        pixmap.backingAHB, pixmap.width, pixmap.height, rx, ry);
                }
            }
        }
    }

    public void addDirectContent(int windowId, Drawable drawable, GPUImage gpuImage) {

    }

    public void nativeRemoveDirectContent(int windowId, int pixmapId) {
        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null || pixmap.drawable == null) return;
        final long contentId = did(pixmap.drawable);
        queueEvent(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, contentId);
            }
        });
    }

    @Override
    public void nativeSetCompositeRedirected(int windowId, boolean redirected) {
        if (xServer.windowManager.getWindow(windowId) == null) return;
        if (redirected) compositeRedirectedWindows.add(windowId);
        else compositeRedirectedWindows.remove(windowId);
    }

    private static boolean hasAncestor(Window window, Window ancestor) {
        Window current = window;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    private static Window getWindowSibling(Window window, Window other) {
        Window current = window;
        while (current != null) {
            if (current.getParent() == other.getParent()) return current;
            current = current.getParent();
        }
        return null;
    }

    @Override
    public void nativeCompositeRedirect(int srcDrawableId, int dstDrawableId, short dstX, short dstY) {
        Window srcWindow = xServer.windowManager.getWindow(srcDrawableId);
        if (srcWindow == null) return;

        Window dstWindow = xServer.windowManager.getWindow(dstDrawableId);
        if (dstWindow == null) return;

        if (!hasAncestor(srcWindow, dstWindow)) {
            Window sibling = getWindowSibling(srcWindow, dstWindow);
            if (sibling == null) return;

            int posX = dstWindow.getX() + dstX;
            int posY = dstWindow.getY() + dstY;
            boolean positionChanged = sibling.getX() != posX || sibling.getY() != posY;

            if (positionChanged) {
                sibling.setX((short)posX);
                sibling.setY((short)posY);
                onUpdateWindowGeometry(sibling, false);
            }

            Window parent = sibling.getParent();
            if (parent == null || parent != dstWindow.getParent()) return;

            List<Window> children = parent.getChildren();
            int siblingIndex = children.indexOf(sibling);
            int dstIndex = children.indexOf(dstWindow);
            if (siblingIndex < 0 || dstIndex < 0) return;

            if (siblingIndex <= dstIndex) {
                parent.moveChildAbove(sibling, dstWindow);
                onChangeWindowZOrder(Window.StackMode.ABOVE, sibling, dstWindow);
            }
        }

    }

    @Override
    public void onUpdateWindowContent(Window window) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            Drawable drawable = window.getContent();
            if (drawable == null || !window.attributes.isMapped()) return;
            if (isFrameGenerationSource(window)) {
                nativeSetFrameGenerationSourceCount(nativeHandle,
                    sourceFrames.incrementAndGet());
            }

            final int rx = window.getRootX(), ry = window.getRootY();
            synchronized (drawable.renderLock) {
                if (drawable.backingAHB != 0) {
                    nativeUpdateWindowContentAHB(nativeHandle, did(drawable),
                        drawable.backingAHB, drawable.width, drawable.height, rx, ry);
                }
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, x, y);
            Window pw = xServer.inputDeviceManager.getPointWindow();
            Cursor cursor = pw != null ? pw.attributes.getCursor() : null;
            if (cursor != lastCursor) { lastCursor = cursor; sendCursorToNative(lastCursor); }
            if (screenOffsetYRelativeToCursor || magnifierZoom != 1.0f) updateTransform();
        }
    }

    @Override
    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) { nativeSetCursorVisible(nativeHandle, visible); if (visible) sendCursorToNative(lastCursor); }
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
    }

    public void setValidationEnabled(boolean enabled, String nativeLibDir) {
        this.validationEnabled = enabled;
        this.nativeLibDir = nativeLibDir;
    }

    public void setVerboseLog(boolean v) {
        synchronized (lock) { if (nativeHandle != 0) nativeSetVerboseLog(nativeHandle, v); }
    }

    public void dumpRendererInfo() {
        synchronized (lock) { if (nativeHandle != 0) nativeDumpRendererInfo(nativeHandle); }
    }

    public void setStretchMode(int mode) {
        pendingStretchMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetStretchMode(nativeHandle, mode); }
    }

    public void setPostFXMode(int mode) {
        pendingPostFXMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPostFXMode(nativeHandle, mode); }
    }

    public void setSharpness(float s) {
        pendingSharpness = s;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSharpness(nativeHandle, s); }
    }

    public void setFilterMode(int mode) {
        pendingFilterMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode); }
    }

    private float displayRefreshRate() {
        android.view.Display display = getDisplay();
        float hz = display != null ? display.getRefreshRate() : 0f;
        return hz > 1f ? hz : 60f;
    }

    // Caller must hold {@code lock} and have a valid native handle.
    private void applyFrameGen() {
        updateFrameGenSourceCap();
        nativeSetFrameGen(nativeHandle, tuningFrameGen, tuningFrameGenMode,
            tuningFrameGenQuality, tuningFrameGenMultiplier, displayRefreshRate());
    }

    private void updateFrameGenSourceCap() {
        frameGenSourceCap = tuningFrameGen && tuningFrameGenMode == 0
            ? Math.round(displayRefreshRate() / tuningFrameGenMultiplier) : 0;
    }

    private void setVsyncTracking(boolean on) {
        if (vsyncTracking == on) return;
        vsyncTracking = on;
        mainHandler.post(() -> {
            Choreographer ch = Choreographer.getInstance();
            ch.removeFrameCallback(vsyncCallback);
            if (vsyncTracking) ch.postFrameCallback(vsyncCallback);
        });
    }

    /**
     * @param mode       0 power saver (game capped at refresh / multiplier, then multiplied),
     *                   1 performance (game uncapped, only refreshes it would miss get generated)
     * @param quality    0 low, 1 medium, 2 high (motion estimation detail)
     * @param multiplier power saver only: 2 or 3 output frames per real frame
     */
    @Override
    public void setFrameGen(boolean enabled, int mode, int quality, int multiplier) {
        tuningFrameGen = enabled;
        tuningFrameGenMode = mode;
        tuningFrameGenQuality = quality;
        tuningFrameGenMultiplier = Math.max(2, Math.min(3, multiplier));
        updateFrameGenSourceCap();
        setVsyncTracking(enabled && mode == 1);
        synchronized (lock) { if (nativeHandle != 0) applyFrameGen(); }
    }

    /**
     * srcFps, outFps, gpuMs, addedLatencyMs, generatedPerFrame,
     * state (0 off, 1 on, 2 n/a, 3 paused, 4 on with lowered quality), quality in use.
     */
    @Override
    public float[] getFrameGenStats() {
        synchronized (lock) {
            if (nativeHandle != 0) return nativeGetFrameGenStats(nativeHandle);
        }
        return new float[6];
    }

    public void setSwapRB(boolean enabled) {
        pendingSwapRB = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSwapRB(nativeHandle, enabled); }
    }

    public void setVkPresentMode(int mode) {
        pendingPresentMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode); }
    }

    private void applyPendingFrameGeneration() {
        if (nativeHandle == 0) {
            effectiveFrameGenEnabled = false;
            return;
        }
        nativeSetFrameGenerationEngine(nativeHandle, "wfg".equals(pendingFrameGenEngine) ? 1 : 0);
        final boolean wasRequested = pendingFrameGenEnabled;
        final boolean supported = nativeIsFrameGenerationSupported(nativeHandle);
        final boolean shouldEnable = wasRequested && supported;
        if (wasRequested && !supported)
            pendingFrameGenEnabled = false;
        final int effectiveTargetRate = pendingFrameGenTargetRate == -1
            ? (pendingFrameGenRefreshRate >= 1.0f
                ? Math.round(pendingFrameGenRefreshRate) : 60)
            : pendingFrameGenTargetRate;
        final boolean nativeEnabled = nativeConfigureFrameGeneration(nativeHandle, shouldEnable,
            pendingFrameGenCache, pendingFrameGenMultiplier, effectiveTargetRate,
            pendingFrameGenFlowScale, pendingFrameGenRefreshRate);
        nativeSetFrameGenerationSourceCount(nativeHandle, sourceFrames.get());
        boolean previous = effectiveFrameGenEnabled;
        effectiveFrameGenEnabled = shouldEnable && nativeEnabled;
        if (shouldEnable && !nativeEnabled)
            pendingFrameGenEnabled = false;
        if (previous != effectiveFrameGenEnabled || wasRequested != pendingFrameGenEnabled)
            notifyFrameGenerationStateChanged();
    }

    private void notifyFrameGenerationStateChanged() {
        Runnable listener = frameGenerationStateListener;
        if (listener != null) post(listener);
    }

    public void setOnFrameGenerationStateChangedListener(Runnable listener) {
        frameGenerationStateListener = listener;
        if (listener != null) post(listener);
    }

    @Override public void setFrameGenerationEngine(String engine) {
        synchronized (lock) {
            if (!pendingFrameGenEnabled && !effectiveFrameGenEnabled)
                pendingFrameGenEngine = "wfg".equals(engine) ? "wfg" : "lsfg";
        }
    }
    @Override public String getFrameGenerationEngine() { return pendingFrameGenEngine; }

    public void setFrameGenerationConfig(boolean enabled, String cachePath,
                                         int multiplier, int targetRate,
                                         int flowScalePercent, float refreshRate) {
        synchronized (lock) {
            pendingFrameGenEnabled = enabled && ("wfg".equals(pendingFrameGenEngine) ||
                (cachePath != null && !cachePath.isEmpty()));
            if (nativeHandle == 0) effectiveFrameGenEnabled = false;
            pendingFrameGenCache = cachePath;
            pendingFrameGenMultiplier = Math.max(2, Math.min(4, multiplier));
            pendingFrameGenTargetRate = Math.max(-1, targetRate);
            pendingFrameGenFlowScale = Math.max(25, Math.min(100, flowScalePercent));
            pendingFrameGenRefreshRate = Math.max(0.0f, refreshRate);
            applyPendingFrameGeneration();
        }
    }

    public boolean setFrameGenerationEnabled(boolean enabled) {
        synchronized (lock) {
            if (enabled && !"wfg".equals(pendingFrameGenEngine) && (pendingFrameGenCache == null || pendingFrameGenCache.isEmpty() ||
                    !new java.io.File(pendingFrameGenCache).isFile())) {
                // A disabled launch intentionally skips cache lookup. Resolve
                // it here so the sidebar can enable LSFG later in the session.
                java.io.File cache = LosslessScaling.resolveCacheFile(getContext(), true);
                if (cache == null) return false;
                pendingFrameGenCache = cache.getAbsolutePath();
            }
            if (enabled && nativeHandle != 0 && !nativeIsFrameGenerationSupported(nativeHandle)) {
                pendingFrameGenEnabled = false;
                effectiveFrameGenEnabled = false;
                notifyFrameGenerationStateChanged();
                return false;
            }
            pendingFrameGenEnabled = enabled;
            applyPendingFrameGeneration();
            return !enabled || effectiveFrameGenEnabled || nativeHandle == 0;
        }
    }

    public void setFrameGenerationSettings(int multiplier, int targetRate,
                                           int flowScalePercent) {
        synchronized (lock) {
            pendingFrameGenMultiplier = Math.max(2, Math.min(4, multiplier));
            pendingFrameGenTargetRate = Math.max(-1, targetRate);
            pendingFrameGenFlowScale = Math.max(25, Math.min(100, flowScalePercent));
            applyPendingFrameGeneration();
        }
    }

    public int getFrameGenerationMultiplier() {
        synchronized (lock) { return pendingFrameGenMultiplier; }
    }

    public int getFrameGenerationTargetRate() {
        synchronized (lock) { return pendingFrameGenTargetRate; }
    }

    public int getFrameGenerationFlowScale() {
        synchronized (lock) { return pendingFrameGenFlowScale; }
    }

    public boolean isFrameGenerationSupported() {
        synchronized (lock) {
            return nativeHandle != 0 && nativeIsFrameGenerationSupported(nativeHandle);
        }
    }

    public boolean isFrameGenerationEnabled() {
        return effectiveFrameGenEnabled;
    }
    public boolean isFrameGenerationRequested() {
        synchronized (lock) { return pendingFrameGenEnabled; }
    }

    private boolean isFrameGenerationSource(Window window) {
        if (!effectiveFrameGenEnabled) return false;
        return fpsWindowId >= 0 ? window.id == fpsWindowId : window.isApplicationWindow();
    }

    public long getGeneratedFrameCount() {
        synchronized (lock) {
            return nativeHandle != 0 ? nativeGetGeneratedFrameCount(nativeHandle) : 0L;
        }
    }

    public int[] getSupportedPresentModes() {
        synchronized (lock) {
            if (nativeHandle != 0) return nativeGetSupportedPresentModes(nativeHandle);
        }
        return new int[0];
    }

    @Override
    public boolean isFullscreen() { return fullscreen; }
    @Override
    public void toggleFullscreen() {
        fullscreen = !fullscreen;
        synchronized (lock) { updateTransform(); }
    }
    @Override
    public void setScreenOffsetYRelativeToCursor(boolean b) {
        screenOffsetYRelativeToCursor = b;
        synchronized (lock) { updateTransform(); }
    }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    @Override
    public void setMagnifierZoom(float zoom) {
        synchronized (lock) {
            final float bounded = Math.max(1f, Math.min(3f, zoom));
            if (magnifierZoom != bounded) magnifierPan.reset();
            magnifierZoom = bounded;
            updateTransform();
        }
    }
    @Override
    public float getMagnifierZoom() { return magnifierZoom; }
    @Override
    public void setUnviewableWMClasses(String... classes) { this.unviewableWMClasses = classes; }

    public void setFpsWindowId(int id) { fpsWindowId = id; }
    public void setFrameRating(Object fr) {
        if (fr instanceof WinlatorHUD) {
            hudRef = (WinlatorHUD) fr;
            hudRef.setFrameGenerationSource(this);
            hudRef.setFrameGenSource(() -> tuningFrameGen ? getFrameGenStats() : null);
        } else if (fr instanceof FrameRating) {
            classicHudRef = (FrameRating) fr;
            classicHudRef.setFrameGenerationSource(this);
        }
    }
    public int getFpsLimit() {
        // This value feeds PresentExtension's X11/game pacer. LSFG and WFG must not
        // disable it; the Tuning generator caps the source to its own cadence.
        int cap = frameGenSourceCap;
        if (cap <= 0 || nativeFrameGenSourceUncapped()) return fpsLimit;
        return fpsLimit > 0 ? Math.min(fpsLimit, cap) : cap;
    }
    public void setFpsLimit(int limit) { this.fpsLimit = Math.max(0, Math.min(1000, limit)); }
    public void setPipMode(boolean pip) { inPipMode = pip; }
    public int getSurfaceWidth() { return surfaceWidth; }
    public int getSurfaceHeight() { return surfaceHeight; }
    public void requestRender() {}
}
