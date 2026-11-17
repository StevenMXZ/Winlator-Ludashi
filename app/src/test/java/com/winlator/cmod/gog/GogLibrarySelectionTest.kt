package com.winlator.cmod.gog

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GogLibrarySelectionTest {
    @Test fun mainLibraryIncludesInstalledAndManuallySelectedGamesWhileStoreKeepsAllGames() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("gog-settings", 0)
        prefs.edit().clear().commit()
        val installed = GogGame("920001", "Installed", "")
        val selected = GogGame("920002", "Selected", "")
        val favorite = GogGame("920003", "Favorite", "")
        val snapshot = GogState(games = listOf(selected, favorite), installed = mapOf(installed.id to installed.json()))
        try {
            prefs.edit().putBoolean("favorite-${favorite.id}", true).commit()
            assertEquals(listOf(installed), GogLibrary.libraryGames(context, snapshot, mainLibraryOnly = true))
            GogLibrary.setAdded(context, selected.id, true)
            assertTrue(GogLibrary.added(context, selected.id))
            assertEquals(listOf(selected, installed), GogLibrary.libraryGames(context, snapshot, mainLibraryOnly = true))
            assertEquals(listOf(selected, favorite, installed), GogLibrary.libraryGames(context, snapshot))
            GogLibrary.setAdded(context, selected.id, false)
            assertEquals(listOf(installed), GogLibrary.libraryGames(context, snapshot, mainLibraryOnly = true))
        } finally { prefs.edit().clear().commit() }
    }
}
