package com.winlator.cmod.renderer;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit tests for pool initialization failure handling.
 *
 * <p>For each k in 0..N-1:
 * <ul>
 *   <li>Mock {@code nativeCreateBuffer} to succeed for calls 0..k-1 and fail
 *       (return 0) on call k.</li>
 *   <li>Assert {@code init()} returns {@code false}.</li>
 *   <li>Assert {@code nativeDestroyBuffer} was called exactly k times (once for
 *       each successfully allocated buffer).</li>
 *   <li>Assert {@code nativeDestroyBuffer} was NOT called with 0 (the failure
 *       sentinel).</li>
 * </ul>
 */
public class AHardwareBufferPoolInitFailureTest {

    private static final long PTR_BASE = 1000L;

    /**
     * Fake {@link AHardwareBufferNativeCalls} that:
     * <ul>
     *   <li>Returns unique non-zero pointers for calls 0..failAtIndex-1.</li>
     *   <li>Returns 0 (failure sentinel) on call number failAtIndex.</li>
     *   <li>Records all pointers passed to {@code destroyBuffer}.</li>
     * </ul>
     */
    private static class FailAtKNativeCalls implements AHardwareBufferNativeCalls {
        private final int failAtIndex;
        private final AtomicInteger createCallCount = new AtomicInteger(0);
        private final AtomicLong ptrCounter = new AtomicLong(PTR_BASE);
        final List<Long> destroyedPtrs = new ArrayList<>();
        final List<Long> allocatedPtrs = new ArrayList<>();

        FailAtKNativeCalls(int failAtIndex) {
            this.failAtIndex = failAtIndex;
        }

        @Override
        public long createBuffer(int width, int height, int format, long usage) {
            int callIndex = createCallCount.getAndIncrement();
            if (callIndex == failAtIndex) {
                return 0L;
            }
            long ptr = ptrCounter.getAndIncrement();
            allocatedPtrs.add(ptr);
            return ptr;
        }

        @Override
        public synchronized void destroyBuffer(long ptr) {
            destroyedPtrs.add(ptr);
        }

        @Override
        public int sendBufferToSocket(long ptr, int fd) {
            return 0;
        }

        @Override
        public long receiveBufferFromSocket(int fd) {
            return ptrCounter.getAndIncrement();
        }

        @Override
        public int waitFence(int fd, int timeoutMs) {
            return 0;
        }
    }

    /**
     * Runs the failure-at-k scenario for a pool of size {@code poolSize} where
     * {@code nativeCreateBuffer} fails at call index {@code failAtIndex}.
     *
     * <p>Verifies:
     * <ol>
     *   <li>{@code init()} returns {@code false}.</li>
     *   <li>{@code nativeDestroyBuffer} is called exactly {@code failAtIndex} times.</li>
     *   <li>Each of the {@code failAtIndex} successfully allocated pointers was destroyed.</li>
     *   <li>{@code nativeDestroyBuffer} was NOT called with 0 (the failure sentinel).</li>
     * </ol>
     */
    private static void assertInitFailureAtK(int poolSize, int failAtIndex) {
        FailAtKNativeCalls fake = new FailAtKNativeCalls(failAtIndex);

        AHardwareBufferPool pool = new AHardwareBufferPool(
                64, 64, poolSize,
                /* AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM */ 1,
                /* GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY */ 0x130L,
                fake);

        boolean result = pool.init();
        if (result) {
            throw new AssertionError(
                    "init() must return false when nativeCreateBuffer fails at index "
                            + failAtIndex + " (poolSize=" + poolSize + ")");
        }

        int expectedDestroyCount = failAtIndex;
        if (fake.destroyedPtrs.size() != expectedDestroyCount) {
            throw new AssertionError(
                    "nativeDestroyBuffer must be called exactly " + expectedDestroyCount
                            + " time(s) when failure occurs at index " + failAtIndex
                            + " (poolSize=" + poolSize + "), but was called "
                            + fake.destroyedPtrs.size() + " time(s)");
        }

        for (int i = 0; i < fake.allocatedPtrs.size(); i++) {
            long allocatedPtr = fake.allocatedPtrs.get(i);
            if (!fake.destroyedPtrs.contains(allocatedPtr)) {
                throw new AssertionError(
                        "nativeDestroyBuffer must be called with allocated pointer 0x"
                                + Long.toHexString(allocatedPtr)
                                + " (slot " + i + ", poolSize=" + poolSize
                                + ", failAtIndex=" + failAtIndex + ")");
            }
        }

        if (fake.destroyedPtrs.contains(0L)) {
            throw new AssertionError(
                    "nativeDestroyBuffer must NOT be called with 0 (the failure sentinel) "
                            + "(poolSize=" + poolSize + ", failAtIndex=" + failAtIndex + ")");
        }
    }

    @Property(tries = 100)
    void initFailureAtK_releasesAllPreviousBuffersAndReturnsFalse(
            @ForAll @IntRange(min = 1, max = 8) int poolSize,
            @ForAll @IntRange(min = 0, max = 7) int failAtIndexRaw) {

        int failAtIndex = failAtIndexRaw % poolSize;
        assertInitFailureAtK(poolSize, failAtIndex);
    }

    /** Pool of 1: failure at k=0 — no buffers allocated, no destroys expected. */
    @Property(tries = 1)
    void poolSize1_failureAtK0() { assertInitFailureAtK(1, 0); }

    /** Pool of 2: failure at k=0 — no destroys expected. */
    @Property(tries = 1)
    void poolSize2_failureAtK0() { assertInitFailureAtK(2, 0); }

    /** Pool of 2: failure at k=1 — 1 destroy expected. */
    @Property(tries = 1)
    void poolSize2_failureAtK1() { assertInitFailureAtK(2, 1); }

    /** Pool of 3: failure at k=0 — no destroys expected. */
    @Property(tries = 1)
    void poolSize3_failureAtK0() { assertInitFailureAtK(3, 0); }

    /** Pool of 3: failure at k=1 — 1 destroy expected. */
    @Property(tries = 1)
    void poolSize3_failureAtK1() { assertInitFailureAtK(3, 1); }

    /** Pool of 3: failure at k=2 — 2 destroys expected. */
    @Property(tries = 1)
    void poolSize3_failureAtK2() { assertInitFailureAtK(3, 2); }

    /** Pool of 4: failure at k=0 — no destroys expected. */
    @Property(tries = 1)
    void poolSize4_failureAtK0() { assertInitFailureAtK(4, 0); }

    /** Pool of 4: failure at k=3 — 3 destroys expected. */
    @Property(tries = 1)
    void poolSize4_failureAtK3() { assertInitFailureAtK(4, 3); }

    /** Pool of 8: failure at k=0 — no destroys expected. */
    @Property(tries = 1)
    void poolSize8_failureAtK0() { assertInitFailureAtK(8, 0); }

    /** Pool of 8: failure at k=4 — 4 destroys expected. */
    @Property(tries = 1)
    void poolSize8_failureAtK4() { assertInitFailureAtK(8, 4); }

    /** Pool of 8: failure at k=7 — 7 destroys expected. */
    @Property(tries = 1)
    void poolSize8_failureAtK7() { assertInitFailureAtK(8, 7); }
}
