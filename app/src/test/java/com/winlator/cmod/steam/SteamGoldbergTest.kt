package com.winlator.cmod.steam

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamGoldbergTest {
    @Test fun gamesWithoutSteamApiCanPrepareOfflineWithoutDownloadingAnEmulator() {
        val context = RuntimeEnvironment.getApplication()
        val game = File(context.cacheDir, "offline-no-api").apply { mkdirs() }
        val exe = File(game, "game.exe").apply { writeText("original executable") }
        val stages = mutableListOf<String>()
        SteamGoldberg.prepare(context, game, 42) { stage, _, _ -> stages.add(stage) }
        assertEquals(listOf("prepare"), stages)
        assertEquals("original executable", exe.readText())
        assertEquals(listOf("game.exe"), game.list()!!.toList())
    }
    @Test fun refreshedDllIndexFindsFilesAddedAfterAnEarlierScan() {
        val context = RuntimeEnvironment.getApplication()
        val game = File(context.cacheDir, "offline-new-api").apply { mkdirs() }
        assertTrue(SteamGoldberg.settingsDirectories(game).isEmpty())
        val bin = File(game, "bin").apply { mkdirs() }
        File(bin, "Steam_API.dll").writeText("original API")
        SteamGoldberg.invalidate(game)
        assertEquals(listOf(File(bin, "steam_settings")), SteamGoldberg.settingsDirectories(game))
    }
}
