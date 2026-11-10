package com.winlator.cmod.steam

import android.content.Context
import androidx.compose.runtime.*
import com.winlator.cmod.ui.library.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun rememberSteamLibrary(context: Context, json: String, enabled: Boolean = true, filterType: String = "game"): List<LibraryItem> {
    val changes = SteamGames.version
    val artwork = SteamLibrary.artworkVersion
    val items by produceState(emptyList<LibraryItem>(), json, enabled, changes, artwork, filterType) {
        value = if (enabled) withContext(Dispatchers.IO) { SteamLibrary.items(context, json, filterType) } else emptyList()
    }
    return items
}

internal data class SteamUserSnapshot(val profile: JSONObject = JSONObject(), val owned: Map<Int, JSONObject> = emptyMap(), val achievements: Map<Int, List<SteamUserData.Achievement>> = emptyMap())
@Composable internal fun rememberSteamUserSnapshot(context: Context): SteamUserSnapshot {
    val revision = SteamUserData.contentRevision.intValue
    val snapshot by produceState(SteamUserSnapshot(), revision) {
        value = withContext(Dispatchers.IO) {
            val games = SteamUserData.owned(context)
            SteamUserSnapshot(SteamUserData.profile(context), (0 until games.length()).map { games.getJSONObject(it) }.associateBy { it.optInt("appId") }, SteamUserData.cachedAchievements(context))
        }
    }
    return snapshot
}

internal data class SteamGameSnapshot(val ready: Boolean = false, val info: JSONObject = JSONObject(), val play: JSONObject? = null, val achievements: List<SteamUserData.Achievement>? = null)
@Composable internal fun rememberSteamGameSnapshot(context: Context, id: Int): SteamGameSnapshot {
    val revision = SteamUserData.contentRevision.intValue
    val snapshot by produceState(SteamGameSnapshot(), id, revision) {
        value = withContext(Dispatchers.IO) {
            SteamGameSnapshot(true, runCatching { SteamGames.info(context, id) }.getOrDefault(JSONObject()), SteamUserData.game(context, id),
                SteamUserData.achievementData(context, id)?.let { SteamUserData.parseAchievements(it, id) })
        }
    }
    return snapshot
}
