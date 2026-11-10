package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SteamAchievementEventsTest {
    private val gse = File("/wine/Roaming/GSE Saves/440/achievements.json")
    private val legacy = File("/wine/Roaming/Goldberg SteamEmu Saves/440/achievements.json")
    @Test fun frequentStatsWritesAndOtherGamesDoNotTriggerAchievementParsing() {
        assertTrue(AchievementWatcher.affectedFiles(File(gse.parentFile, "stats.json"), listOf(gse, legacy)).isEmpty())
        assertTrue(AchievementWatcher.affectedFiles(File("/wine/Roaming/GSE Saves/4400"), listOf(gse, legacy)).isEmpty())
    }
    @Test fun aSharedAncestorObserverStillRoutesLegacyFolderCreation() {
        assertEquals(listOf(legacy), AchievementWatcher.affectedFiles(File("/wine/Roaming/Goldberg SteamEmu Saves"), listOf(gse, legacy)))
    }
    @Test fun actualUnlockAndProfileDirectoryRecreationRemainObservable() {
        assertEquals(listOf(gse), AchievementWatcher.affectedFiles(gse, listOf(gse, legacy)))
        assertEquals(listOf(gse, legacy), AchievementWatcher.affectedFiles(File("/wine/Roaming"), listOf(gse, legacy)))
    }
}
