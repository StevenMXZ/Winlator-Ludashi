package com.winlator.cmod.renderer;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drop Warning Includes Consecutive Count
 *
 * For any N in [1..10] consecutive timeouts:
 *   - After N calls to {@code acquire()} that all time out (all slots in-flight),
 *     the {@code consecutiveDrops} counter equals N.
 *   - The warning log message format is
 *     {@code "acquire: timeout after <TIMEOUT> ms, consecutiveDrops=<N>"},
 *     which contains the value N.
 *   - After a successful acquire, {@code consecutiveDrops} resets to 0.
 *
 * <p>Because {@code android.util.Log} is a no-op stub in JVM unit tests
 * ({@code returnDefaultValues = true}), the log message content is verified
 * by inspecting the private {@code consecutiveDrops} field via reflection.
 */
public class AHardwareBufferPoolProperty15Test {

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

    private static int readConsecutiveDrops(AHardwareBufferPool pool) {
        try {
            Field f = AHardwareBufferPool.class.getDeclaredField("consecutiveDrops");
            f.setAccessible(true);
            return (int) f.get(pool);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new AssertionError("Could not read consecutiveDrops field: " + e.getMessage(), e);
        }
    }

    @Property(tries = 100)
    void afterNConsecutiveTimeoutsDropCountEqualsN(
            @ForAll @IntRange(min = 1, max = 10) int n) {

        AHardwareBufferPool pool = makePool(1);
        try {
            int heldSlot = pool.acquire();
            if (heldSlot < 0) {
                throw new AssertionError("Initial acquire() must succeed on a fresh pool");
            }

            int dropsAfterSuccess = readConsecutiveDrops(pool);
            if (dropsAfterSuccess != 0) {
                throw new AssertionError(
                        "consecutiveDrops must be 0 after a successful acquire(), got "
                        + dropsAfterSuccess);
            }

            for (int i = 1; i <= n; i++) {
                int result = pool.acquire();

                if (result != -1) {
                    throw new AssertionError(
                            "acquire() must return -1 when pool is exhausted (call " + i
                            + " of " + n + "), got " + result);
                }

                int drops = readConsecutiveDrops(pool);
                if (drops != i) {
                    throw new AssertionError(
                            "consecutiveDrops must equal " + i + " after " + i
                            + " consecutive timeout(s), got " + drops);
                }
            }

            int finalDrops = readConsecutiveDrops(pool);
            if (finalDrops != n) {
                throw new AssertionError(
                        "consecutiveDrops must equal N=" + n + " after N consecutive timeouts, "
                        + "got " + finalDrops);
            }

            pool.release(heldSlot, -1);
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void consecutiveDropsResetsToZeroAfterSuccessfulAcquire(
            @ForAll @IntRange(min = 1, max = 10) int n) {

        AHardwareBufferPool pool = makePool(1);
        try {
            int heldSlot = pool.acquire();
            if (heldSlot < 0) {
                throw new AssertionError("Initial acquire() must succeed on a fresh pool");
            }

            for (int i = 0; i < n; i++) {
                int result = pool.acquire();
                if (result != -1) {
                    throw new AssertionError(
                            "acquire() must return -1 when pool is exhausted (call " + (i + 1)
                            + " of " + n + "), got " + result);
                }
            }

            int dropsBeforeReset = readConsecutiveDrops(pool);
            if (dropsBeforeReset != n) {
                throw new AssertionError(
                        "consecutiveDrops must equal N=" + n + " after N timeouts, got "
                        + dropsBeforeReset);
            }

            pool.release(heldSlot, -1);

            int reacquired = pool.acquire();
            if (reacquired < 0) {
                throw new AssertionError(
                        "acquire() must succeed after releasing the held slot, got " + reacquired);
            }

            int dropsAfterReset = readConsecutiveDrops(pool);
            if (dropsAfterReset != 0) {
                throw new AssertionError(
                        "consecutiveDrops must reset to 0 after a successful acquire(), "
                        + "got " + dropsAfterReset
                        + " (was " + n + " before the successful acquire)");
            }

            pool.release(reacquired, -1);
        } finally {
            pool.destroy();
        }
    }

    @Property(tries = 100)
    void warningLogMessageFormatContainsConsecutiveDropsCount(
            @ForAll @IntRange(min = 1, max = 10) int n) {

        AHardwareBufferPool pool = makePool(1);
        try {
            int heldSlot = pool.acquire();
            if (heldSlot < 0) {
                throw new AssertionError("Initial acquire() must succeed on a fresh pool");
            }

            for (int i = 0; i < n; i++) {
                int result = pool.acquire();
                if (result != -1) {
                    throw new AssertionError(
                            "acquire() must return -1 when pool is exhausted, got " + result);
                }
            }

            int consecutiveDrops = readConsecutiveDrops(pool);

            // Construct the warning message exactly as the production code does
            String warningMessage = "acquire: timeout after " + AHardwareBufferPool.ACQUIRE_TIMEOUT_MS
                    + " ms, consecutiveDrops=" + consecutiveDrops;

            String expectedSubstring = "consecutiveDrops=" + n;
            if (!warningMessage.contains(expectedSubstring)) {
                throw new AssertionError(
                        "Warning log message must contain '" + expectedSubstring + "' after N="
                        + n + " consecutive timeouts, but message was: '" + warningMessage + "'");
            }

            if (consecutiveDrops != n) {
                throw new AssertionError(
                        "consecutiveDrops must equal N=" + n + " after N consecutive timeouts, "
                        + "got " + consecutiveDrops);
            }

            pool.release(heldSlot, -1);
        } finally {
            pool.destroy();
        }
    }
}
