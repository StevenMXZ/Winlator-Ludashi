package com.winlator.cmod.widget.gpu;

import org.junit.Test;
import static org.junit.Assert.*;

public class MemstoreLoadTest {
    @Test public void primesOnNewWorkAndReportsBusyShareAcrossTheWindow() {
        MemstoreLoad load = new MemstoreLoad(1000);
        assertNull(load.percent(0, new long[]{0, 0, 0}));
        assertNull(load.percent(500, new long[]{0, 500, 0}));
        assertEquals(Integer.valueOf(25), load.percent(1000, new long[]{250, 1000, 1}));
        assertTrue(load.working());
        assertEquals(Integer.valueOf(50), load.percent(1500, new long[]{500, 1500, 2}));
        assertEquals(Integer.valueOf(50), load.percent(2000, new long[]{750, 2000, 3}));
    }

    @Test public void unmovingStampsAreUnavailableInsteadOfFakeZero() {
        MemstoreLoad load = new MemstoreLoad(1000);
        assertNull(load.percent(0, new long[]{0, 0, 0}));
        assertNull(load.percent(5000, new long[]{0, 5000, 0}));
        assertTrue(load.failed());
        assertNull(load.percent(6000, new long[]{100, 6000, 1}));
    }

    @Test public void pauseStartsANewWindowAndIdleWorkCanReportZero() {
        MemstoreLoad load = new MemstoreLoad(1000);
        load.percent(0, new long[]{0, 0, 0});
        assertEquals(Integer.valueOf(100), load.percent(1000, new long[]{1000, 1000, 1}));
        load.pause();
        assertNull(load.percent(2000, new long[]{1000, 1000, 1}));
        assertEquals(Integer.valueOf(0), load.percent(3000, new long[]{1000, 2000, 1}));
    }

    @Test public void invalidCountsAreUnavailableAndRoundingStaysInRange() {
        MemstoreLoad load = new MemstoreLoad(1000);
        assertNull(load.percent(0, null));
        assertNull(load.percent(0, new long[]{0, 0}));
        load.percent(0, new long[]{0, 0, 0});
        assertEquals(Integer.valueOf(100), load.percent(1000, new long[]{1001, 1000, 1}));
    }
}
