package com.winlator.cmod.xenvironment.components;

import android.os.Build;
import android.util.Log;

import com.winlator.cmod.renderer.AHardwareBufferPool;
import com.winlator.cmod.renderer.VulkanRenderer;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

/**
 * Owns the {@link AHardwareBufferPool} and coordinates the direct Android
 * compositing path lifecycle. Wired into {@code XEnvironment} as a peer of
 * {@code XServerComponent} — starts before it so the pool is ready when the
 * first frame arrives.
 *
 * <p><b>Phase 4 deletion readiness:</b> This class has zero compile-time
 * imports from {@code com.winlator.cmod.xserver.*}.
 */
public class DirectCompositorComponent extends EnvironmentComponent {

    private static final String LOG_TAG = "DirectCompositor";

    private final VulkanRenderer renderer;
    private final int poolSize;
    private final int screenWidth;
    private final int screenHeight;

    private AHardwareBufferPool pool;
    private boolean active = false;

    /**
     * @param renderer    the VulkanRenderer that manages SurfaceControl layers
     * @param poolSize    number of AHardwareBuffers to pre-allocate (typically 3)
     * @param screenWidth  game screen width in pixels
     * @param screenHeight game screen height in pixels
     */
    public DirectCompositorComponent(VulkanRenderer renderer, int poolSize,
                                     int screenWidth, int screenHeight) {
        this.renderer = renderer;
        this.poolSize = poolSize;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /**
     * Convenience constructor that uses the default pool size of 3.
     */
    public DirectCompositorComponent(VulkanRenderer renderer,
                                     int screenWidth, int screenHeight) {
        this(renderer, 4, screenWidth, screenHeight);
    }

    @Override
    public void start() {
        if (Build.VERSION.SDK_INT < 26) {
            Log.w(LOG_TAG, "start: API level " + Build.VERSION.SDK_INT
                    + " < 26, AHardwareBuffer not available — skipping activation");
            return;
        }

        pool = new AHardwareBufferPool(screenWidth, screenHeight, poolSize);
        if (!pool.init()) {
            Log.e(LOG_TAG, "start: AHardwareBufferPool.init() failed, falling back to XServer path");
            pool = null;
            return;
        }

        renderer.setDirectCompositor(this);
        renderer.setAHBPool(pool);
        // NOTE: Do NOT call renderer.setNativeMode(true) here.
        // nativeMode is for the legacy submitDirectFrame() path that's
        // driven from Java when a single AHB is pushed per frame. The
        // recv-thread DAC pipeline (the modern path) calls
        // renderer->scanoutSetBuffer from C++ and never sets nativeMode.
        // VulkanRenderer's surface-reattach logic uses isActive() on
        // this component (in addition to nativeMode) to decide whether
        // SCs need to be recreated after lock/unlock, so the flag below
        // doubles as the recv-thread DAC's "I'm active" signal.
        active = true;
        Log.i(LOG_TAG, "start: pool ready (pool=" + poolSize
                + ", " + screenWidth + "x" + screenHeight
                + "), waiting for Wine WSI to deliver first frame");
    }

    @Override
    public void stop() {
        active = false;
        renderer.setNativeMode(false);
        renderer.setDirectCompositor(null);
        renderer.setAHBPool(null);
        if (pool != null) {
            pool.destroy();
            pool = null;
        }
        Log.i(LOG_TAG, "stop: direct compositing deactivated");
    }

    /**
     * Called by VulkanRenderer when SurfaceControl creation fails.
     * Deactivates the direct compositing path so the XServer path is used.
     */
    public void onScanoutFallback() {
        if (!active) return;
        active = false;
        renderer.setAHBPool(null);
        if (pool != null) {
            pool.destroy();
            pool = null;
        }
        Log.w(LOG_TAG, "onScanoutFallback: SurfaceControl failed, falling back to XServer path");
    }

    /**
     * Reinitializes the AHardwareBuffer pool. Called when the Vulkan context is
     * lost and must be fully recreated (e.g., nativeReattachSurface returns false).
     *
     * @return true if the pool was successfully reinitialized
     */
    public boolean reinitPool() {
        renderer.setAHBPool(null);
        if (pool != null) {
            pool.destroy();
            pool = null;
        }
        pool = new AHardwareBufferPool(screenWidth, screenHeight, poolSize);
        if (!pool.init()) {
            Log.e(LOG_TAG, "reinitPool: AHardwareBufferPool.init() failed");
            active = false;
            return false;
        }
        renderer.setAHBPool(pool);
        Log.i(LOG_TAG, "reinitPool: pool reinitialized successfully");
        return true;
    }

    /**
     * Called when the app is sent to the background. Detaches SurfaceControl layers
     * without destroying the AHB pool or Wine swapchain. Wine naturally blocks in
     * {@code acquire()} while paused since no release fences will arrive.
     *
     * <p>Requirements: 9.1
     */
    public void onPause() {
        if (!active) return;
        renderer.detachScanoutLayers();
        Log.i(LOG_TAG, "onPause: SC layers detached, pool and swapchain preserved");
    }

    /**
     * Called when the app returns to the foreground. Reattaches SurfaceControl layers
     * and resumes frame submission without requiring Wine to recreate the swapchain.
     *
     * <p>Requirements: 9.2
     */
    public void onResume() {
        if (!active) return;
        renderer.reattachScanoutLayers();
        Log.i(LOG_TAG, "onResume: SC layers reattached, frame submission resumed");
    }

    /** Returns the AHardwareBufferPool, or {@code null} if not active. */
    public AHardwareBufferPool getPool() {
        return pool;
    }

    /** Returns the VulkanRenderer for frame submission. */
    public VulkanRenderer getRenderer() {
        return renderer;
    }

    /** Returns {@code true} if the direct compositing path is active. */
    public boolean isActive() {
        return active;
    }
}
