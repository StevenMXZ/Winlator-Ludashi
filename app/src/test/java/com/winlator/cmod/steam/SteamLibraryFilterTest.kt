package com.winlator.cmod.steam

import com.winlator.cmod.ui.library.LibraryFilter
import com.winlator.cmod.ui.library.LibraryItem
import com.winlator.cmod.ui.library.filterLibraryItems
import com.winlator.cmod.ui.library.filterLibraryStores
import com.winlator.cmod.ui.library.steamLibraryFilterAvailable
import org.junit.Assert.*
import org.junit.Test

class SteamLibraryFilterTest {
    @Test fun steamFilterTracksAccountSessionAndFeatureState() {
        assertFalse(steamLibraryFilterAvailable(true, false, false))
        assertTrue(steamLibraryFilterAvailable(true, true, false))
        assertTrue(steamLibraryFilterAvailable(true, false, true))
        assertFalse(steamLibraryFilterAvailable(false, true, true))
    }

    private fun item(path: String, favorite: Boolean = true) = LibraryItem(path, path, path, "", null, null, null, null, favorite, 0)
    @Test fun allIncludesConnectedStoresAndFavoritesRemainIndependent() {
        val local = item("/game.desktop")
        val steam = item("steam://42")
        val items = listOf(local, steam)
        assertEquals(listOf(local, steam), filterLibraryItems(items, LibraryFilter.All))
        assertEquals(listOf(local, steam), filterLibraryItems(items, LibraryFilter.Favorites))
        assertEquals(listOf(local, steam), filterLibraryItems(items, LibraryFilter.Recent))
        assertEquals(listOf(steam), filterLibraryItems(items, LibraryFilter.Steam))
    }
    @Test fun emptyLocalLibraryDoesNotHideTheSteamTabContents() {
        val steam = item("steam://42")
        assertEquals(listOf(steam), filterLibraryItems(listOf(steam), LibraryFilter.All))
        assertEquals(listOf(steam), filterLibraryItems(listOf(steam), LibraryFilter.Steam))
    }
    @Test fun installedSteamGamesAlsoAppearInAll() {
        val installed = item("steam://42").copy(containerName = "Steam · Installed")
        val pending = item("steam://43")
        assertEquals(listOf(installed, pending), filterLibraryItems(listOf(installed, pending), LibraryFilter.All))
        assertEquals(listOf(installed, pending), filterLibraryItems(listOf(installed, pending), LibraryFilter.Steam))
    }
    @Test fun storeSelectionIncludesLocalSteamAndGogIndependently() {
        val local = item("/game.desktop")
        val steam = item("steam://42")
        val gog = item("gog://12345678901")
        val items = listOf(local, steam, gog)
        assertEquals(items, filterLibraryStores(items, 7))
        assertEquals(listOf(steam), filterLibraryStores(items, 2))
        assertEquals(listOf(gog), filterLibraryStores(items, 4))
        assertEquals(listOf(steam, gog), filterLibraryStores(items, 6))
        assertTrue(filterLibraryStores(items, 0).isEmpty())
        assertEquals(listOf(gog, local), filterLibraryItems(listOf(local, gog.copy(lastRunAt = 100)), LibraryFilter.Recent).map { it.copy(lastRunAt = 0) })
    }
    @Test fun installedGamesFromEveryStoreAppearAboveUninstalledGamesInAll() {
        val steam = item("steam://1")
        val gog = item("gog://1234").copy(containerName = "GOG · Installed")
        val local = item("/local.desktop")
        val itch = item("itch://example")
        val amazon = item("amazon://example").copy(containerName = "Amazon · Installed")
        assertEquals(listOf(gog, local, amazon, steam, itch), filterLibraryItems(listOf(steam, gog, local, itch, amazon), LibraryFilter.All))
        assertEquals(listOf(itch, amazon), filterLibraryStores(listOf(steam, gog, local, itch, amazon), 24))
    }

}
