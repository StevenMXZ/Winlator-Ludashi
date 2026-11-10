package com.winlator.cmod.steam

import com.winlator.cmod.container.Container
import com.winlator.cmod.container.Shortcut
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], shadows = [com.winlator.cmod.container.AudioDriverMigrationTest.GpuShadow::class])
class SteamModesTest {
    @Test fun disablingSteamKeepsDataButRejectsSteamLaunchAndPersistsFlag() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/99811.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        SteamSettings.setMode(context, 99811, "Raw")
        SteamSettings.setEnabled(context, false)
        assertFalse(SteamSettings.enabled(context))
        assertTrue(marker.isFile)
        assertEquals("Raw", SteamSettings.mode(context, 99811))
        SteamSettings.setEnabled(context, true)
        assertTrue(SteamSettings.enabled(context))
    }
    @Test fun rawShortcutTargetsGameAndRetainsCustomSettingsAcrossPreparation() {
        val context = RuntimeEnvironment.getApplication()
        SteamSettings.setEnabled(context, true)
        val game = File(context.filesDir, "steam-library/common/Test Raw").apply { mkdirs() }
        File(game, "game.exe").writeText("test exe")
        val container = Container(99812).apply { rootDir = File(context.filesDir, "test-container").apply { mkdirs() } }
        SteamSettings.setMode(context, 99812, "Raw")
        val info = JSONObject("""{"config":{"installdir":"Test Raw"}}""")
        val file = SteamLaunch.createShortcut(context, container, 99812, info, "game.exe")
        Shortcut(container, file).apply { putExtra("envVars", "CUSTOM=1"); putExtra("execArgs", "-windowed"); saveData() }
        SteamLaunch.createShortcut(context, container, 99812, info, "game.exe")
        val result = Shortcut(container, file)
        assertEquals("Raw", result.getExtra("steamLaunchMode"))
        assertTrue(result.path.endsWith("game.exe"))
        assertEquals("CUSTOM=1", result.getExtra("envVars"))
        assertEquals("-windowed", result.getExtra("execArgs"))
        assertFalse(SteamLaunch.canLaunch(context, result))
        result.putExtra("steamConfigured", "1"); result.saveData()
        assertTrue(SteamLaunch.canLaunch(context, result))
        SteamSettings.setMode(context, 99812, "RealSteam")
        assertFalse("A pinned shortcut must not launch a different mode than the Play selection", SteamLaunch.canLaunch(context, result))
        SteamSettings.setMode(context, 99812, "Raw")
        SteamSettings.setEnabled(context, false)
        assertFalse(SteamLaunch.canLaunch(context, result))
        SteamSettings.setEnabled(context, true)
        assertFalse(File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam/steam.exe").exists())
    }
    @Test fun goldbergSwapsWithBackupAndSwitchBackRestoresOriginal() {
        val context = RuntimeEnvironment.getApplication()
        val component = File(context.filesDir, "steam-components/goldberg/regular/x64").apply { mkdirs() }
        File(component, "steam_api64.dll").writeText("replacement")
        File(component.parentFile, "x86/steam_api.dll").apply { parentFile!!.mkdirs(); writeText("replacement32") }
        val game = File(context.filesDir, "goldberg-test").apply { mkdirs() }
        val original = File(game, "steam_api64.dll").apply { writeText("original") }
        SteamGoldberg.prepare(context, game, 99813)
        assertEquals("replacement", original.readText())
        assertEquals("original", File(game, "steam_api64.dll.ludashi-original").readText())
        SteamGoldberg.restore(game)
        assertEquals("original", original.readText())
    }
    @Test fun handoffPausesAppSessionAndPreventsDisablingMidGame() {
        val context = RuntimeEnvironment.getApplication()
        SteamSettings.setEnabled(context, true)
        SteamSession.beginGame(context)
        try {
            assertTrue(SteamSession.gameActive)
            assertNull(SteamSession.session)
            assertThrows(IllegalStateException::class.java) { SteamSettings.setEnabled(context, false) }
            assertTrue(SteamSettings.enabled(context))
        } finally { SteamSession.endGame(context) }
        assertFalse(SteamSession.gameActive)
    }
    @Test fun nativeDownloadSignatureIncludesSeparateDecompressionBudget() {
        val method = com.winlator.cmod.feature.stores.steam.ludashisteam.LudashiSteamSession::class.java.declaredMethods.single { it.name == "nativeDownloadApp" }
        assertEquals(11, method.parameterTypes.size)
        assertEquals(Int::class.javaPrimitiveType, method.parameterTypes[9])
    }
    @Test fun preparingRenamedSteamShortcutRetainsItsSettingsAndFilename() {
        val context = RuntimeEnvironment.getApplication()
        SteamSettings.setEnabled(context, true)
        val game = File(context.filesDir, "steam-library/common/Renamed Game").apply { mkdirs() }
        File(game, "game.exe").writeText("exe")
        val container = Container(99814).apply { rootDir = File(context.filesDir, "rename-container").apply { mkdirs() } }
        SteamSettings.setMode(context, 99814, "Raw")
        val info = JSONObject("""{"config":{"installdir":"Renamed Game"}}""")
        val original = SteamLaunch.createShortcut(context, container, 99814, info, "game.exe")
        Shortcut(container, original).apply { putExtra("envVars", "KEEP=1"); saveData() }
        val renamed = File(original.parentFile, "My Game.desktop")
        assertTrue(original.renameTo(renamed))
        val prepared = SteamLaunch.createShortcut(context, container, 99814, info, "game.exe")
        assertEquals(renamed, prepared)
        assertEquals("KEEP=1", Shortcut(container, prepared).getExtra("envVars"))
        assertFalse(original.exists())
    }
    @Test fun launchRestoresOriginalDllsEvenWhenPinnedShortcutDidNotReopenConfiguration() {
        val context = RuntimeEnvironment.getApplication()
        SteamSettings.setEnabled(context, true)
        val game = File(context.filesDir, "steam-library/common/Pinned Restore").apply { mkdirs() }
        File(game, "steam_api64.dll").writeText("offline replacement")
        File(game, "steam_api64.dll.ludashi-original").writeText("original API")
        val container = Container(99820).apply { rootDir = File(context.filesDir, "pinned-container").apply { mkdirs() } }
        container.desktopDir.mkdirs()
        val file = File(container.desktopDir, "Pinned.desktop").apply { writeText("[Desktop Entry]\nName=Pinned\nExec=wine game.exe\n\n[Extra Data]\n") }
        val shortcut = Shortcut(container, file).apply {
            putExtra("steamAppId", "99820"); putExtra("steamConfigured", "1"); putExtra("steamLaunchMode", "Raw"); saveData()
        }
        File(context.filesDir, "steam-library/99820.json").writeText("""{"config":{"installdir":"Pinned Restore"}}""")
        try {
            SteamLaunch.apply(context, shortcut, com.winlator.cmod.core.EnvVars())
            assertEquals("original API", File(game, "steam_api64.dll").readText())
            assertTrue(File(game, "steam_api64.dll.ludashi-original").isFile)
        } finally { SteamSession.endGame(context) }
    }

}
