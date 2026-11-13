package com.winlator.cmod.widget;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.FrameLayout;
import com.winlator.cmod.renderer.GPUImage;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XServer;
import java.io.File;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.lsfg.LosslessScaling;

/** Android surface and input adapter. X11 clients, resources, DRI3 and Present live in C++. */
public final class NativeX11View extends XServerRendererView implements SurfaceHolder.Callback,
        Pointer.OnPointerMotionListener, Keyboard.OnKeyboardListener, VulkanRendererControls {
    private long handle;
    private final int backend;
    private boolean fullscreen, cursorVisible, destroyed;
    private int fpsLimit;
    private volatile float zoom = 1f;
    private int filterMode, postFXMode, stretchMode;
    private float sharpness = .5f;
    private boolean frameGenRequested, frameGenEnabled, paused;
    private String frameGenCache;
    private String frameGenEngine = "lsfg";
    private int frameGenMultiplier = 2, frameGenTargetRate = -1, frameGenFlowScale = 70;
    private float frameGenRefresh = 60f;
    private long visualRevision, generatedFrames;
    private Runnable frameGenListener;
    private final Runnable frameGenCheck = new Runnable() {
        @Override public void run() {
            synchronized (NativeX11View.this) {
                if (destroyed || paused || handle == 0) return;
                long[] state = nativeVisualState(handle);
                boolean pending = state == null || state[0] < visualRevision || (state[1] & 1) == 0;
                if (!pending) {
                    boolean enabled = (state[1] & 4) != 0;
                    generatedFrames = state[2];
                    boolean rejected = frameGenRequested && !enabled;
                    if (rejected) {
                        frameGenRequested = false;
                        applyVisualOptions();
                        pending = true;
                    }
                    if (frameGenEnabled != enabled || rejected) {
                        frameGenEnabled = enabled;
                        notifyFrameGenerationStateChanged();
                    }
                }
                // No status timer when FG is off. Enabled sessions also detect
                // an engine fallback without a Java callback on every frame.
                if (pending || frameGenRequested || frameGenEnabled) postDelayed(this, 100L);
            }
        }
    };
    private Runnable readyCallback;
    private boolean ready;
    private boolean started;
    private final boolean highRefreshRate;
    private final DisplayManager displayManager;
    private boolean displayListenerRegistered, diagnosticsEnabled;
    private Surface votedSurface;
    private float votedRate, reportedRefresh = -1f, reportedRequest = -1f;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override public void onDisplayChanged(int id) {
            synchronized (NativeX11View.this) {
                Display display = getDisplay();
                if (!destroyed && display != null && display.getDisplayId() == id) updateOptions();
            }
        }
    };
    private FrameRating classicHud;
    private WinlatorHUD modernHud;
    private int hudMode;
    private long hudWindow, hudFrames, hudTime;
    private String hudRenderer = "", hudGpu = "";
    private final Runnable hudCheck = new Runnable() {
        @Override public void run() {
            synchronized (NativeX11View.this) {
                if (destroyed || handle == 0 || hudMode == 0) return;
                String[] sample = nativeHudSnapshot(handle);
                if (sample != null && sample.length == 4) {
                    long window = Long.parseLong(sample[0]);
                    long frames = Long.parseLong(sample[1]);
                    long now = android.os.SystemClock.elapsedRealtime();
                    boolean changed = window != hudWindow;
                    if (window != 0) {
                        if (changed || !hudRenderer.equals(sample[2]) || !hudGpu.equals(sample[3])) {
                            if (hudMode == 2 && modernHud != null) {
                                modernHud.onRendererDetected(sample[2].isEmpty() ? ((com.winlator.cmod.XServerDisplayActivity)getContext()).getHudGraphicsFallback() : sample[2]);
                                if (!sample[3].isEmpty()) modernHud.setGpuName(sample[3]);
                            }
                            if (hudMode == 1 && classicHud != null) {
                                classicHud.setVisibility(VISIBLE);
                                classicHud.setRenderer(sample[2].isEmpty() ? ((com.winlator.cmod.XServerDisplayActivity)getContext()).getHudGraphicsFallback() : sample[2]);
                                if (!sample[3].isEmpty()) classicHud.setGpuName(sample[3]);
                            }
                        }
                        if (!changed && hudTime != 0 && frames >= hudFrames) {
                            long delta = frames - hudFrames;
                            if (hudMode == 2 && modernHud != null) modernHud.onNativeFrames(delta);
                            if (hudMode == 1 && classicHud != null) classicHud.updateNativeFrames(delta, now - hudTime);
                        }
                    } else if (hudWindow != 0) {
                        if (modernHud != null) modernHud.onRendererGone();
                        if (classicHud != null) { classicHud.setVisibility(GONE); classicHud.reset(); }
                    }
                    hudWindow = window; hudFrames = frames; hudTime = now;
                    hudRenderer = sample[2]; hudGpu = sample[3];
                }
            }
            postDelayed(this, 100L);
        }
    };
    private final Runnable startupCheck = new Runnable() {
        @Override public void run() {
            synchronized (NativeX11View.this) {
                if (destroyed || handle == 0 || ready) return;
                String error = nativeError(handle);
                if (!error.isEmpty()) {
                    android.util.Log.e("LudashiX11", error);
                    android.widget.Toast.makeText(getContext(), "Native X11: " + error,
                            android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                if (started) {
                    ready = true;
                    if (readyCallback != null) readyCallback.run();
                    return;
                }
            }
            postDelayed(this, 100L);
        }
    };
    static { System.loadLibrary("ludashix11"); }
    public NativeX11View(Context context, XServer inputProxy) {
        this(context, inputProxy, false);
    }
    public NativeX11View(Context context, XServer inputProxy, boolean validationEnabled) {
        this(context, inputProxy, validationEnabled, "", "", "", 2, false, 0, 6);
    }
    public NativeX11View(Context context, XServer inputProxy, boolean validationEnabled,
                         String driverPath, String libraryName, String nativeLibDir, int presentMode, boolean swapRB, int backend, int displayXFlags) {
        super(context, inputProxy);
        this.backend = backend == 1 || backend == 2 ? backend : 0;
        highRefreshRate = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("high_refresh_rate_mode", false);
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        handle = nativeCreate(inputProxy.screenInfo.width, inputProxy.screenInfo.height, validationEnabled,
                driverPath, libraryName, nativeLibDir, presentMode, swapRB, this.backend, displayXFlags);
        setRawInputEnabled(!inputProxy.isRelativeMouseMovement());
        nativeWindowListener(handle, this);
        inputProxy.setXServerView(this);
        inputProxy.cursorLocker.setEnabled(false);
        inputProxy.pointer.addOnPointerMotionListener(this);
        inputProxy.keyboard.addOnKeyboardListener(this);
        setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        getHolder().addCallback(this);
        updateOptions();
    }
    private Object displayTimeline;
    @androidx.annotation.Keep
    private void onNativeWindowMapped(int pid, String className, boolean wow64) {
        post(() -> {
            if (!destroyed && getContext() instanceof com.winlator.cmod.XServerDisplayActivity)
                ((com.winlator.cmod.XServerDisplayActivity)getContext()).assignNativeTaskAffinity(pid, className, wow64);
        });
    }
    private static native void nativeWindowListener(long handle, NativeX11View view);
    private void startDisplayTimeline() {
        if (backend == 2 && !paused && !destroyed && displayTimeline == null && Build.VERSION.SDK_INT >= 33)
            displayTimeline = new DisplayTimeline33(this);
    }
    private void stopDisplayTimeline() {
        if (displayTimeline != null && Build.VERSION.SDK_INT >= 33) {
            ((DisplayTimeline33) displayTimeline).stop();
            displayTimeline = null;
        }
        if (handle != 0) nativeFrameTimeline(handle, -1);
    }
    @androidx.annotation.RequiresApi(33)
    private static final class DisplayTimeline33 implements android.view.Choreographer.VsyncCallback {
        final NativeX11View view;
        final android.view.Choreographer choreographer = android.view.Choreographer.getInstance();
        boolean running = true;
        DisplayTimeline33(NativeX11View view) { this.view = view; choreographer.postVsyncCallback(this); }
        public void onVsync(android.view.Choreographer.FrameData frame) {
            synchronized (view) {
                if (!running || view.destroyed || view.paused || view.handle == 0) return;
                nativeFrameTimeline(view.handle, frame.getPreferredFrameTimeline().getVsyncId());
                choreographer.postVsyncCallback(this);
            }
        }
        void stop() { running = false; choreographer.removeVsyncCallback(this); }
    }

    public boolean isVulkanBackend() { return backend == 0; }
    public String getBackendName() { return backend == 2 ? "DisplayX" : backend == 1 ? "EGL" : "Vulkan"; }
    public synchronized void setReadyCallback(Runnable callback) { readyCallback = callback; if (ready) callback.run(); }
    public synchronized void setDiagnosticsEnabled(boolean enabled) {
        setDiagnosticsEnabled(enabled, true);
    }
    public synchronized void setDiagnosticsEnabled(boolean enabled, boolean commandCacheEnabled) {
        diagnosticsEnabled = enabled;
        if (enabled) reportedRefresh = reportedRequest = -1f;
        if (handle != 0) nativeDiagnostics(handle, enabled, commandCacheEnabled);
    }
    private synchronized void startDisplayListener() {
        if (!destroyed && !displayListenerRegistered && displayManager != null && isAttachedToWindow()) {
            displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
            displayListenerRegistered = true;
        }
    }
    private synchronized void stopDisplayListener() {
        if (displayListenerRegistered && displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayListenerRegistered = false;
        }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startDisplayListener();
        updateOptions();
    }
    @Override protected void onDetachedFromWindow() {
        stopDisplayListener();
        super.onDetachedFromWindow();
    }
    public synchronized void start(String socket, String shm) {
        if (handle == 0 || destroyed) throw new IllegalStateException("Native X11 session was closed");
        File parent = new File(socket).getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IllegalStateException("Cannot create native X11 socket directory");
        nativeStart(handle, socket, shm);
        started = true;
        post(startupCheck);
    }
    private synchronized void updateOptions() {
        if (handle == 0) return;
        Display display = getDisplay();
        float refresh = display != null ? display.getRefreshRate() : 60f;
        float requested = 0f;
        java.util.TreeSet<Integer> rates = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        if (display != null) {
            Display.Mode current = display.getMode();
            for (Display.Mode mode : display.getSupportedModes()) {
                rates.add(Math.round(mode.getRefreshRate()));
                if (highRefreshRate && mode.getPhysicalWidth() == current.getPhysicalWidth() &&
                        mode.getPhysicalHeight() == current.getPhysicalHeight())
                    requested = Math.max(requested, mode.getRefreshRate());
            }
        }
        // Honor the existing High refresh rate mode setting. With it disabled,
        // leave the Surface vote to Android; virtual RandR rates stay independent.
        Surface surface = getHolder().getSurface();
        if (requested > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                surface != null && surface.isValid() && (surface != votedSurface || requested != votedRate)) {
            try {
                surface.setFrameRate(requested, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                votedSurface = surface; votedRate = requested;
            } catch (IllegalArgumentException | IllegalStateException error) {
                android.util.Log.w("LudashiX11", "Surface refresh-rate vote failed", error);
            }
        }
        if (diagnosticsEnabled && (refresh != reportedRefresh || requested != reportedRequest)) {
            android.util.Log.i("LudashiX11", "X11Refresh active=" + refresh + " requested=" + requested
                    + " high_refresh=" + highRefreshRate);
            reportedRefresh = refresh; reportedRequest = requested;
        }
        if (rates.isEmpty()) rates.add(Math.round(refresh));
        int[] supported = new int[rates.size()];
        int index = 0;
        for (int hz : rates) supported[index++] = hz;
        nativeRefreshRates(handle, supported);
        nativeOptions(handle, fullscreen, cursorVisible, fpsLimit, refresh);
        if (frameGenRefresh != refresh) {
            frameGenRefresh = refresh;
            if (frameGenRequested) applyVisualOptions();
        }
    }
    @Override public synchronized void surfaceCreated(SurfaceHolder holder) { updateOptions(); startDisplayTimeline(); }
    @Override public synchronized void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        viewTransformation.update(w, h, xServer.screenInfo.width, xServer.screenInfo.height);
        if (handle != 0) nativeSurface(handle, holder.getSurface());
        updateOptions();
    }
    @Override public synchronized void surfaceDestroyed(SurfaceHolder holder) {
        stopDisplayTimeline();
        votedSurface = null; votedRate = 0f;
        if (handle != 0) nativeSurface(handle, null);
    }
    @Override public synchronized void onPause() {
        paused = true;
        stopDisplayTimeline();
        stopDisplayListener();
        removeCallbacks(hudCheck);
        removeCallbacks(frameGenCheck);
        if (handle != 0) nativeHudEnabled(handle, false);
    }
    @Override public synchronized void onResume() {
        paused = false;
        startDisplayTimeline();
        startDisplayListener(); updateOptions(); setHudMode(hudMode);
        if (frameGenRequested || frameGenEnabled) { removeCallbacks(frameGenCheck); post(frameGenCheck); }
    }
    @Override public void onDestroy() { close(); }
    public synchronized void close() {
        if (destroyed) return;
        destroyed = true;
        stopDisplayTimeline();
        stopDisplayListener();
        removeCallbacks(startupCheck);
        removeCallbacks(hudCheck);
        removeCallbacks(frameGenCheck);
        frameGenListener = null;
        frameGenEnabled = false;
        xServer.pointer.removeOnPointerMotionListener(this);
        xServer.keyboard.removeOnKeyboardListener(this);
        long old = handle; handle = 0;
        if (old != 0) nativeDestroy(old);
    }
    @Override public void forceCleanup() { close(); }
    @Override public synchronized void onPointerMove(short x, short y) {
        if (handle != 0) nativeInput(handle, 0, x, y);
    }
    public synchronized void moveRelative(int dx, int dy) {
        if (handle != 0) nativeInput(handle, 3, dx, dy);
    }
    public synchronized void warpPointer(int x, int y) {
        if (handle != 0) nativeInput(handle, 4, x, y);
    }
    public synchronized void setRawInputEnabled(boolean enabled) {
        if (handle != 0) nativeInput(handle, 5, enabled ? 1 : 0, 0);
    }
    public synchronized long getFocusedWindowHandle() { return handle != 0 ? nativeFocusHandle(handle) : 0; }
    public synchronized boolean hasWindowHandle(long hwnd) { return handle != 0 && nativeHasWindow(handle, hwnd); }
    @Override public synchronized void onPointerButtonPress(Pointer.Button button) { if (handle != 0) nativeInput(handle, 1, button.code(), 1); }
    @Override public synchronized void onPointerButtonRelease(Pointer.Button button) { if (handle != 0) nativeInput(handle, 1, button.code(), 0); }
    @Override public synchronized void onKeyPress(byte code, int keysym) { if (handle != 0) nativeInput(handle, 2, code & 255, 1); }
    @Override public synchronized void onKeyRelease(byte code) { if (handle != 0) nativeInput(handle, 2, code & 255, 0); }
    @Override public void toggleFullscreen() { fullscreen = !fullscreen; updateOptions(); }
    @Override public boolean isFullscreen() { return fullscreen; }
    @Override public void setCursorVisible(boolean value) { cursorVisible = value; updateOptions(); }
    @Override public void setFpsLimit(int value) { fpsLimit = Math.max(0, value); updateOptions(); }
    @Override public int getFpsLimit() { return fpsLimit; }
    @Override public synchronized void setMagnifierZoom(float value) {
        zoom = Float.isFinite(value) ? Math.max(1f, Math.min(3f, value)) : 1f;
        if (handle != 0) nativeMagnifierZoom(handle, zoom);
    }
    @Override public float getMagnifierZoom() { return zoom; }
    @Override public void setScreenOffsetYRelativeToCursor(boolean value) {}
    @Override public synchronized void setUnviewableWMClasses(String... values) {
        if (handle != 0) nativeUnviewableWMClasses(handle, values);
    }
    @Override public void addDirectContent(int w, Drawable d, GPUImage i) {}
    @Override public void nativeRemoveDirectContent(int w, int p) {}
    @Override public void nativeSetCompositeRedirected(int w, boolean r) {}
    @Override public void nativeCompositeRedirect(int s, int d, short x, short y) {}
    @Override public void onUpdateWindowContentDirect(Window w, Drawable d) {}
    @Override public void onUpdateWindowContentDirect(Window w, Drawable d, short x, short y) {}
    @Override public void requestRender() {}
    @Override public void queueEvent(Runnable action) { post(action); }
    @Override public void onSurfaceChanged(int w, int h) { viewTransformation.update(w, h, xServer.screenInfo.width, xServer.screenInfo.height); }
    @Override public void setFpsWindowId(int id) {}
    @Override public void setPipMode(boolean pip) {}
    @Override public void setFrameRating(Object rating) {
        if (rating instanceof FrameRating) {
            classicHud = (FrameRating) rating;
            classicHud.setFrameGenerationSource(this);
            classicHud.enableByUser();
            setHudMode(1);
        } else if (rating instanceof WinlatorHUD) {
            modernHud = (WinlatorHUD) rating;
            modernHud.setFrameGenerationSource(this);
            setHudMode(2);
        }
    }
    public synchronized void setHudMode(int mode) {
        removeCallbacks(hudCheck);
        hudMode = mode; hudWindow = 0; hudFrames = 0; hudTime = 0;
        hudRenderer = ""; hudGpu = "";
        if (classicHud != null) classicHud.reset();
        if (handle == 0 || destroyed) return;
        nativeHudEnabled(handle, mode != 0);
        if (mode != 0) post(hudCheck);
    }
    private void applyVisualOptions() {
        if (handle == 0 || destroyed) return;
        visualRevision = nativeVisualOptions(handle, filterMode, postFXMode, stretchMode, sharpness,
                frameGenRequested, frameGenCache, frameGenMultiplier, frameGenTargetRate,
                frameGenFlowScale, frameGenRefresh, "wfg".equals(frameGenEngine) ? 1 : 0);
        if (frameGenRequested || frameGenEnabled) {
            removeCallbacks(frameGenCheck);
            post(frameGenCheck);
        }
    }
    @Override public synchronized void setFilterMode(int mode) {
        filterMode = backend == 2 ? 0 : backend == 1 ? (mode == 1 ? 1 : 0) : Math.max(0, Math.min(5, mode)); applyVisualOptions();
    }
    @Override public synchronized void setPostFXMode(int mode) {
        postFXMode = isVulkanBackend() ? Math.max(0, Math.min(20, mode)) : 0; applyVisualOptions();
    }
    @Override public synchronized void setStretchMode(int mode) {
        stretchMode = mode == 1 ? 1 : 0; applyVisualOptions();
    }
    @Override public synchronized void setSharpness(float value) {
        sharpness = Float.isNaN(value) ? .5f : Math.max(0f, Math.min(1f, value)); applyVisualOptions();
    }
    @Override public synchronized void setFrameGenerationEngine(String engine) {
        if (!frameGenRequested && !frameGenEnabled)
            frameGenEngine = "wfg".equals(engine) ? "wfg" : "lsfg";
    }
    @Override public synchronized String getFrameGenerationEngine() { return frameGenEngine; }
    @Override public synchronized void setFrameGenerationConfig(boolean enabled, String cachePath,
            int multiplier, int targetRate, int flowScalePercent, float refreshRate) {
        frameGenCache = cachePath;
        frameGenRequested = isVulkanBackend() && enabled && ("wfg".equals(frameGenEngine) ||
            (cachePath != null && !cachePath.isEmpty()));
        frameGenRefresh = refreshRate >= 1f ? refreshRate : 60f;
        setFrameGenerationSettings(multiplier, targetRate, flowScalePercent);
    }
    @Override public synchronized boolean setFrameGenerationEnabled(boolean enabled) {
        if (destroyed || handle == 0 || enabled && !isVulkanBackend()) return false;
        if (enabled && !"wfg".equals(frameGenEngine) && (frameGenCache == null || !new File(frameGenCache).isFile())) {
            File cache = LosslessScaling.resolveCacheFile(getContext(), true);
            if (cache == null) return false;
            frameGenCache = cache.getAbsolutePath();
        }
        frameGenRequested = enabled;
        applyVisualOptions();
        return true;
    }
    @Override public synchronized void setFrameGenerationSettings(int multiplier, int targetRate, int flowScalePercent) {
        frameGenMultiplier = Math.max(2, Math.min(4, multiplier));
        frameGenTargetRate = Math.max(-1, Math.min(1000, targetRate));
        frameGenFlowScale = Math.max(25, Math.min(100, flowScalePercent));
        applyVisualOptions();
    }
    @Override public synchronized int getFrameGenerationMultiplier() { return frameGenMultiplier; }
    @Override public synchronized int getFrameGenerationTargetRate() { return frameGenTargetRate; }
    @Override public synchronized int getFrameGenerationFlowScale() { return frameGenFlowScale; }
    @Override public synchronized boolean isFrameGenerationEnabled() { return frameGenEnabled; }
    @Override public synchronized boolean isFrameGenerationRequested() { return frameGenRequested; }
    @Override public synchronized long getGeneratedFrameCount() { return generatedFrames; }
    @Override public synchronized void setOnFrameGenerationStateChangedListener(Runnable listener) {
        frameGenListener = listener;
        notifyFrameGenerationStateChanged();
    }
    private void notifyFrameGenerationStateChanged() {
        if (frameGenListener != null) post(frameGenListener);
    }
    private static native long nativeVisualOptions(long handle, int filter, int postFX, int stretch,
            float sharpness, boolean frameGen, String cachePath, int multiplier, int targetRate,
            int flowScale, float refreshRate, int engine);
    private static native long[] nativeVisualState(long handle);
    private static native void nativeHudEnabled(long handle, boolean enabled);
    private static native String[] nativeHudSnapshot(long handle);
    private static native long nativeCreate(int width, int height, boolean validationEnabled,
            String driverPath, String libraryName, String nativeLibDir, int presentMode, boolean swapRB, int backend, int displayXFlags);
    private static native void nativeStart(long handle, String socket, String shm);
    private static native void nativeDestroy(long handle);
    private static native void nativeSurface(long handle, Surface surface);
    private static native void nativeInput(long handle, int kind, int a, int b);
    private static native void nativeFrameTimeline(long handle, long id);
    private static native void nativeOptions(long handle, boolean fullscreen, boolean cursor, int fps, float refresh);
    private static native void nativeMagnifierZoom(long handle, float zoom);
    private static native void nativeRefreshRates(long handle, int[] rates);
    private static native void nativeDiagnostics(long handle, boolean enabled, boolean commandCacheEnabled);
    private static native void nativeUnviewableWMClasses(long handle, String[] values);
    private static native String nativeError(long handle);
    private static native boolean nativeHasContent(long handle);
    private static native long nativeFocusHandle(long handle);
    private static native boolean nativeHasWindow(long handle, long hwnd);
}
