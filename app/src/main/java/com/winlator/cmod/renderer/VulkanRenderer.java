package com.winlator.cmod.renderer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Surface;
import android.widget.Toast;

import com.winlator.cmod.R;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.xenvironment.components.DirectCompositorComponent;
import com.winlator.cmod.xserver.Bitmask;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowAttributes;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.util.ArrayList;

public class VulkanRenderer implements WindowManager.OnWindowModificationListener,
                                       Pointer.OnPointerMotionListener {

    static { System.loadLibrary("vulkan_renderer"); }
    public static final int EFFECT_NONE = 0;
    public static final int EFFECT_FSR = 1;
    public static final int EFFECT_DLS = 2;
    public static final int EFFECT_CRT = 3;
    public static final int EFFECT_HDR = 4;
    public static final int EFFECT_NATURAL = 5;

    public final XServerView xServerView;
    private final XServer xServer;
    private long nativeHandle = 0;
    private final Object lock = new Object();

    public final ViewTransformation viewTransformation = new ViewTransformation();
    private boolean fullscreen = false;
    private float magnifierZoom = 1.0f;
    private boolean screenOffsetYRelativeToCursor = false;
    public int surfaceWidth;
    public int surfaceHeight;
    private String[] unviewableWMClasses = null;
    private boolean cursorVisible = false;
    private boolean nativeMode = false;
    private String driverPath = null;
    private java.util.concurrent.ExecutorService initExecutor = null;
    private volatile boolean initComplete = false;
    private String driverLibraryName = null;
    private String nativeLibDir = null;
    private Drawable rootCursorDrawable;
    private Cursor lastCursor = null;
    private boolean xRenderingPausedForScanout = false;

    private final java.util.concurrent.atomic.AtomicLong directFrameCount =
        new java.util.concurrent.atomic.AtomicLong(0);

    private volatile ArrayList<RenderableWindow> renderableWindows = new ArrayList<>();
    private static final java.util.concurrent.atomic.AtomicLong ID_GEN =
        new java.util.concurrent.atomic.AtomicLong(1);
    private final java.util.WeakHashMap<Drawable, Long> drawableIds =
        new java.util.WeakHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean scenePending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private android.view.SurfaceControl scanoutGameSC;
    private android.view.SurfaceControl scanoutCursorSC;
    private android.view.Surface        scanoutGameSurface;
    private android.view.Surface        scanoutCursorSurface;
    private volatile AHardwareBufferPool ahbPool = null;
    private volatile DirectCompositorComponent directCompositorRef = null;

    public VulkanRenderer(XServerView xServerView, XServer xServer) {
        this.xServerView = xServerView;
        this.xServer = xServer;
        rootCursorDrawable = createRootCursorDrawable();
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
    }

    private Drawable createRootCursorDrawable() {
        try {
            Context context = xServerView.getContext();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.cursor, options);
            return Drawable.fromBitmap(bitmap);
        } catch (Exception e) { return null; }
    }

    private native long nativeInit(Surface surface, int screenWidth, int screenHeight, String driverPath, String libraryName, String nativeLibDir);
    private native void nativeResize(long handle, int width, int height);
    private native void nativeDestroy(long handle);
    private native void nativeUpdateWindowContent(long handle, long id, java.nio.ByteBuffer pixels,
        short width, short height, short stride, int x, int y);
    private native void nativeUpdateWindowContentAHB(long handle, long id, long ahbPtr,
        short width, short height, int x, int y);
    private native void nativeSetTransform(long handle, float ox, float oy, float sx, float sy);
    private native void nativeSetPointerPos(long handle, short x, short y);
    private native void nativeSetCursorVisible(long handle, boolean visible);
    private native void nativeUpdateCursorImage(long handle, java.nio.ByteBuffer pixels,
        short width, short height, short hotX, short hotY);
    private native void nativeSetRenderList(long handle, long[] ids, int[] xs, int[] ys, int count);
    private native void nativeRemoveWindow(long handle, long id);

    private native void nativeInitScanout(long handle);
    private native void nativeDetachSurface(long handle);
    private native boolean nativeReattachSurface(long handle, android.view.Surface surface);
    private native void nativeDestroyScanout(long handle);
    private native void nativeScanoutSetBuffer(long handle, long ahbPtr, int acquireFenceFd, int x, int y, int w, int h);
    private native int[] nativePollReleaseFence(long handle);
    private native void nativeScanoutSetCursorImage(long handle, java.nio.ByteBuffer pixels, short w, short h, short stride);
    private native void nativeScanoutSetCursorPos(long handle, short x, short y, short hotX, short hotY);
    private native boolean nativeIsScanoutActive(long handle);
    private native boolean nativeIsGameFrameDelivered(long handle);
    private native long nativeGetDirectFrameCount(long handle);
    private native long nativeGetLatencyEmaUs(long handle);
    private native void nativeSetX11FrameT1(long handle, long tsUs);
    private native void nativeSetScanoutWindow(long handle, android.view.Surface game, android.view.Surface cursor);
    private native void nativeScanoutSetDst(long handle, int x, int y, int w, int h);
    private native void nativeStartPresentReceiver(long handle, int clientFd, long[] ahbPtrs, int screenWidth, int screenHeight);
    private native void nativeStartPresentReceiverWithSlots(long handle, int clientFd, long ahb0, long ahb1, long ahb2, long ahb3, int screenWidth, int screenHeight);
    private native void nativeStopPresentReceiver(long handle);
    private native void nativeSetVerboseLog(long handle, boolean v);
    private native void nativeDumpRendererInfo(long handle);
    private native void nativeSetFilterMode(long handle, int mode);
    private native void nativeSetSwapRB(long handle, boolean enabled);
    private native void nativeSetPresentMode(long handle, int mode);
    private native void nativeSetEffect(long handle, int effectId, float sharpness);

    private static volatile boolean gpuImageChecked = false;

    private long did(Drawable d) {
        return drawableIds.computeIfAbsent(d, k -> ID_GEN.getAndIncrement());
    }

    public void queueSceneUpdate() {
        if (scenePending.compareAndSet(false, true)) {
            xServerView.queueEvent(() -> {
                scenePending.set(false);
                updateScene();
            });
        }
    }

    public void onSurfaceCreated(Surface surface) {
        if (!gpuImageChecked) { GPUImage.checkIsSupported(); gpuImageChecked = true; }
        if (initExecutor != null) {
            initExecutor.shutdownNow();
            try { initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        initExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        initExecutor.execute(() -> {
            synchronized (lock) {
                if (nativeHandle != 0) {
                    boolean ok = nativeReattachSurface(nativeHandle, surface);
                    if (!ok) {
                        nativeDestroy(nativeHandle);
                        nativeHandle = 0;
                        // Signal pool reinit if in nativeMode
                        if (nativeMode) {
                            DirectCompositorComponent dcc = directCompositorRef;
                            if (dcc != null) {
                                xServerView.post(() -> {
                                    if (!dcc.reinitPool()) {
                                        android.util.Log.e("VulkanRenderer",
                                            "onSurfaceCreated: pool reinit failed after context loss");
                                    }
                                });
                            }
                        }
                    } else {
                        initComplete = true;
                        xServerView.queueEvent(this::updateScene);
                        // Recreate SC layers after successful reattach.
                        //
                        // Gate is "nativeMode OR DirectCompositor active": the
                        // recv-thread DAC pipeline (used by direct-render mode)
                        // bypasses VulkanRenderer.submitDirectFrame entirely
                        // (it calls renderer->scanoutSetBuffer from C++), so
                        // nativeMode stays false even when DAC frames are
                        // flowing. Without this second condition, locking and
                        // unlocking the device permanently freezes the game:
                        // nativeReattachSurface calls destroyScanout(),
                        // nulling scanoutGameSC; nothing then recreates it;
                        // every subsequent scanoutSetBuffer hits the SKIPPED
                        // path; SurfaceFlinger keeps showing the last frame
                        // (or black) while DXVK keeps drawing into AHB pool
                        // slots that never get displayed back.
                        DirectCompositorComponent dccActive = directCompositorRef;
                        boolean needScanoutRecreate = nativeMode
                                || (dccActive != null && dccActive.isActive());
                        if (needScanoutRecreate) {
                            xServerView.post(() -> {
                                releaseScanoutSurfaces();
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    try {
                                        android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                                        scanoutGameSC = new android.view.SurfaceControl.Builder()
                                            .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                                        scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                                        scanoutCursorSC = new android.view.SurfaceControl.Builder()
                                            .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                                        scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                                        new android.view.SurfaceControl.Transaction()
                                            .setLayer(scanoutGameSC,   1)
                                            .setLayer(scanoutCursorSC, 2)
                                            .setVisibility(scanoutGameSC,   true)
                                            .setVisibility(scanoutCursorSC, true)
                                            .apply();
                                        applyScanoutFrameRateHint();
                                        applyScanoutSwapTransform();
                                        synchronized (lock) {
                                            if (nativeHandle != 0) {
                                                nativeSetScanoutWindow(nativeHandle, scanoutGameSurface, scanoutCursorSurface);
                                                updateTransform();
                                            }
                                        }
                                    } catch (Exception e) {
                                        android.util.Log.w("VulkanRenderer", "SC recreate failed on reattach: " + e);
                                        synchronized (lock) {
                                            if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                                        }
                                        DirectCompositorComponent dcc = directCompositorRef;
                                        if (dcc != null) dcc.onScanoutFallback();
                                    }
                                } else {
                                    synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
                                }
                            });
                        }
                        return;
                    }
                }
                nativeHandle = nativeInit(surface, xServer.screenInfo.width, xServer.screenInfo.height, driverPath, driverLibraryName, nativeLibDir);
                if (nativeHandle != 0) {
                    nativeSetPresentMode(nativeHandle, pendingPresentMode);
                    nativeSetFilterMode(nativeHandle, pendingFilterMode);
                    nativeSetSwapRB(nativeHandle, pendingSwapRB);
                    nativeSetEffect(nativeHandle, pendingEffectId, pendingSharpness);
                    updateTransform();
                    nativeSetCursorVisible(nativeHandle, cursorVisible);
                    if (nativeMode) {
                        xServerView.post(() -> {
                            releaseScanoutSurfaces();
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                try {
                                    android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                                    scanoutGameSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                                    scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                                    scanoutCursorSC = new android.view.SurfaceControl.Builder()
                                        .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                                    scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                                    new android.view.SurfaceControl.Transaction()
                                        .setLayer(scanoutGameSC,   1)
                                        .setLayer(scanoutCursorSC, 2)
                                        .setVisibility(scanoutGameSC,   true)
                                        .setVisibility(scanoutCursorSC, true)
                                        .apply();
                                    applyScanoutFrameRateHint();
                                    applyScanoutSwapTransform();
                                    synchronized (lock) {
                                        if (nativeHandle != 0) {
                                            nativeSetScanoutWindow(nativeHandle, scanoutGameSurface, scanoutCursorSurface);
                                            updateTransform();
                                        }
                                    }
                                } catch (Exception e) {
                                    android.util.Log.w("VulkanRenderer", "SC recreate failed on surface restore: " + e);
                                    synchronized (lock) {
                                        if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                                    }
                                    DirectCompositorComponent dcc = directCompositorRef;
                                    if (dcc != null) dcc.onScanoutFallback();
                                }
                            } else {
                                synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
                            }
                        });
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
            xServerView.queueEvent(this::updateScene);
        });
    }

    public void onSurfaceChanged(int width, int height) {
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
            try { initExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            initExecutor = null;
        }
        synchronized (lock) {
            if (nativeHandle != 0) {
                if (nativeMode) {
                    nativeDetachSurface(nativeHandle);
                } else {
                    nativeDetachSurface(nativeHandle);
                }
            }
        }
        if (nativeMode) xServerView.post(this::releaseScanoutSurfaces);
    }

    private void releaseScanoutSurfaces() {
        // Step 1: Hide both layers atomically
        if (android.os.Build.VERSION.SDK_INT >= 29 && (scanoutGameSC != null || scanoutCursorSC != null)) {
            try {
                android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction();
                if (scanoutGameSC != null) txn.setVisibility(scanoutGameSC, false);
                if (scanoutCursorSC != null) txn.setVisibility(scanoutCursorSC, false);
                txn.apply();
            } catch (Exception e) {
                android.util.Log.w("VulkanRenderer", "releaseScanoutSurfaces: hide failed: " + e);
            }
        }
        // Step 2: Release Surface objects
        if (scanoutGameSurface   != null) { scanoutGameSurface.release();   scanoutGameSurface   = null; }
        if (scanoutCursorSurface != null) { scanoutCursorSurface.release(); scanoutCursorSurface = null; }
        // Step 3: Release SurfaceControl objects
        if (scanoutGameSC        != null) { scanoutGameSC.release();        scanoutGameSC        = null; }
        if (scanoutCursorSC      != null) { scanoutCursorSC.release();      scanoutCursorSC      = null; }
    }

    private void applyScanoutSwapTransform() {
        if (scanoutGameSC == null || android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction();
            float[] matrix = pendingSwapRB
                ? new float[]{0f, 0f, 1f, 0f, 1f, 0f, 1f, 0f, 0f}
                : new float[]{1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};
            float[] translation = new float[]{0f, 0f, 0f};
            java.lang.reflect.Method setColorTransform = android.view.SurfaceControl.Transaction.class.getMethod(
                "setColorTransform",
                android.view.SurfaceControl.class,
                float[].class,
                float[].class
            );
            setColorTransform.invoke(txn, scanoutGameSC, matrix, translation);
            txn.apply();
            txn.close();
        } catch (Exception e) {
            android.util.Log.w("VulkanRenderer", "Scanout color transform unavailable: " + e);
        }
    }

    private void updateTransform() {
        if (nativeHandle == 0) return;
        if (fullscreen) {
            nativeSetTransform(nativeHandle, 0, 0, 1.0f, 1.0f);
            viewTransformation.update(surfaceWidth, surfaceHeight,
                xServer.screenInfo.width, xServer.screenInfo.height);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        } else {
            float py = 0;
            if (screenOffsetYRelativeToCursor) {
                short halfH = (short)(xServer.screenInfo.height / 2);
                py = Math.max(0, Math.min(xServer.pointer.getY() - halfH / 2.0f, halfH));
            }
            nativeSetTransform(nativeHandle,
                viewTransformation.sceneOffsetX,
                viewTransformation.sceneOffsetY - py,
                viewTransformation.sceneScaleX,
                viewTransformation.sceneScaleY);
            nativeScanoutSetDst(nativeHandle,
                viewTransformation.viewOffsetX,
                viewTransformation.viewOffsetY,
                viewTransformation.viewWidth,
                viewTransformation.viewHeight);
        }
    }

    public void updateScene() {
        ArrayList<RenderableWindow> newList = new ArrayList<>();
        try (XLock xl = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            collectWindows(newList, xServer.windowManager.rootWindow,
                xServer.windowManager.rootWindow.getX(),
                xServer.windowManager.rootWindow.getY());
        }
        synchronized (lock) {
            renderableWindows = newList;
            pushRenderList(newList);
        }
    }

    private void collectWindows(ArrayList<RenderableWindow> list, Window window, int x, int y) {
        if (!window.attributes.isMapped()) return;
        if (window != xServer.windowManager.rootWindow) {
            boolean viewable = true;
            if (unviewableWMClasses != null) {
                String wc = window.getClassName();
                for (String cls : unviewableWMClasses) {
                    if (wc.contains(cls)) {
                        if (window.attributes.isEnabled()) window.disableAllDescendants();
                        viewable = false; break;
                    }
                }
            }
            if (viewable) list.add(new RenderableWindow(window.getContent(), x, y));
        }
        for (Window child : window.getChildren())
            collectWindows(list, child, child.getX() + x, child.getY() + y);
    }

    private void pushRenderList(ArrayList<RenderableWindow> list) {
        if (nativeHandle == 0) return;
        int screenW = xServer.screenInfo.width, screenH = xServer.screenInfo.height;

        int start = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            RenderableWindow rw = list.get(i);
            if (rw.content != null && rw.content.width >= screenW && rw.content.height >= screenH) {
                start = i; break;
            }
        }

        if (nativeMode) {
            ArrayList<RenderableWindow> ns = new ArrayList<>();
            for (int i = start; i < list.size(); i++) {
                RenderableWindow rw = list.get(i);
                if (rw.content != null && !rw.content.isDirectScanout()) ns.add(rw);
            }
            int n = ns.size();
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = did(ns.get(i).content); xs[i] = ns.get(i).rootX; ys[i] = ns.get(i).rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }
        if (fullscreen) {
            int n = list.size() - start;
            if (n <= 0) { nativeSetRenderList(nativeHandle, new long[0], new int[0], new int[0], 0); return; }
            long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                RenderableWindow rw = list.get(start + i);
                ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
            }
            nativeSetRenderList(nativeHandle, ids, xs, ys, n);
            return;
        }

        int n = list.size() - start;
        long[] ids = new long[n]; int[] xs = new int[n]; int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            RenderableWindow rw = list.get(start + i);
            ids[i] = did(rw.content); xs[i] = rw.rootX; ys[i] = rw.rootY;
        }
        nativeSetRenderList(nativeHandle, ids, xs, ys, n);
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
        if (effVis && cd != null && cd.getBuffer() != null) {
            synchronized (cd.renderLock) {
                nativeUpdateCursorImage(nativeHandle, cd.getBuffer(), cd.width, cd.height, hotX, hotY);
                if (nativeMode) {
                    java.nio.ByteBuffer buf = cd.getBuffer();
                    short stride = (short)(buf.capacity() / (cd.height * 4));
                    nativeScanoutSetCursorImage(nativeHandle, buf, cd.width, cd.height, stride);
                }
            }
        }
    }

    public void onUpdateWindowContentDirect(Window window, Drawable pixmap, short xOff, short yOff) {
        if (nativeHandle == 0 || pixmap == null) return;
        /* Native X11 latency T1: stamp arrival time of the Wine-delivered
         * frame. CAS-from-0 in JNI ignores additional same-frame updates;
         * renderFrame's T2 clears the stamp after each measurement.
         * System.nanoTime() on Android is CLOCK_MONOTONIC, matching the
         * clock used on the native side. */
        nativeSetX11FrameT1(nativeHandle, System.nanoTime() / 1000L);
        Drawable targetDrawable = window.getContent();
        long targetId = did(targetDrawable);
        int rx = window.getRootX() + xOff;
        int ry = window.getRootY() + yOff;
        synchronized (pixmap.renderLock) {
            Texture texture = pixmap.getTexture();
            if (texture instanceof GPUImage) {
                GPUImage g = (GPUImage) texture;
                long ahbPtr = g.getHardwareBufferPtr();
                if (ahbPtr != 0) {
                    if (nativeMode && pixmap.isDirectScanout() && nativeIsScanoutActive(nativeHandle)) {
                        g.unlock();
                        nativeScanoutSetBuffer(nativeHandle, ahbPtr, -1,
                            rx, ry, pixmap.width, pixmap.height);
                        drainReleaseFences(nativeHandle);
                        g.lock();
                        if (hudRef != null) {
                            hudRef.setIsNative(true);
                            hudRef.onFrame();
                        }
                    } else {
                        nativeUpdateWindowContentAHB(nativeHandle, targetId, ahbPtr,
                            pixmap.width, pixmap.height, rx, ry);
                        if (hudRef != null && !nativeMode) {
                            hudRef.setIsNative(false);
                            hudRef.onFrame();
                        }
                    }
                    return;
                }
                java.nio.ByteBuffer vd = g.getVirtualData();
                if (vd != null) {
                    short s = g.getStride() > 0 ? g.getStride() : pixmap.width;
                    nativeUpdateWindowContent(nativeHandle, targetId, vd,
                        pixmap.width, pixmap.height, s, rx, ry);
                    if (hudRef != null) {
                        hudRef.setIsNative(false);
                        hudRef.onFrame();
                    }
                    return;
                }
            }
            java.nio.ByteBuffer buf = pixmap.getBuffer();
            if (buf == null) return;
            short stride = (short)(buf.capacity() / (pixmap.height * 4));
            nativeUpdateWindowContent(nativeHandle, targetId, buf,
                pixmap.width, pixmap.height, stride, rx, ry);
            if (hudRef != null) {
                hudRef.setIsNative(false);
                hudRef.onFrame();
            }
        }
    }

    @Override
    public void onUpdateWindowContent(Window window) {
        if (hudRef != null) hudRef.update();
        final long handle;
        synchronized (lock) { handle = nativeHandle; }
        if (handle == 0) return;
        /* Native X11 latency T1 — see comment in onUpdateWindowContentDirect.
         * Stamps arrival of a fresh frame from Wine; cleared by renderFrame's
         * T2 right after vkQueuePresentKHR returns. */
        nativeSetX11FrameT1(handle, System.nanoTime() / 1000L);

        Drawable drawable = window.getContent();
        if (drawable == null || !window.attributes.isMapped()) return;
        if (unviewableWMClasses != null) {
            String wc = window.getClassName();
            for (String cls : unviewableWMClasses) if (wc.contains(cls)) return;
        }
        int rx = window.getRootX();
        int ry = window.getRootY();
        long drawableId = did(drawable);

        synchronized (drawable.renderLock) {
            if (drawable.getTexture() instanceof GPUImage) {
                GPUImage g = (GPUImage) drawable.getTexture();
                long ahbPtr = g.getHardwareBufferPtr();
                if (ahbPtr != 0) {
                    boolean scanoutNow = nativeMode && nativeIsScanoutActive(handle);
                    if (nativeMode && drawable.isDirectScanout() && scanoutNow) {
                        boolean wasDelivered = nativeIsGameFrameDelivered(handle);
                        g.unlock();
                        nativeScanoutSetBuffer(handle, ahbPtr, -1,
                            rx, ry, drawable.width, drawable.height);
                        drainReleaseFences(handle);
                        g.lock();
                        boolean delivered = nativeIsGameFrameDelivered(handle);
                        directFrameCount.incrementAndGet();
                        if (!xRenderingPausedForScanout && !wasDelivered && delivered) {
                            android.util.Log.i("VulkanRenderer", "VulkanRenderer: first scanout frame delivered");
                            xServer.setRenderingEnabled(false);
                            xRenderingPausedForScanout = true;
                        }
                        if (hudRef != null) {
                            hudRef.onFrame();
                            hudRef.setIsNative(delivered);
                        }
                    } else if (!scanoutNow) {
                        nativeUpdateWindowContentAHB(handle, drawableId, ahbPtr,
                            drawable.width, drawable.height, rx, ry);
                        if (hudRef != null) {
                            hudRef.setIsNative(false);
                            hudRef.onFrame();
                        }
                    }
                    return;
                }
                java.nio.ByteBuffer vd = g.getVirtualData();
                if (vd != null) {
                    short s = g.getStride() > 0 ? g.getStride() : drawable.width;
                    nativeUpdateWindowContent(handle, drawableId, vd,
                        drawable.width, drawable.height, s, rx, ry);
                    if (hudRef != null) {
                        hudRef.setIsNative(false);
                        hudRef.onFrame();
                    }
                    return;
                }
            }
            java.nio.ByteBuffer buf = drawable.getBuffer();
            if (buf == null) return;
            short stride = (short)(buf.capacity() / (drawable.height * 4));
            nativeUpdateWindowContent(handle, drawableId, buf,
                drawable.width, drawable.height, stride, rx, ry);
            if (hudRef != null) {
                hudRef.setIsNative(false);
                hudRef.onFrame();
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
            if (cursor != lastCursor) { lastCursor = cursor; sendCursorToNative(cursor); }
            if (nativeMode) {
                short hotX = 0, hotY = 0;
                if (cursor != null) { hotX = (short)cursor.hotSpotX; hotY = (short)cursor.hotSpotY; }
                nativeScanoutSetCursorPos(nativeHandle, x, y, hotX, hotY);
            }
            if (screenOffsetYRelativeToCursor) updateTransform();
        }
    }

    @Override
    public void onDestroyWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) { if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id); }
            queueSceneUpdate();
        });
    }

    @Override public void onMapWindow(Window window) { queueSceneUpdate(); }

    @Override
    public void onUnmapWindow(Window window) {
        final long id = did(window.getContent());
        xServerView.queueEvent(() -> {
            synchronized (lock) { if (nativeHandle != 0) nativeRemoveWindow(nativeHandle, id); }
            queueSceneUpdate();
        });
    }

    @Override public void onChangeWindowZOrder(Window window) { queueSceneUpdate(); }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        queueSceneUpdate();
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

    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        synchronized (lock) {
            if (nativeHandle != 0) {
                nativeSetCursorVisible(nativeHandle, visible);
                if (visible) sendCursorToNative(lastCursor);
            }
        }
        // Req 7.4, 7.5: when nativeMode is active, also toggle the cursor SurfaceControl layer
        // visibility so SurfaceFlinger hides/shows the cursor without going through the GL path.
        if (nativeMode && android.os.Build.VERSION.SDK_INT >= 29) {
            xServerView.post(() -> {
                android.view.SurfaceControl sc = scanoutCursorSC;
                if (sc == null) return;
                try {
                    new android.view.SurfaceControl.Transaction()
                        .setVisibility(sc, visible)
                        .apply();
                } catch (Exception e) {
                    android.util.Log.w("VulkanRenderer",
                        "setCursorVisible: SurfaceControl.Transaction failed: " + e);
                }
            });
        }
    }

    public boolean isCursorVisible() { return cursorVisible; }

    /**
     * Updates only the visible cursor position in the renderer.
     * This is useful for relative mouse mode where movement is forwarded to Wine
     * without necessarily changing the XServer pointer state.
     */
    public void updateVisualCursorPosition(int x, int y) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeSetPointerPos(nativeHandle, (short) x, (short) y);
        }
    }

    public void setNativeMode(boolean mode) {
        if (this.nativeMode == mode) return;
        this.nativeMode = mode;
        xRenderingPausedForScanout = false;
        if (mode) {
            xServer.setRenderingEnabled(true);
            xServerView.post(() -> {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try {
                        android.view.SurfaceControl xsc = xServerView.getSurfaceControl();
                        scanoutGameSC = new android.view.SurfaceControl.Builder()
                            .setParent(xsc).setName("winlator_game").setOpaque(true).build();
                        scanoutGameSurface = new android.view.Surface(scanoutGameSC);
                        scanoutCursorSC = new android.view.SurfaceControl.Builder()
                            .setParent(xsc).setName("winlator_cursor").setFormat(1).build();
                        scanoutCursorSurface = new android.view.Surface(scanoutCursorSC);
                        android.view.SurfaceControl.Transaction scTxn =
                            new android.view.SurfaceControl.Transaction()
                            .setLayer(scanoutGameSC,   1)
                            .setLayer(scanoutCursorSC, 2)
                            .setVisibility(scanoutGameSC,   true)
                            .setVisibility(scanoutCursorSC, true);
                        scTxn.apply();
                        applyScanoutFrameRateHint();
                        applyScanoutSwapTransform();
                        synchronized (lock) {
                            if (nativeHandle != 0) {
                                nativeSetScanoutWindow(nativeHandle,
                                    scanoutGameSurface, scanoutCursorSurface);
                                updateTransform();
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.w("VulkanRenderer", "SurfaceControl creation failed, falling back to XServer path: " + e);
                        synchronized (lock) {
                            if (nativeHandle != 0) nativeInitScanout(nativeHandle);
                        }
                        DirectCompositorComponent dcc = directCompositorRef;
                        if (dcc != null) dcc.onScanoutFallback();
                    }
                } else {
                    synchronized (lock) { if (nativeHandle != 0) nativeInitScanout(nativeHandle); }
                }
            });
        } else {
            synchronized (lock) {
                if (nativeHandle != 0) nativeDestroyScanout(nativeHandle);
            }
            xServerView.post(() -> {
                xServer.setRenderingEnabled(true);
                releaseScanoutSurfaces();
            });
        }
        if (hudRef != null) hudRef.setIsNative(mode && nativeHandle != 0 && nativeIsGameFrameDelivered(nativeHandle));
        xServerView.queueEvent(this::updateScene);
        final String msg = mode ? "Native Rendering+ Enabled" : "Native Rendering+ Disabled";
        xServerView.post(() -> Toast.makeText(xServerView.getContext(), msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * Detaches SurfaceControl layers from the display by hiding them, without
     * destroying the layers or the AHB pool. Called by DirectCompositorComponent.onPause().
     * Wine naturally blocks in acquire() since no release fences arrive while paused.
     *
     * <p>Requirements: 9.1
     */
    public void detachScanoutLayers() {
        if (android.os.Build.VERSION.SDK_INT >= 29 && (scanoutGameSC != null || scanoutCursorSC != null)) {
            xServerView.post(() -> {
                try {
                    android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction();
                    if (scanoutGameSC != null) txn.setVisibility(scanoutGameSC, false);
                    if (scanoutCursorSC != null) txn.setVisibility(scanoutCursorSC, false);
                    txn.apply();
                } catch (Exception e) {
                    android.util.Log.w("VulkanRenderer", "detachScanoutLayers: hide failed: " + e);
                }
            });
        }
    }

    /**
     * Reattaches SurfaceControl layers by making them visible again and resuming
     * frame submission. Called by DirectCompositorComponent.onResume().
     * The destination rectangle is updated to match the current ViewTransformation.
     *
     * <p>Requirements: 9.2, 9.6
     */
    public void reattachScanoutLayers() {
        if (android.os.Build.VERSION.SDK_INT >= 29 && (scanoutGameSC != null || scanoutCursorSC != null)) {
            xServerView.post(() -> {
                try {
                    android.view.SurfaceControl.Transaction txn = new android.view.SurfaceControl.Transaction();
                    if (scanoutGameSC != null) txn.setVisibility(scanoutGameSC, true);
                    if (scanoutCursorSC != null) txn.setVisibility(scanoutCursorSC, true);
                    txn.apply();
                    // Update destination rect to match current screen geometry after resume
                    synchronized (lock) {
                        if (nativeHandle != 0) updateTransform();
                    }
                } catch (Exception e) {
                    android.util.Log.w("VulkanRenderer", "reattachScanoutLayers: show failed: " + e);
                }
            });
        }
    }

    /**
     * Drains the native release fence queue and forwards each pending release
     * to the AHardwareBufferPool. Called after each nativeScanoutSetBuffer.
     */
    private void drainReleaseFences(long handle) {
        AHardwareBufferPool pool = ahbPool;
        if (pool == null) return;
        int[] pending;
        while ((pending = nativePollReleaseFence(handle)) != null) {
            if (pending.length >= 2) {
                pool.release(pending[0], pending[1]);
            }
        }
    }

    /**
     * Sets the AHardwareBufferPool to use for release fence forwarding.
     * Called by DirectCompositorComponent when the pool is initialized.
     * Pass null to clear the reference when the pool is destroyed.
     */
    public void setAHBPool(AHardwareBufferPool pool) {
        this.ahbPool = pool;
    }

    /**
     * Sets the DirectCompositorComponent reference for fallback signaling.
     * Called during component wiring so VulkanRenderer can notify the compositor
     * when SurfaceControl creation fails or context is lost.
     */
    public void setDirectCompositor(DirectCompositorComponent dcc) {
        this.directCompositorRef = dcc;
    }

    /**
     * Submits an AHardwareBuffer frame directly to SurfaceFlinger via SurfaceControl.
     * Called by AHBSocketServerComponent when Wine presents a frame.
     *
     * @param ahbPtr the AHardwareBuffer pointer to display
     * @param acquireFenceFd the acquire fence fd (-1 if none)
     */
    public void submitDirectFrame(long ahbPtr, int acquireFenceFd) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            if (!nativeMode) {
                // Activate nativeMode on first direct frame
                setNativeMode(true);
                android.util.Log.i("VulkanRenderer",
                    "VulkanRenderer: nativeMode enabled by first direct frame");
            }
            nativeScanoutSetBuffer(nativeHandle, ahbPtr, acquireFenceFd,
                0, 0, xServer.screenInfo.width, xServer.screenInfo.height);
            drainReleaseFences(nativeHandle);
            directFrameCount.incrementAndGet();
        }
    }

    /**
     * Starts the native present-receiver thread that reads present_msg from the
     * Wine client socket and calls scanoutSetBuffer directly in C++.
     * This bypasses XConnectorEpoll for the hot path.
     *
     * @param clientFd the socket fd connected to Wine's Vulkan WSI
     * @param ahb0 AHardwareBuffer pointer for slot 0
     * @param ahb1 AHardwareBuffer pointer for slot 1
     * @param ahb2 AHardwareBuffer pointer for slot 2
     * @param screenWidth game screen width
     * @param screenHeight game screen height
     */
    public void startPresentReceiver(int clientFd, long ahb0, long ahb1, long ahb2, long ahb3,
                                     int screenWidth, int screenHeight) {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            // Do NOT enable nativeMode here — nativeMode is activated lazily by
            // submitDirectFrame() when the FIRST actual AHB frame is delivered.
            // Enabling it here would disable X11 display even when the AHB swapchain
            // creation fails (e.g., dispatch patching couldn't intercept vkCreateSwapchainKHR),
            // causing a permanent black screen with no fallback.
            nativeStartPresentReceiverWithSlots(nativeHandle, clientFd,
                ahb0, ahb1, ahb2, ahb3, screenWidth, screenHeight);
        }
    }

    /**
     * Stops the native present-receiver thread.
     */
    public void stopPresentReceiver() {
        synchronized (lock) {
            if (nativeHandle == 0) return;
            nativeStopPresentReceiver(nativeHandle);
        }
    }

    public boolean isNativeMode() { return nativeMode; }

    /**
     * Called by the activity/container setup code before the surface is created.
     * Activates nativeMode if the driver is Vulkan-based (dxvk or vkd3d).
     * Req 6.1, 6.2, 6.6, 10.1
     */
    public void setGraphicsDriver(String graphicsDriver) {
        // nativeMode is now controlled exclusively by DirectCompositorComponent.
        // This method is kept for API compatibility and logging only.
        android.util.Log.d("VulkanRenderer",
            "VulkanRenderer: setGraphicsDriver called with driver=" + graphicsDriver
            + " (nativeMode controlled by DirectCompositorComponent)");
    }

    public void setDriverInfo(String driverPath, String libraryName, String nativeLibDir) {
        this.driverPath = driverPath;
        this.driverLibraryName = libraryName;
        this.nativeLibDir = nativeLibDir;
        android.util.Log.d("Winlator_Renderer",
            "setDriverInfo: path=" + driverPath + " lib=" + libraryName);
    }

    public void setVerboseLog(boolean v) {
        synchronized (lock) { if (nativeHandle != 0) nativeSetVerboseLog(nativeHandle, v); }
    }

    public void dumpRendererInfo() {
        synchronized (lock) {
            if (nativeHandle != 0) nativeDumpRendererInfo(nativeHandle);
        }
        // Req 10.5: log nativeMode, pool buffer count, and directFrameCount
        AHardwareBufferPool pool = ahbPool;
        int poolCount = (pool != null) ? pool.getCount() : 0;
        android.util.Log.i("VulkanRenderer",
            "VulkanRenderer: nativeMode=" + nativeMode
            + " poolBufferCount=" + poolCount
            + " directFrameCount=" + directFrameCount.get());
    }



    public void setFilterMode(int mode) {
        pendingFilterMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetFilterMode(nativeHandle, mode); }
    }

    public void setSwapRB(boolean enabled) {
        pendingSwapRB = enabled;
        synchronized (lock) { if (nativeHandle != 0) nativeSetSwapRB(nativeHandle, enabled); }
    }

    public void setEffect(int effectId, float sharpness) {
        pendingEffectId = Math.max(EFFECT_NONE, Math.min(EFFECT_NATURAL, effectId));
        pendingSharpness = Math.max(0.0f, Math.min(1.0f, sharpness));
        synchronized (lock) {
            if (nativeHandle != 0) nativeSetEffect(nativeHandle, pendingEffectId, pendingSharpness);
        }
    }
    public int getEffectId() { return pendingEffectId; }
    public float getSharpness() { return pendingSharpness; }




    public void setVkPresentMode(int mode) {
        pendingPresentMode = mode;
        synchronized (lock) { if (nativeHandle != 0) nativeSetPresentMode(nativeHandle, mode); }
    }



    private WinlatorHUD hudRef = null;

    public void setFrameRating(Object fr) {
        if (fr instanceof WinlatorHUD) {
            hudRef = (WinlatorHUD) fr;
            /* Wire a supplier so the HUD can read the DAC frame counter directly.
             * Used when nativeMode is active — the X11 onUpdateWindowContent path
             * that normally drives hud.onFrame() is disabled by
             * xServer.setRenderingEnabled(false) after the first DAC frame, so
             * the HUD's frameAccum stays at 0. */
            hudRef.setNativeFrameCountSupplier(() -> {
                final long h;
                synchronized (lock) { h = nativeHandle; }
                return h != 0 ? nativeGetDirectFrameCount(h) : 0L;
            });
            /* Compositor-latency supplier: feeds the LAT row when the
             * user enables SHOW_LATENCY via the sidebar checkbox. Returns
             * 0 when the DAC pipeline isn't running (Native X11 mode), in
             * which case the HUD renders "—" instead of a value. */
            hudRef.setLatencySupplier(() -> {
                final long h;
                synchronized (lock) { h = nativeHandle; }
                return h != 0 ? nativeGetLatencyEmaUs(h) : 0L;
            });
        }
    }

    public boolean isFullscreen() { return fullscreen; }
    public void toggleFullscreen() { fullscreen = !fullscreen; synchronized (lock) { updateTransform(); } xServerView.queueEvent(this::updateScene); }
    public void setScreenOffsetYRelativeToCursor(boolean b) { screenOffsetYRelativeToCursor = b; synchronized (lock) { updateTransform(); } }
    public boolean isScreenOffsetYRelativeToCursor() { return screenOffsetYRelativeToCursor; }
    public void setMagnifierZoom(float zoom) { magnifierZoom = zoom; }
    public float getMagnifierZoom() { return magnifierZoom; }
    public void setUnviewableWMClasses(String... classes) { this.unviewableWMClasses = classes; }
    private int fpsLimit = 0;
    private int refreshRateLimit = 60;
    private int     pendingPresentMode    = 1;
    private int     pendingFilterMode     = 0;
    private boolean pendingSwapRB         = false;
    private int     pendingEffectId       = EFFECT_NONE;
    private float   pendingSharpness      = 1.0f;
    public int getFpsLimit() { return fpsLimit; }
    public void setFpsLimit(int limit) {
        this.fpsLimit = limit;
        if (android.os.Build.VERSION.SDK_INT >= 30 && scanoutGameSC != null) {
            float targetFps = limit > 0 ? (float)limit
                : xServerView.getDisplay() != null
                    ? xServerView.getDisplay().getRefreshRate() : 60f;
            new android.view.SurfaceControl.Transaction()
                .setFrameRate(scanoutGameSC, targetFps,
                    android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                .apply();
        }
    }
    public int getRefreshRateLimit() { return refreshRateLimit; }
    public void setRefreshRateLimit(int limit) {
        this.refreshRateLimit = limit > 0 ? limit : 0;
        applyScanoutFrameRateHint();
    }

    private void applyScanoutFrameRateHint() {
        if (android.os.Build.VERSION.SDK_INT < 30 || scanoutGameSC == null) return;
        float targetFps = refreshRateLimit > 0 ? (float)refreshRateLimit
            : xServerView.getDisplay() != null
                ? xServerView.getDisplay().getRefreshRate() : 60f;
        new android.view.SurfaceControl.Transaction()
            .setFrameRate(scanoutGameSC, targetFps,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            .apply();
    }
    private static class RenderableWindow {
        public final Drawable content; public int rootX, rootY;
        public RenderableWindow(Drawable c, int x, int y) { content=c; rootX=x; rootY=y; }
    }
}
