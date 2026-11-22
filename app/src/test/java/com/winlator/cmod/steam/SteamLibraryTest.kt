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
    @Test fun locallyInstalledGameSurvivesIncompleteSteamMetadataAndInvalidSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/789126.json")
        marker.parentFile!!.mkdirs()
        marker.writeText("""{"common":{"name":"Installed locally","type":"game"}}""")
        try {
            val incomplete = """{"owned_apps":[{"id":789126,"name":"","type":""}]}"""
            val item = SteamLibrary.items(context, incomplete).single { it.id == "steam-789126" }
            assertEquals("Installed locally", item.name)
            assertEquals("Steam · Installed", item.containerName)
            assertEquals("steam://789126", item.shortcutPath)
            assertTrue(SteamLibrary.items(context, "{invalid").any { it.id == "steam-789126" })
        } finally { marker.delete() }
    }

    @Test fun partialRemoteListCannotHideInstalledGames() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/789127.json")
        marker.parentFile!!.mkdirs()
        marker.writeText("""{"common":{"name":"Keep me in All","type":"game"}}""")
        try {
            val partial = """{"owned_apps":[{"id":123456,"name":"Another game","type":""}]}"""
            val list = SteamLibrary.items(context, partial)
            assertTrue(list.any { it.id == "steam-789127" && it.containerName == "Steam · Installed" })
            assertFalse(list.any { it.id == "steam-123456" })
        } finally { marker.delete() }
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

    @Test fun installedDemoAppearsWithoutAnOwnedLibraryEntryOrShortcut() {
        val context = RuntimeEnvironment.getApplication()
        val marker = File(context.filesDir, "steam-library/3714190.json")
        marker.parentFile!!.mkdirs()
        marker.writeText("""{"common":{"name":"NINJA GAIDEN: Ragebound Demo","type":"Demo"}}""")
        try {
            val snapshot = """{"owned_apps":[{"id":3564860,"name":"Stellar Blade Demo","type":"demo"}]}"""
            val shortcuts = SteamLibrary.items(context, snapshot, mainLibraryOnly = true, includeDemos = true)
            assertEquals(listOf("steam://3714190"), shortcuts.map { it.shortcutPath })
            assertEquals("Steam · Installed", SteamLibrary.items(context, snapshot, filterType = "demo")
                .single { it.shortcutPath == "steam://3714190" }.containerName)
            assertTrue(SteamLibrary.items(context, snapshot).isEmpty())
        } finally { marker.delete() }
    }

    @Test fun artworkUsesPublishedHashedAssetsAndCommunityIcon() {
        val common = org.json.JSONObject("""{
            "library_assets_full":{"library_capsule":{"image2x":{"english":"de8119957c34795d7bdd949a87a836e76ad1e662/library_600x900_2x.jpg"}}},
            "header_image":{"english":"02d95cefbfabfdf69ea0563164f90b14d1db98e5/header.jpg"},
            "icon":"77b83cc2b8e63a0aca31ddefc7cc4ced5cfa3bcc"
        }""")
        val urls = SteamArtwork.fromCommon(3564860, common, "cover")
        assertTrue(urls.first().endsWith("/3564860/de8119957c34795d7bdd949a87a836e76ad1e662/library_600x900_2x.jpg"))
        assertTrue(urls.last().endsWith("/3564860/77b83cc2b8e63a0aca31ddefc7cc4ced5cfa3bcc.jpg"))
        assertFalse(SteamArtwork.isSteamAsset("https://shared.steamstatic.com.invalid/art.jpg"))
    }
}
