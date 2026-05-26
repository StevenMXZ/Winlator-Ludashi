package com.winlator.cmod.renderer;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pool Size Invariant
 *
 * For any pool size N in [1..4] and arbitrary sequences of acquire/release ops:
 *   - The number of simultaneously acquired (in-flight) slots never exceeds N
 *   - {@code acquire()} returns -1 when all N slots are in-flight and the
 *     timeout of {@link AHardwareBufferPool#ACQUIRE_TIMEOUT_MS} elapses
 *   - The pool never hands out the same slot index twice concurrently
 */
public class AHardwareBufferPoolProperty3Test {

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

    private static AHardwareBufferPool makePool(int n) {
        AHardwareBufferPool pool = new AHardwareBufferPool(
                64, 64, n,
                /* AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM */ 1,
                /* GPU_FRAMEBUFFER | GPU_SAMPLED_IMAGE | COMPOSER_OVERLAY */ 0x130L,
                fakeCalls());
        boolean ok = pool.init();
        if (!ok) {
            throw new AssertionError("pool.init() must succeed with fake native calls");
        }
        return pool;
    }

    @Property(tries = 100)
    void simultaneouslyAcquiredCountNeverExceedsN(
            @ForAll @IntRange(min = 1, max = 4) int n) {

        AHardwareBufferPool pool = makePool(n);
        try {
            boolean[] slotInFlight = new boolean[n];
            int inFlightCount = 0;

            int[] acquired = new int[n];
            for (int i = 0; i < n; i++) {
                int slot = pool.acquire();

                if (slot < 0 || slot >= n) {
                    throw new AssertionError(
                            "acquire() call " + i + " must return index in [0, " + n
                            + "), got " + slot);
                }

                if (slotInFlight[slot]) {
                    throw new AssertionError(
                            "acquire() returned slot " + slot + " which is already in-flight "
                            + "(duplicate slot handed out concurrently)");
                }

                slotInFlight[slot] = true;
                inFlightCount++;
                acquired[i] = slot;

                if (inFlightCount > n) {
                    throw new AssertionError(
                            "in-flight count " + inFlightCount + " exceeds pool size N=" + n);
                }
            }

            for (int i = 0; i < n; i++) {
                pool.release(acquired[i], -1);
                slotInFlight[acquired[i]] = false;
                inFlightCount--;
            }

            if (inFlightCount != 0) {
                throw new AssertionError(
                        "in-flight count must be 0 after releasing all slots, got " + inFlightCount);
            }
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void acquireReturnsSentinelWhenPoolExhausted(
            @ForAll @IntRange(min = 1, max = 4) int n) {

        AHardwareBufferPool pool = makePool(n);
        try {
            int[] slots = new int[n];
            for (int i = 0; i < n; i++) {
                slots[i] = pool.acquire();
                if (slots[i] < 0 || slots[i] >= n) {
                    throw new AssertionError(
                            "acquire() call " + i + " must succeed while pool has free slots, "
                            + "got " + slots[i]);
                }
            }

            long before = System.currentTimeMillis();
            int result = pool.acquire();
            long elapsed = System.currentTimeMillis() - before;

            if (result != -1) {
                throw new AssertionError(
                        "acquire() must return -1 when all " + n + " slots are in-flight, got " + result);
            }

            if (elapsed < AHardwareBufferPool.ACQUIRE_TIMEOUT_MS) {
                throw new AssertionError(
                        "acquire() must block for at least ACQUIRE_TIMEOUT_MS="
                        + AHardwareBufferPool.ACQUIRE_TIMEOUT_MS + " ms before returning -1, "
                        + "but returned after only " + elapsed + " ms");
            }

            for (int i = 0; i < n; i++) {
                pool.release(slots[i], -1);
            }
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void concurrentAcquiresReturnDistinctSlots(
            @ForAll @IntRange(min = 1, max = 4) int n) throws InterruptedException {

        AHardwareBufferPool pool = makePool(n);
        try {
            int[] results = new int[n];
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(n);
            AtomicInteger errorCount = new AtomicInteger(0);

            Thread[] threads = new Thread[n];
            for (int i = 0; i < n; i++) {
                final int threadIdx = i;
                threads[i] = new Thread(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        errorCount.incrementAndGet();
                        doneLatch.countDown();
                        return;
                    }
                    results[threadIdx] = pool.acquire();
                    doneLatch.countDown();
                });
                threads[i].setDaemon(true);
                threads[i].start();
            }

            startGate.countDown();
            doneLatch.await();

            if (errorCount.get() > 0) {
                throw new AssertionError("One or more acquire threads were interrupted");
            }

            for (int i = 0; i < n; i++) {
                if (results[i] < 0 || results[i] >= n) {
                    throw new AssertionError(
                            "concurrent acquire() thread " + i
                            + " must return index in [0, " + n + "), got " + results[i]);
                }
            }

            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (results[i] == results[j]) {
                        throw new AssertionError(
                                "concurrent acquire() returned duplicate slot " + results[i]
                                + " for threads " + i + " and " + j
                                + " (pool size N=" + n + ")");
                    }
                }
            }

            for (int i = 0; i < n; i++) {
                pool.release(results[i], -1);
            }
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void interleavedAcquireReleaseRespectsNSlotCap(
            @ForAll @IntRange(min = 1, max = 4) int n,
            @ForAll @IntRange(min = 1, max = 8) int k) {

        AHardwareBufferPool pool = makePool(n);
        try {
            List<Integer> inFlight = new ArrayList<>();
            boolean[] slotUsed = new boolean[n];
            int maxObservedInFlight = 0;

            for (int round = 0; round < k; round++) {
                if (inFlight.size() < n) {
                    int slot = pool.acquire();

                    if (slot < 0 || slot >= n) {
                        throw new AssertionError(
                                "round " + round + ": acquire() must return index in [0, " + n
                                + "), got " + slot + " (in-flight=" + inFlight.size() + ")");
                    }

                    if (slotUsed[slot]) {
                        throw new AssertionError(
                                "round " + round + ": acquire() returned slot " + slot
                                + " which is already in-flight");
                    }

                    slotUsed[slot] = true;
                    inFlight.add(slot);

                    int currentInFlight = inFlight.size();
                    if (currentInFlight > maxObservedInFlight) {
                        maxObservedInFlight = currentInFlight;
                    }

                    if (currentInFlight > n) {
                        throw new AssertionError(
                                "round " + round + ": in-flight count " + currentInFlight
                                + " exceeds pool size N=" + n);
                    }
                }

                if (!inFlight.isEmpty() && (round % 2 == 1 || inFlight.size() == n)) {
                    int toRelease = inFlight.remove(0);
                    slotUsed[toRelease] = false;
                    pool.release(toRelease, -1);
                }
            }

            for (int slot : inFlight) {
                pool.release(slot, -1);
            }

            if (maxObservedInFlight > n) {
                throw new AssertionError(
                        "max observed in-flight count " + maxObservedInFlight
                        + " exceeded pool size N=" + n);
            }
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void acquireUnblocksWhenSlotReleasedBeforeTimeout(
            @ForAll @IntRange(min = 1, max = 4) int n) throws InterruptedException {

        AHardwareBufferPool pool = makePool(n);
        try {
            int[] slots = new int[n];
            for (int i = 0; i < n; i++) {
                slots[i] = pool.acquire();
                if (slots[i] < 0 || slots[i] >= n) {
                    throw new AssertionError(
                            "initial acquire() call " + i + " must succeed, got " + slots[i]);
                }
            }

            long releaseDelayMs = AHardwareBufferPool.ACQUIRE_TIMEOUT_MS / 2;
            final int slotToRelease = slots[0];
            Thread releaser = new Thread(() -> {
                try {
                    Thread.sleep(releaseDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                pool.release(slotToRelease, -1);
            });
            releaser.setDaemon(true);
            releaser.start();

            int result = pool.acquire();

            if (result < 0 || result >= n) {
                throw new AssertionError(
                        "acquire() must return a valid slot index in [0, " + n
                        + ") when a slot is released before the timeout, got " + result);
            }

            releaser.join(AHardwareBufferPool.ACQUIRE_TIMEOUT_MS * 2);

            pool.release(result, -1);
            for (int i = 1; i < n; i++) {
                pool.release(slots[i], -1);
            }
        } finally {
            pool.destroy();
        }
    }
}
