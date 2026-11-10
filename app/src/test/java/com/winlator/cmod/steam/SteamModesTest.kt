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
        assertTrue(SteamLaunch.canLaunch(context, result))
        SteamSettings.setEnabled(context, false)
        assertFalse(SteamLaunch.canLaunch(context, result))
        SteamSettings.setEnabled(context, true)
        assertFalse(File(container.rootDir, ".wine/drive_c/Program Files (x86)/Steam/steam.exe").exists())
    }
    @Test fun goldbergSwapsWithBackupAndSwitchBackRestoresOriginal() {
        val context = RuntimeEnvironment.getApplication()
        val component = File(context.filesDir, "steam-components/goldberg/regular/x64").apply { mkdirs() }
        File(component, "steam_api64.dll").writeText("replacement")
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
}
