package com.winlator.cmod.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreTransferStatisticsTest {
    @Test fun remainingSecondsRoundUpAndHandleUnknownTotals() {
        assertEquals(4L, remainingTime(1L, 11L, 3L))
        assertEquals(1L, remainingTime(0L, Long.MAX_VALUE, Long.MAX_VALUE))
        assertNull(remainingTime(10L, 10L, 3L))
        assertNull(remainingTime(10L, 20L, 0L))
        assertNull(remainingTime(0L, 0L, 3L))
    }
}
