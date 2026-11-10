package com.winlator.cmod.steam

import com.winlator.cmod.ui.library.LibraryItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamOrderingTest {
    private fun item(id: Int) = LibraryItem("$id", "steam://$id", "Game $id", "", null, null, null, null, false, 0L)
    @Test fun recentSortMixesGamesWithAndWithoutHistoryWithoutClassCast() {
        val a = item(1); val b = item(2); val c = item(3)
        val owned = mapOf(1 to JSONObject().put("rtimeLastPlayed", 2_200_000_000L), 3 to JSONObject().put("rtimeLastPlayed", 10))
        assertEquals(listOf(a, c, b), sortSteamLibrary(listOf(b, c, a), owned, "Recent"))
    }
    @Test fun playtimeSortHandlesMissingHistoryAndLargeTotals() {
        val a = item(1); val b = item(2); val c = item(3)
        val owned = mapOf(1 to JSONObject().put("playtimeForever", 2_200_000_000L), 3 to JSONObject().put("playtimeForever", 1))
        assertEquals(listOf(a, c, b), sortSteamLibrary(listOf(c, b, a), owned, "Playtime"))
    }
    @Test fun emptyHistoryUsesStableNameOrdering() {
        val a = item(1); val b = item(2)
        assertEquals(listOf(a, b), sortSteamLibrary(listOf(b, a), emptyMap(), "Recent"))
    }
}
