package com.winlator.cmod.renderer;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Package-private interface that abstracts the five native JNI calls.
 * The default implementation delegates to the real {@code private static native} methods.
 * Tests can supply a fake implementation to avoid loading {@code libwinlator.so}.
 */
interface AHardwareBufferNativeCalls {
    long createBuffer(int width, int height, int format, long usage);
    void destroyBuffer(long ptr);
    int  sendBufferToSocket(long ptr, int fd);
    long receiveBufferFromSocket(int fd);
    int  waitFence(int fd, int timeoutMs);
}

/**
 * Manages a fixed pool of pre-allocated AHardwareBuffer objects for use as
 * swapchain images on the direct Android compositing path.
 *
 * <p>Provides acquire/release semantics with fence-based availability tracking.
 * Buffers are allocated at {@link #init()} time and released at {@link #destroy()}.
 *
 * <p>Thread-safety: {@link #acquire()} and {@link #release(int, int)} are safe to
 * call from any thread. {@link #init()} and {@link #destroy()} must not be called
 * concurrently with each other or with acquire/release.
 */
public class AHardwareBufferPool {

    /**
     * Default implementation that delegates to the real JNI native methods.
     * Package-private so tests can supply a fake without modifying this class.
     */
    static final AHardwareBufferNativeCalls DEFAULT_NATIVE_CALLS =
            new AHardwareBufferNativeCalls() {
        @Override public long createBuffer(int w, int h, int fmt, long usage) {
            return nativeCreateBuffer(w, h, fmt, usage);
        }
        @Override public void destroyBuffer(long ptr) {
            nativeDestroyBuffer(ptr);
        }
        @Override public int sendBufferToSocket(long ptr, int fd) {
            return nativeSendBufferToSocket(ptr, fd);
        }
        @Override public long receiveBufferFromSocket(int fd) {
            return nativeReceiveBufferFromSocket(fd);
        }
        @Override public int waitFence(int fd, int timeoutMs) {
            return nativeWaitFence(fd, timeoutMs);
        }
    };

    /** The native calls provider; replaced in tests via the package-private constructor. */
    private final AHardwareBufferNativeCalls nativeCalls;

    private static final String LOG_TAG = "AHB_Pool";

    /**
     * One frame period at 60 Hz — the maximum time {@link #acquire()} will block
     * before giving up and returning -1.
     */
    static final long ACQUIRE_TIMEOUT_MS = 16L;

    // -------------------------------------------------------------------------
    // Configuration (immutable after construction)
    // -------------------------------------------------------------------------

    /** Number of buffers in the pool. */
    private final int count;

    /** Buffer width in pixels. */
    private final int width;

    /** Buffer height in pixels. */
    private final int height;

    /**
     * AHardwareBuffer pixel format.
     * Default: AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM = 1
     */
    private final int format;

    /**
     * AHardwareBuffer usage flags.
     * Default: GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY
     */
    private final long usage;

    // -------------------------------------------------------------------------
    // Buffer state (guarded by {@link #lock})
    // -------------------------------------------------------------------------

    /** AHardwareBuffer* pointers stored as jlong; 0 means unallocated. */
    private final long[] bufferPtrs;

    /**
     * Pending release fence fd per slot. -1 means no fence is pending.
     * Stored here so {@link #destroy()} can close any leaked fds.
     */
    private final int[] releaseFds;

    /**
     * true = slot is currently held by the caller (Wine WSI or SurfaceFlinger).
     * false = slot is available for {@link #acquire()}.
     */
    private final boolean[] inFlight;

    // -------------------------------------------------------------------------
    // Synchronization
    // -------------------------------------------------------------------------

    /** Monitor used for acquire/release coordination. */
    private final Object lock = new Object();

    // -------------------------------------------------------------------------
    // Observability
    // -------------------------------------------------------------------------

    /**
     * Number of consecutive {@link #acquire()} calls that timed out without
     * obtaining a buffer. Reset to 0 on the next successful acquire.
     */
    private int consecutiveDrops = 0;

    // -------------------------------------------------------------------------
    // Background fence-wait executor
    // -------------------------------------------------------------------------

    /**
     * Cached thread pool used to wait on release fences without blocking the
     * Wine presentation thread.
     */
    private final ExecutorService fenceWaiter = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AHB-FenceWaiter");
        t.setDaemon(true);
        return t;
    });

    // -------------------------------------------------------------------------
    // Static initialiser — load libwinlator.so which contains ahb_bridge.c
    // -------------------------------------------------------------------------

    static {
        try {
            System.loadLibrary("winlator");
        } catch (UnsatisfiedLinkError e) {
            // Expected in JVM unit-test environments where the native library
            // is not available. Tests must supply a fake AHardwareBufferNativeCalls
            // via the package-private constructor; using DEFAULT_NATIVE_CALLS in
            // a test environment will throw at call time.
        }
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a pool with the given parameters.
     *
     * @param width  buffer width in pixels
     * @param height buffer height in pixels
     * @param count  number of buffers to pre-allocate (typically 2 or 3)
     * @param format AHardwareBuffer format constant (e.g. AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM = 1)
     * @param usage  AHardwareBuffer usage flags
     */
    public AHardwareBufferPool(int width, int height, int count, int format, long usage) {
        this(width, height, count, format, usage, DEFAULT_NATIVE_CALLS);
    }

    /**
     * Package-private constructor that accepts a custom {@link AHardwareBufferNativeCalls}
     * implementation. Used by unit tests to inject a fake without loading the native library.
     */
    AHardwareBufferPool(int width, int height, int count, int format, long usage,
                        AHardwareBufferNativeCalls nativeCalls) {
        this.width  = width;
        this.height = height;
        this.count  = count;
        this.format = format;
        this.usage  = usage;
        this.nativeCalls = nativeCalls;

        bufferPtrs = new long[count];
        releaseFds = new int[count];
        inFlight   = new boolean[count];

        for (int i = 0; i < count; i++) {
            bufferPtrs[i] = 0L;
            releaseFds[i] = -1;
            inFlight[i]   = false;
        }
    }

    /**
     * Convenience constructor using the default format and usage flags required
     * by the direct compositing path.
     *
     * <p>Format: {@code HAL_PIXEL_FORMAT_BGRA_8888} (5)<br>
     * Usage: {@code GPU_COLOR_OUTPUT | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY}
     * (0xB00 = 0x200 | 0x100 | 0x800)
     *
     * <p>Why BGRA: DXVK's preferred swapchain format is
     * {@code DXGI_FORMAT_B8G8R8A8_UNORM} → Vulkan
     * {@code VK_FORMAT_B8G8R8A8_UNORM}. When DXVK renders into an AHB-
     * backed VkImage, the byte layout in memory matches whatever Vulkan
     * format the image was created with. If we allocate the AHB as
     * RGBA_8888 but DXVK writes BGRA, SurfaceFlinger (which reads the AHB
     * per its HAL format) sees swapped channels — red and blue look
     * inverted on screen. Allocating as BGRA_8888 makes the byte layout
     * agree end-to-end (DXVK writes BGRA, AHB stores BGRA, SF reads as
     * BGRA). No swap step needed anywhere.
     *
     * <p>{@code GPU_COLOR_OUTPUT} (a.k.a. {@code GPU_FRAMEBUFFER}) lets DXVK
     * render directly into the AHB as a Vulkan color attachment.
     * {@code GPU_SAMPLED_IMAGE} lets the host process import it as a
     * sampled texture (used by the trojan-blit fallback path).
     * {@code COMPOSER_OVERLAY} signals to SurfaceFlinger that the buffer is
     * eligible for direct hardware-overlay scanout — required to skip the
     * GPU composition path on the host process.
     *
     * <p>{@code HAL_PIXEL_FORMAT_BGRA_8888 = 5} isn't in the public NDK
     * {@code AHARDWAREBUFFER_FORMAT_*} enum but is accepted by
     * {@code AHardwareBuffer_allocate} on Adreno gralloc (and most other
     * Android device gralloc impls). If a device rejects it, pool init
     * will fail and the app falls back to the X11 compositor path. */
    public AHardwareBufferPool(int width, int height, int count) {
        this(width, height, count,
             /* HAL_PIXEL_FORMAT_BGRA_8888 (not in public AHARDWAREBUFFER_FORMAT enum) */ 5,
             /* GPU_COLOR_OUTPUT | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY */
             0xB00L);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Allocates all {@code count} AHardwareBuffers.
     *
     * <p>If any allocation fails, all previously allocated buffers are released
     * and this method returns {@code false}.
     *
     * @return {@code true} if all buffers were allocated successfully
     */
    public boolean init() {
        for (int i = 0; i < count; i++) {
            long ptr = nativeCalls.createBuffer(width, height, format, usage);
            if (ptr == 0L) {
                Log.e(LOG_TAG, "init: nativeCreateBuffer failed at slot " + i
                        + " (w=" + width + ", h=" + height + ", fmt=" + format + ")");
                // Release all buffers allocated so far
                for (int j = 0; j < i; j++) {
                    if (bufferPtrs[j] != 0L) {
                        nativeCalls.destroyBuffer(bufferPtrs[j]);
                        bufferPtrs[j] = 0L;
                    }
                }
                return false;
            }
            bufferPtrs[i] = ptr;
        }
        Log.i(LOG_TAG, "init: allocated " + count + " buffers ("
                + width + "x" + height + ", fmt=" + format + ")");
        return true;
    }

    /**
     * Returns the index of the next available buffer slot, blocking up to
     * {@link #ACQUIRE_TIMEOUT_MS} milliseconds if all slots are in-flight.
     *
     * <p>Returns -1 if no buffer becomes available within the timeout.
     */
    public int acquire() {
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT_MS;

            while (true) {
                // Fast path: scan for a free slot
                for (int i = 0; i < count; i++) {
                    if (!inFlight[i]) {
                        inFlight[i] = true;
                        consecutiveDrops = 0;
                        return i;
                    }
                }

                // All slots in-flight — wait for a release notification
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Timeout — log and return sentinel
            consecutiveDrops++;
            Log.w(LOG_TAG, "acquire: timeout after " + ACQUIRE_TIMEOUT_MS
                    + " ms, consecutiveDrops=" + consecutiveDrops);
            return -1;
        }
    }

    /**
     * Returns the AHardwareBuffer pointer for the given slot index.
     *
     * <p>Only valid while the slot is acquired (between {@link #acquire()} and
     * the corresponding {@link #release(int, int)}).
     *
     * @param index slot index returned by {@link #acquire()}
     * @return AHardwareBuffer* as a {@code long}
     */
    public long getBufferPtr(int index) {
        return bufferPtrs[index];
    }

    /** Returns the number of buffers in the pool. */
    public int getCount() {
        return count;
    }

    /** Returns the buffer width in pixels. */
    public int getWidth() {
        return width;
    }

    /** Returns the buffer height in pixels. */
    public int getHeight() {
        return height;
    }

    /** Returns the AHardwareBuffer format constant. */
    public int getFormat() {
        return format;
    }

    /** Returns the AHardwareBuffer usage flags. */
    public long getUsage() {
        return usage;
    }

    /**
     * Marks the buffer at {@code index} as available for reuse once
     * {@code releaseFenceFd} signals.
     *
     * <p>If {@code releaseFenceFd == -1} the buffer is immediately available and
     * waiting threads are notified inline. If {@code releaseFenceFd >= 0} a
     * background thread waits on the fence (via {@link #nativeWaitFence}) and
     * then notifies waiting threads. Ownership of {@code releaseFenceFd}
     * transfers to this pool; it will be closed after use.
     *
     * @param index          slot index previously returned by {@link #acquire()}
     * @param releaseFenceFd sync fence fd, or -1 if the buffer is already free
     */
    public void release(int index, int releaseFenceFd) {
        if (index < 0 || index >= count) {
            Log.e(LOG_TAG, "release: invalid index " + index);
            if (releaseFenceFd >= 0) {
                nativeCalls.waitFence(releaseFenceFd, 0);
            }
            return;
        }

        if (releaseFenceFd == -1) {
            // No fence — mark available immediately
            synchronized (lock) {
                inFlight[index]   = false;
                releaseFds[index] = -1;
                lock.notifyAll();
            }
        } else {
            // Fence present — wait on a background thread, then notify
            final int slotIndex = index;
            final int fenceFd   = releaseFenceFd;
            fenceWaiter.submit(() -> {
                // nativeWaitFence closes fenceFd regardless of outcome
                nativeCalls.waitFence(fenceFd, (int) ACQUIRE_TIMEOUT_MS);
                synchronized (lock) {
                    inFlight[slotIndex]   = false;
                    releaseFds[slotIndex] = -1;
                    lock.notifyAll();
                }
            });
        }
    }

    /**
     * Destroys all buffers and shuts down the fence-waiter thread pool.
     *
     * <p>Must be called after all in-flight buffers have been released (i.e.,
     * all release fences have signalled). Any remaining pending fences are
     * closed immediately without waiting.
     */
    public void destroy() {
        fenceWaiter.shutdownNow();

        synchronized (lock) {
            for (int i = 0; i < count; i++) {
                if (releaseFds[i] >= 0) {
                    nativeCalls.waitFence(releaseFds[i], 0);
                    releaseFds[i] = -1;
                }
                if (bufferPtrs[i] != 0L) {
                    nativeCalls.destroyBuffer(bufferPtrs[i]);
                    bufferPtrs[i] = 0L;
                }
                inFlight[i] = false;
            }
            lock.notifyAll();
        }

        Log.i(LOG_TAG, "destroy: all buffers released");
    }

    // =========================================================================
    // Native methods — implemented in ahb_bridge.c (libwinlator.so)
    // =========================================================================

    /** @return AHardwareBuffer* as jlong, or 0 on failure */
    private static native long nativeCreateBuffer(int width, int height, int format, long usage);

    /** Releases the AHardwareBuffer at {@code ptr}. */
    private static native void nativeDestroyBuffer(long ptr);

    /** @return 0 on success, -1 on failure */
    public static native int nativeSendBufferToSocket(long ptr, int fd);

    /** @return AHardwareBuffer* as jlong, or 0 on failure */
    private static native long nativeReceiveBufferFromSocket(int fd);

    /**
     * Waits for the sync fence fd to signal, then closes it.
     *
     * @param fd        sync fence file descriptor
     * @param timeoutMs maximum wait time in milliseconds
     * @return 0 on success, -1 on timeout or error
     */
    private static native int nativeWaitFence(int fd, int timeoutMs);
}
