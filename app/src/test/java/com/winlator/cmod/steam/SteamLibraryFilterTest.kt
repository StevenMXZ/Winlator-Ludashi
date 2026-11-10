package com.winlator.cmod.steam

import com.winlator.cmod.ui.library.LibraryFilter
import com.winlator.cmod.ui.library.LibraryItem
import com.winlator.cmod.ui.library.filterLibraryItems
import org.junit.Assert.*
import org.junit.Test

class SteamLibraryFilterTest {
    private fun item(path: String, favorite: Boolean = true) = LibraryItem(path, path, path, "", null, null, null, null, favorite, 0)
    @Test fun steamGamesAppearOnlyInTheirDedicatedTab() {
        val local = item("/game.desktop")
        val steam = item("steam://42")
        val items = listOf(local, steam)
        assertEquals(listOf(local), filterLibraryItems(items, LibraryFilter.All))
        assertEquals(listOf(local), filterLibraryItems(items, LibraryFilter.Favorites))
        assertEquals(listOf(local), filterLibraryItems(items, LibraryFilter.Recent))
        assertEquals(listOf(steam), filterLibraryItems(items, LibraryFilter.Steam))
    }
    @Test fun emptyLocalLibraryDoesNotHideTheSteamTabContents() {
        val steam = item("steam://42")
        assertTrue(filterLibraryItems(listOf(steam), LibraryFilter.All).isEmpty())
        assertEquals(listOf(steam), filterLibraryItems(listOf(steam), LibraryFilter.Steam))
    }
}
