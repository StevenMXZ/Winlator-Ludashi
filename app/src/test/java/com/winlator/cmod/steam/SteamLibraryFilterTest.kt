package com.winlator.cmod.steam

import com.winlator.cmod.ui.library.LibraryFilter
import com.winlator.cmod.ui.library.LibraryItem
import com.winlator.cmod.ui.library.filterLibraryItems
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
    @Test fun uninstalledSteamGamesStayOutOfAllButCanBeFavorited() {
        val local = item("/game.desktop")
        val steam = item("steam://42")
        val items = listOf(local, steam)
        assertEquals(listOf(local), filterLibraryItems(items, LibraryFilter.All))
        assertEquals(listOf(local, steam), filterLibraryItems(items, LibraryFilter.Favorites))
        assertEquals(listOf(local), filterLibraryItems(items, LibraryFilter.Recent))
        assertEquals(listOf(steam), filterLibraryItems(items, LibraryFilter.Steam))
    }
    @Test fun emptyLocalLibraryDoesNotHideTheSteamTabContents() {
        val steam = item("steam://42")
        assertTrue(filterLibraryItems(listOf(steam), LibraryFilter.All).isEmpty())
        assertEquals(listOf(steam), filterLibraryItems(listOf(steam), LibraryFilter.Steam))
    }
    @Test fun installedSteamGamesAlsoAppearInAll() {
        val installed = item("steam://42").copy(containerName = "Steam · Installed")
        val pending = item("steam://43")
        assertEquals(listOf(installed), filterLibraryItems(listOf(installed, pending), LibraryFilter.All))
        assertEquals(listOf(installed, pending), filterLibraryItems(listOf(installed, pending), LibraryFilter.Steam))
    }
}
