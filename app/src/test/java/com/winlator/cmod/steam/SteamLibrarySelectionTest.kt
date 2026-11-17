package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamLibrarySelectionTest {
    @Test fun mainLibraryIncludesInstalledAndSelectedGamesWithoutChangingSteamCatalog() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("steam-settings", 0)
        prefs.edit().clear().commit()
        val record = File(context.filesDir, "steam-library/910001.json").apply {
            parentFile.mkdirs(); writeText("""{"common":{"name":"Installed","type":"game"}}""")
        }
        val json = """{"owned_apps":[{"id":910001,"name":"Installed","type":"game"},{"id":910002,"name":"Selected","type":"game"},{"id":910003,"name":"Favorite","type":"game"}]}"""
        try {
            prefs.edit().putBoolean("favorite-910003", true).commit()
            assertEquals(listOf("Installed"), SteamLibrary.items(context, json, mainLibraryOnly = true).map { it.name })
            SteamLibrary.setAdded(context, 910002, true)
            assertTrue(SteamLibrary.added(context, 910002))
            assertEquals(listOf("Installed", "Selected"), SteamLibrary.items(context, json, mainLibraryOnly = true).map { it.name })
            assertEquals(3, SteamLibrary.items(context, json).size)
            SteamLibrary.setAdded(context, 910002, false)
            assertEquals(listOf("Installed"), SteamLibrary.items(context, json, mainLibraryOnly = true).map { it.name })
        } finally { record.delete(); prefs.edit().clear().commit() }
    }
}
