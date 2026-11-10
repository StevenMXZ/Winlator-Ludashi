// SPDX-License-Identifier: GPL-3.0-only
// Adapted from The412Banner/Ludashi 0ab047311ccc55b2911d984727d2d57ac6c0ebf7.
package com.winlator.cmod.widget.gpu;

import java.util.ArrayDeque;

/**
 * GPU busy share over about the last {@link #windowMs} from {@link KgslBusy} running totals (busy samples, samples,
 * samples in which a ringbuffer or context started new work). No reading until new work has been seen; {@link #failed}
 * once {@link #GIVE_UP_SAMPLES} samples passed without any. Several HUDs may ask at different rates, so the window is
 * time-based. Ported from DroidDeck's GpuStatsComponent.MemstoreLoad.
 */
final class MemstoreLoad {
    static final long GIVE_UP_SAMPLES = 5_000L;

    private final long windowMs;
    private final ArrayDeque<long[]> history = new ArrayDeque<>(); // {timeMs, busy, samples, moves}
    private boolean working;
    private boolean failed;
    private long waited;
    private Integer last;

    MemstoreLoad(long windowMs) { this.windowMs = windowMs; }

    boolean working() { return working; }
    boolean failed() { return failed; }

    /** @param counts {busy, samples, moves} from the reader, or null when it could not be read. */
    Integer percent(long nowMs, long[] counts) {
        if (failed || counts == null || counts.length < 3) return null;
        long[] previous = history.peekLast();
        history.addLast(new long[] {nowMs, counts[0], counts[1], counts[2]});
        while (history.size() > 2) {
            long[] second = secondOldest();
            if (nowMs - second[0] < windowMs) break;
            history.removeFirst();
        }
        if (previous == null) return null;
        long fresh = counts[1] - previous[2];
        if (fresh <= 0) return last;
        if (!working) {
            if (counts[2] > previous[3]) {
                working = true;
            } else {
                waited += fresh;
                if (waited >= GIVE_UP_SAMPLES) failed = true;
                return null;
            }
        }
        long[] base = history.peekFirst();
        long samples = counts[1] - base[2];
        if (samples <= 0) return last;
        long p = (counts[0] - base[1]) * 100 / samples;
        last = (int) Math.max(0, Math.min(100, p));
        return last;
    }

    /** The totals stop while sampling is off; the next reading starts a new window. */
    void pause() {
        history.clear();
        last = null;
    }

    private long[] secondOldest() {
        java.util.Iterator<long[]> it = history.iterator();
        it.next();
        return it.next();
    }
}

