package com.winlator.cmod.steam

import android.content.res.Configuration
import android.view.View
import android.view.WindowManager
import com.winlator.cmod.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamResponsiveSettingsTest {
    @Test fun oldMediumChoiceMigratesToFastWithoutRenumberingBlazing() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("steam-settings", 0)
        preferences.edit().putInt("download-tier", 1).commit()
        assertEquals(2, SteamDownloadSettings.tier(context))
        assertEquals(2, preferences.getInt("download-tier", -1))
        SteamDownloadSettings.setTier(context, 3)
        assertEquals(3, SteamDownloadSettings.tier(context))
        assertFalse(SteamDownloadSettings.tiers.contains(1))
        assertThrows(IllegalArgumentException::class.java) { SteamDownloadSettings.setTier(context, 1) }
    }
    @Test fun portugueseResourcesIncludeCorrectlyFormattedLibraryPercentage() {
        val context = RuntimeEnvironment.getApplication()
        val portuguese = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("pt-BR")) })
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        assertEquals("Steam / Lojas", portuguese.getString(R.string.steam_ui_stores))
        assertEquals("Carregando biblioteca Steam… 50%", portuguese.getString(R.string.steam_ui_loading_library, 50))
        assertEquals("Entrar na Steam", portuguese.getString(R.string.steam_ui_login_title))
        assertEquals("Conectado", portuguese.getString(R.string.steam_ui_connected))
        assertEquals("Steam / Stores", english.getString(R.string.steam_ui_stores))
    }
    @Suppress("DEPRECATION")
    @Test fun immersiveFlagsAreRestoredAfterReturningFromAnotherWindow() {
        val controller = Robolectric.buildActivity(ImmersiveSteamActivity::class.java)
        controller.get().setTheme(R.style.AppThemeFullscreen)
        val activity = controller.setup().get()
        try {
            activity.window.decorView.systemUiVisibility = 0
            activity.onWindowFocusChanged(true)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN != 0)
            val visibility = activity.window.decorView.systemUiVisibility
            assertTrue(visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
            assertTrue(visibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0)
        } finally { controller.pause().stop().destroy() }
    }
}
