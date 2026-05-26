package com.winlator.cmod.renderer;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Acquire-Release Round-Trip
 *
 * For any pool size N in [1..4] and arbitrary slot sequences:
 *   - {@code acquire()} returns a non-negative index in [0, N)
 *   - after {@code release(index, -1)}, the same slot is acquirable again
 *     within {@link AHardwareBufferPool#ACQUIRE_TIMEOUT_MS}
 *   - releasing all N slots makes all N slots available again
 *   - acquire/release cycles are repeatable without degradation
 */
public class AHardwareBufferPoolProperty2Test {

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
    void singleAcquireReleaseRoundTrip(
            @ForAll @IntRange(min = 1, max = 4) int n) {

        AHardwareBufferPool pool = makePool(n);
        try {
            int slot = pool.acquire();

            if (slot < 0 || slot >= n) {
                throw new AssertionError(
                        "acquire() must return index in [0, " + n + "), got " + slot);
            }

            pool.release(slot, -1);

            int reacquired = pool.acquire();
            if (reacquired < 0 || reacquired >= n) {
                throw new AssertionError(
                        "re-acquire() after release(fd=-1) must return valid index in [0, " + n
                        + "), got " + reacquired);
            }

            if (n == 1 && reacquired != slot) {
                throw new AssertionError(
                        "with N=1, re-acquire() must return the same slot=" + slot
                        + " that was released, got " + reacquired);
            }

            pool.release(reacquired, -1);
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void acquireAllReleasAllReacquireAll(
            @ForAll @IntRange(min = 1, max = 4) int n) {

        AHardwareBufferPool pool = makePool(n);
        try {
            int[] slots = new int[n];
            for (int i = 0; i < n; i++) {
                slots[i] = pool.acquire();
                if (slots[i] < 0 || slots[i] >= n) {
                    throw new AssertionError(
                            "acquire() call " + i + " must return index in [0, " + n
                            + "), got " + slots[i]);
                }
            }

            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (slots[i] == slots[j]) {
                        throw new AssertionError(
                                "acquire() returned duplicate slot " + slots[i]
                                + " at positions " + i + " and " + j);
                    }
                }
            }

            for (int i = 0; i < n; i++) {
                pool.release(slots[i], -1);
            }

            List<Integer> reacquired = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int slot = pool.acquire();
                if (slot < 0 || slot >= n) {
                    throw new AssertionError(
                            "re-acquire() call " + i + " after releasing all slots must return "
                            + "index in [0, " + n + "), got " + slot
                            + " (only " + i + " of " + n + " slots re-acquired so far)");
                }
                reacquired.add(slot);
            }

            for (int i = 0; i < reacquired.size(); i++) {
                for (int j = i + 1; j < reacquired.size(); j++) {
                    if (reacquired.get(i).equals(reacquired.get(j))) {
                        throw new AssertionError(
                                "re-acquire() returned duplicate slot " + reacquired.get(i)
                                + " at positions " + i + " and " + j);
                    }
                }
            }

            for (int slot : reacquired) {
                pool.release(slot, -1);
            }
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void repeatedCyclesAreStable(
            @ForAll @IntRange(min = 1, max = 4)  int n,
            @ForAll @IntRange(min = 1, max = 10) int cycles) {

        AHardwareBufferPool pool = makePool(n);
        try {
            for (int cycle = 0; cycle < cycles; cycle++) {
                int[] slots = new int[n];

                for (int i = 0; i < n; i++) {
                    slots[i] = pool.acquire();
                    if (slots[i] < 0 || slots[i] >= n) {
                        throw new AssertionError(
                                "cycle " + cycle + ": acquire() call " + i
                                + " must return index in [0, " + n + "), got " + slots[i]);
                    }
                }

                for (int i = 0; i < n; i++) {
                    pool.release(slots[i], -1);
                }
            }
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void partialReleaseMakesExactlyOneSlotAvailable(
            @ForAll @IntRange(min = 2, max = 4) int n,
            @ForAll @IntRange(min = 0, max = 3) int releasePos) {

        int rPos = releasePos % n;

        AHardwareBufferPool pool = makePool(n);
        try {
            int[] slots = new int[n];
            for (int i = 0; i < n; i++) {
                slots[i] = pool.acquire();
                if (slots[i] < 0 || slots[i] >= n) {
                    throw new AssertionError(
                            "acquire() call " + i + " must return index in [0, " + n
                            + "), got " + slots[i]);
                }
            }

            int releasedSlot = slots[rPos];
            pool.release(releasedSlot, -1);

            int reacquired = pool.acquire();
            if (reacquired != releasedSlot) {
                throw new AssertionError(
                        "after releasing only slot " + releasedSlot
                        + ", acquire() must return that same slot, got " + reacquired);
            }

            pool.release(reacquired, -1);
            for (int i = 0; i < n; i++) {
                if (i != rPos) {
                    pool.release(slots[i], -1);
                }
            }
        } finally {
            pool.destroy();
        }
    }
}
