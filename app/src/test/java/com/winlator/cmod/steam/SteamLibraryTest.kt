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
class SteamLibraryTest {
    @Test fun ownedSteamGamesUseRoutableLibraryEntriesAndExcludeDlc() {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = """{"owned_apps":[{"id":789123,"name":"Owned game","type":"game"},{"id":789124,"name":"DLC","type":"dlc"}]}"""
        val items = SteamLibrary.items(context, snapshot)
        assertEquals(1, items.size)
        assertEquals("Owned game", items.single().name)
        assertEquals(789123, SteamLibrary.appId(items.single().shortcutPath))
        assertTrue(items.single().containerName.contains("Not installed"))
        assertNull(SteamLibrary.appId("/local/game.desktop"))
    }
    @Test fun completedDownloadAppearsInstalledInMainLibrary() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/789125.json")
        marker.parentFile!!.mkdirs(); marker.writeText("{}")
        try {
            val items = SteamLibrary.items(context, """{"owned_apps":[{"id":789125,"name":"Installed","type":"game"}]}""")
            assertEquals("Steam · Installed", items.single().containerName)
        } finally { marker.delete() }
    }
}
