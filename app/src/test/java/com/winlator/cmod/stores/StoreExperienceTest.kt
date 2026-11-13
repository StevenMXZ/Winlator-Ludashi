package com.winlator.cmod.stores

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import com.winlator.cmod.ui.bindOverlayOwners

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StoreExperienceTest {
    @Test fun savesAndUninstallOverlaysHaveTheirOwnActivityOwners() {
        val controller = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        try {
            val activity = controller.get()
            val overlay = android.view.View(activity)
            assertNull(overlay.findViewTreeLifecycleOwner())
            overlay.bindOverlayOwners(activity)
            assertSame(activity, overlay.findViewTreeLifecycleOwner())
            assertSame(activity, overlay.findViewTreeViewModelStoreOwner())
            assertSame(activity, overlay.findViewTreeSavedStateRegistryOwner())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun prefersAppLanguageThenEnglishWithoutChangingTheAvailableChoices() {
        val versions = listOf("de", "en", "pt_BR").map { JSONObject().put("language", it) }
        assertEquals(2, StoreDownloadPreferences.installerIndex(versions, Locale.forLanguageTag("pt-BR")))
        assertEquals(1, StoreDownloadPreferences.installerIndex(versions, Locale.JAPANESE))
        assertEquals(0, StoreDownloadPreferences.installerIndex(versions, Locale.GERMAN))
        assertEquals(1, StoreDownloadPreferences.installerIndex(listOf("de", "english").map { JSONObject().put("language", it) }, Locale.FRENCH))
    }
    @Test fun executablePickerPrioritizesTheGameAndExcludesRedistributables() {
        val files = listOf("__redist/ISI/scriptinterpreter.exe", "Moonscars.exe", "UnityCrashHandler64.exe")
        assertEquals("Moonscars.exe", StoreExecutables.default(files, "Moonscars"))
        assertEquals(listOf("Moonscars.exe"), StoreExecutables.ordered(files, "Moonscars").filterNot(StoreExecutables::helper))
        assertTrue(StoreExecutables.helper("_CommonRedist/vcredist/vcredist_x86.exe"))
        assertEquals("", StoreExecutables.default(listOf("game32.exe", "game64.exe"), "Unknown"))
    }
    @Test fun changingExecutablePreservesShortcutConfiguration() {
        val text = "[Desktop Entry]\nName=Game\nExec=wine old.exe\nIcon=gog-1\n[Extra Data]\ngraphicsDriver=turnip\nscreenSize=1280x720\nexecArgs=-old\n[Other]\nvalue=keep\n"
        val updated = updateStoreDesktop(updateStoreDesktop(text, "Desktop Entry", mapOf("Exec" to "wine new.exe")), "Extra Data", mapOf("execArgs" to "-new"))
        assertTrue(updated.contains("Exec=wine new.exe"))
        assertFalse(updated.contains("old.exe"))
        assertTrue(updated.contains("graphicsDriver=turnip"))
        assertTrue(updated.contains("screenSize=1280x720"))
        assertTrue(updated.contains("execArgs=-new"))
        assertTrue(updated.contains("[Other]\nvalue=keep"))
    }
    @Test fun transferSpeedAndEtaUseMeasuredBytesAndResetAfterRetry() {
        var now = 0L
        val meter = TransferMeter { now }
        now = 1000
        assertEquals(1048576, meter.update(1048576))
        assertEquals(9L, remainingTime(1048576, 10L * 1048576, meter.speed))
        now = 2000
        assertEquals(0, meter.update(0))
        assertNull(remainingTime(0, 0, 0))
        assertNull(remainingTime(100, 100, 50))
    }
    @Test fun apiLibraryKeepsOwnershipForTheCorrectWindowsDownload() {
        val rows = JSONArray("""[{"id":123,"game":{"id":456,"title":"My game","url":"https://author.itch.io/game","cover_url":"https://img.itch.zone/cover.png"}}]""")
        val game = ItchApi.games(rows).single()
        assertEquals("456", game.metadata.getString("itchId"))
        assertEquals("123", game.metadata.getString("downloadKey"))
        assertEquals("https://author.itch.io/game", game.id)
        val uploads = ItchApi.uploads(JSONArray("""[{"id":1,"filename":"game-win.zip","platforms":{"windows":true},"size":42},{"id":2,"filename":"game-linux.zip","platforms":{"linux":true}},{"id":3,"filename":"data.exe","storage":"external"}]"""))
        assertEquals(listOf("1"), uploads.map { it.id })
        assertEquals("https://api.itch.io/uploads/1/download?download_key_id=123", ItchApi.download(game, uploads.single()))
    }
}
