package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test

class SteamDownloadMeterTest {
    @Test fun reusedFilesAdvanceProgressWithoutPretendingTheyWereDownloaded() {
        val meter = SteamDownloadMeter(listOf(1), mapOf(1 to 10000L))
        meter.update(1, 0, 10000, 0, true, 0)
        meter.update(1, 9000, 10000, 0, true, 1000)
        val reading = meter.read(1000)
        assertEquals(9000L, reading.done)
        assertEquals(0L, reading.bytesPerSecond)
        assertNull(reading.etaSeconds)
    }
    @Test fun depotTransitionKeepsOverallProgressAndDoesNotCreateNegativeSpeed() {
        val meter = SteamDownloadMeter(listOf(1, 2), mapOf(1 to 1000L, 2 to 1000L))
        meter.update(1, 0, 1000, 0, false, 0)
        meter.update(1, 1000, 1000, 0, false, 1000)
        meter.update(2, 0, 1000, 1, false, 1100)
        meter.update(2, 500, 1000, 1, false, 2100)
        val reading = meter.read(2100)
        assertEquals(1500L, reading.done); assertEquals(2000L, reading.total)
        assertTrue(reading.bytesPerSecond > 0); assertNotNull(reading.etaSeconds)
    }
    @Test fun unknownFutureDepotSizeDoesNotProduceAnInventedGameEta() {
        val meter = SteamDownloadMeter(listOf(1, 2), mapOf(1 to 1000L, 2 to 0L))
        meter.update(1, 0, 1000, 0, false, 0)
        meter.update(1, 500, 1000, 0, false, 1000)
        assertNull(meter.read(1000).etaSeconds)
        assertTrue(meter.read(1000).bytesPerSecond > 0)
    }
    @Test fun stalledTransferStopsShowingAStalePositiveSpeed() {
        val meter = SteamDownloadMeter(listOf(1), mapOf(1 to 10000L))
        meter.update(1, 0, 10000, 0, false, 0)
        meter.update(1, 500, 10000, 0, false, 1000)
        assertEquals(0L, meter.read(6000).bytesPerSecond)
        assertNull(meter.read(6000).etaSeconds)
    }
}
