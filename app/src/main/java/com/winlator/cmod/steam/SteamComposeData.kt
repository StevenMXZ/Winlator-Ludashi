package com.winlator.cmod.steam

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.winlator.cmod.ui.library.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable internal fun rememberSteamArtwork(id: Int, kind: String): String? {
    val context = LocalContext.current.applicationContext
    val revision = SteamLibrary.artworkVersion
    val path = remember(context, id, kind, revision) { SteamLibrary.artworkPath(context, id, kind) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, context, id, kind, path) {
        if (path == null && id > 0) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SteamLibrary.requestArtwork(context, "steam://$id", kind)
        }
    }
    return path
}

@Composable internal fun SteamAppArt(id: Int, kind: String, modifier: Modifier) {
    SteamArt(rememberSteamArtwork(id, kind).orEmpty(), modifier)
}

@Composable fun rememberSteamLibrary(context: Context, json: String, enabled: Boolean = true, filterType: String = "game", mainLibraryOnly: Boolean = false, includeDemos: Boolean = false): List<LibraryItem> {
    val changes = SteamGames.version
    val artwork = SteamLibrary.artworkVersion
    val items by produceState(emptyList<LibraryItem>(), json, enabled, changes, artwork, filterType, mainLibraryOnly, includeDemos) {
        value = if (enabled) withContext(Dispatchers.IO) { SteamLibrary.items(context, json, filterType, mainLibraryOnly, includeDemos) } else emptyList()
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
