package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [com.winlator.cmod.container.AudioDriverMigrationTest.GpuShadow::class])
class SteamUninstallTest {
    @Test fun uninstallRemovesGameButNeverFollowsLinksIntoOtherFiles() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/871001.json").apply { parentFile!!.mkdirs(); writeText("""{"config":{"installdir":"Uninstall Test"}}""") }
        val game = File(context.filesDir, "steam-library/common/Uninstall Test").apply { mkdirs() }
        File(game, "game.exe").writeText("game")
        val outside = File(context.filesDir, "unrelated-saves").apply { mkdirs() }
        val save = File(outside, "save.dat").apply { writeText("keep") }
        Files.createSymbolicLink(File(game, "linked-save").toPath(), outside.toPath())
        SteamGames.uninstall(context, 871001)
        assertFalse(game.exists()); assertFalse(marker.exists()); assertEquals("keep", save.readText())
    }
    @Test fun invalidInstallPathCannotDeleteOutsideTheSteamLibrary() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/871002.json").apply { parentFile!!.mkdirs(); writeText("""{"config":{"installdir":"../outside"}}""") }
        assertThrows(IllegalArgumentException::class.java) { SteamGames.uninstall(context, 871002) }
        assertTrue(marker.exists())
    }
    @Test fun symlinkedInstallationOutsideLibraryIsRejected() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/871003.json").apply { parentFile!!.mkdirs(); writeText("""{"config":{"installdir":"Linked Test"}}""") }
        val outside = File(context.filesDir, "outside-game").apply { mkdirs() }
        val exe = File(outside, "game.exe").apply { writeText("keep") }
        val game = File(context.filesDir, "steam-library/common/Linked Test").apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(game.toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { SteamGames.uninstall(context, 871003) }
        assertEquals("keep", exe.readText()); assertTrue(marker.exists())
    }
}
