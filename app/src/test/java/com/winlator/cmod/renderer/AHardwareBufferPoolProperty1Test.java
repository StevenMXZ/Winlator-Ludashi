package com.winlator.cmod.renderer;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pool Initialization Correctness
 *
 * For any valid pool configuration (count N in [1..8], width W in [64..4096],
 * height H in [64..4096]), after {@code pool.init()} succeeds:
 *   - {@code pool.getCount()} == N
 *   - each buffer pointer is non-zero
 *   - {@code pool.getFormat()} == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM (1)
 *   - {@code pool.getUsage()} includes GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY
 *   - {@code pool.getWidth()} == W and {@code pool.getHeight()} == H
 *   - {@code init()} returns {@code true}
 */
public class AHardwareBufferPoolProperty1Test {

    /** AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM = 1 (from NDK android/hardware_buffer.h) */
    private static final int AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM = 1;

    /**
     * Required usage flags for the direct compositing path.
     * AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER   = 0x100
     * AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE = 0x020
     * AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY  = 0x010
     * Combined: 0x130
     */
    private static final long REQUIRED_USAGE_FLAGS = 0x130L;

    private static AHardwareBufferNativeCalls fakeCalls() {
        final AtomicLong ptrCounter = new AtomicLong(1000L);
        return new AHardwareBufferNativeCalls() {
            @Override
            public long createBuffer(int width, int height, int format, long usage) {
                return ptrCounter.getAndIncrement();
            }
            @Override public void destroyBuffer(long ptr) {}
            @Override public int  sendBufferToSocket(long ptr, int fd) { return 0; }
            @Override public long receiveBufferFromSocket(int fd) { return ptrCounter.getAndIncrement(); }
            @Override public int  waitFence(int fd, int timeoutMs) { return 0; }
        };
    }

    @Property(tries = 100)
    void poolInitCorrectness(
            @ForAll @IntRange(min = 1, max = 8)     int n,
            @ForAll @IntRange(min = 64, max = 4096) int w,
            @ForAll @IntRange(min = 64, max = 4096) int h) {

        AHardwareBufferPool pool = new AHardwareBufferPool(
                w, h, n,
                AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                REQUIRED_USAGE_FLAGS,
                fakeCalls());

        boolean initResult = pool.init();
        if (!initResult) {
            throw new AssertionError(
                    "init() must return true for valid parameters (n=" + n + ", w=" + w + ", h=" + h + ")");
        }

        if (pool.getCount() != n) {
            throw new AssertionError(
                    "getCount() must equal N=" + n + " after init(), got " + pool.getCount());
        }

        for (int i = 0; i < n; i++) {
            int slot = pool.acquire();
            if (slot == -1) {
                throw new AssertionError("acquire() must succeed for slot " + i + " after init()");
            }
            long ptr = pool.getBufferPtr(slot);
            if (ptr == 0L) {
                throw new AssertionError("bufferPtr[" + slot + "] must be non-zero after init()");
            }
            pool.release(slot, -1);
        }

        if (pool.getFormat() != AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
            throw new AssertionError(
                    "format must be AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM (1), got " + pool.getFormat());
        }

        long actualUsage = pool.getUsage();
        if ((actualUsage & REQUIRED_USAGE_FLAGS) != REQUIRED_USAGE_FLAGS) {
            throw new AssertionError(
                    "usage must include 0x130 (GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY), "
                    + "got 0x" + Long.toHexString(actualUsage));
        }

        if (pool.getWidth() != w) {
            throw new AssertionError("getWidth() must equal W=" + w + ", got " + pool.getWidth());
        }
        if (pool.getHeight() != h) {
            throw new AssertionError("getHeight() must equal H=" + h + ", got " + pool.getHeight());
        }

        pool.destroy();
    }

    @Property(tries = 100)
    void poolInit_callsCreateBufferExactlyNTimes(
            @ForAll @IntRange(min = 1, max = 8)     int n,
            @ForAll @IntRange(min = 64, max = 4096) int w,
            @ForAll @IntRange(min = 64, max = 4096) int h) {

        final AtomicLong createCallCount = new AtomicLong(0);
        final AtomicLong ptrBase = new AtomicLong(1L);

        AHardwareBufferNativeCalls countingCalls = new AHardwareBufferNativeCalls() {
            @Override
            public long createBuffer(int width, int height, int format, long usage) {
                createCallCount.incrementAndGet();
                return ptrBase.getAndIncrement();
            }
            @Override public void destroyBuffer(long ptr) {}
            @Override public int  sendBufferToSocket(long ptr, int fd) { return 0; }
            @Override public long receiveBufferFromSocket(int fd) { return ptrBase.getAndIncrement(); }
            @Override public int  waitFence(int fd, int timeoutMs) { return 0; }
        };

        AHardwareBufferPool pool = new AHardwareBufferPool(
                w, h, n,
                AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                REQUIRED_USAGE_FLAGS,
                countingCalls);

        pool.init();

        int actualCalls = (int) createCallCount.get();
        if (actualCalls != n) {
            throw new AssertionError(
                    "init() must call createBuffer exactly N=" + n + " times, called " + actualCalls);
        }

        pool.destroy();
    }

    @Property(tries = 100)
    void poolInit_passesCorrectParametersToCreateBuffer(
            @ForAll @IntRange(min = 1, max = 8)     int n,
            @ForAll @IntRange(min = 64, max = 4096) int w,
            @ForAll @IntRange(min = 64, max = 4096) int h) {

        final int    expectedFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        final long   expectedUsage  = REQUIRED_USAGE_FLAGS;
        final AtomicLong ptrBase    = new AtomicLong(1L);

        final int[]  capturedWidth  = new int[n];
        final int[]  capturedHeight = new int[n];
        final int[]  capturedFormat = new int[n];
        final long[] capturedUsage  = new long[n];
        final int[]  callIndex      = {0};

        AHardwareBufferNativeCalls capturingCalls = new AHardwareBufferNativeCalls() {
            @Override
            public long createBuffer(int width, int height, int format, long usage) {
                int idx = callIndex[0]++;
                if (idx < n) {
                    capturedWidth[idx]  = width;
                    capturedHeight[idx] = height;
                    capturedFormat[idx] = format;
                    capturedUsage[idx]  = usage;
                }
                return ptrBase.getAndIncrement();
            }
            @Override public void destroyBuffer(long ptr) {}
            @Override public int  sendBufferToSocket(long ptr, int fd) { return 0; }
            @Override public long receiveBufferFromSocket(int fd) { return ptrBase.getAndIncrement(); }
            @Override public int  waitFence(int fd, int timeoutMs) { return 0; }
        };

        AHardwareBufferPool pool = new AHardwareBufferPool(
                w, h, n, expectedFormat, expectedUsage, capturingCalls);
        pool.init();

        for (int i = 0; i < n; i++) {
            if (capturedWidth[i] != w) {
                throw new AssertionError(
                        "createBuffer call " + i + " must use width=" + w + ", got " + capturedWidth[i]);
            }
            if (capturedHeight[i] != h) {
                throw new AssertionError(
                        "createBuffer call " + i + " must use height=" + h + ", got " + capturedHeight[i]);
            }
            if (capturedFormat[i] != expectedFormat) {
                throw new AssertionError(
                        "createBuffer call " + i + " must use format=" + expectedFormat
                        + ", got " + capturedFormat[i]);
            }
            if (capturedUsage[i] != expectedUsage) {
                throw new AssertionError(
                        "createBuffer call " + i + " must use usage=0x" + Long.toHexString(expectedUsage)
                        + ", got 0x" + Long.toHexString(capturedUsage[i]));
            }
        }

        pool.destroy();
    }
}
