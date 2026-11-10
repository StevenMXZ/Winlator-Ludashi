package com.winlator.cmod.steam

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object SteamStoreMetadata {
    fun fetch(id: Int): JSONObject {
        val connection = URL("https://store.steampowered.com/api/appdetails?appids=$id&cc=us&l=english").openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode != 200) return JSONObject()
            val text = connection.inputStream.bufferedReader().use { it.readText().take(1500000) }
            JSONObject(text).optJSONObject(id.toString())?.optJSONObject("data") ?: JSONObject()
        } finally { connection.disconnect() }
    }
    fun cloudListing(id: Int): JSONObject {
        val future = CompletableFuture<JSONObject>()
        SteamSession.work {
            try {
                check(SteamSession.signedIn && !SteamSession.gameActive)
                future.complete(JSONObject(SteamSession.session?.getCloudFileList(id) ?: error("Steam Cloud unavailable")))
            } catch (error: Throwable) { future.completeExceptionally(error) }
        }
        return future.get(35, TimeUnit.SECONDS)
    }
}

@Composable
internal fun SteamStoreDetails(id: Int, installed: Boolean, state: SteamUiSnapshot) {
    val context = LocalContext.current
    var tab by remember(id) { mutableIntStateOf(0) }
    val data by produceState(Pair(JSONObject(), JSONObject()), id, state.signedIn, installed) {
        value = withContext(Dispatchers.IO) {
            Pair(
                runCatching { if (installed) SteamGames.info(context, id) else if (state.signedIn) SteamSession.info(id) else JSONObject() }.getOrDefault(JSONObject()),
                runCatching { SteamStoreMetadata.fetch(id) }.getOrDefault(JSONObject())
            )
        }
    }
    val pics = data.first
    val store = data.second
    val cached = rememberSteamGameSnapshot(context, id)
    val ach = cached.achievements
    var showCloud by remember(id) { mutableStateOf(false) }
    val shortcut = remember(installed, id, SteamGames.version) {
        if (installed) SteamGames.shortcut(context, id) else null
    }
    val tabs = listOf(
        uiStr(R.string.steam_store_details),
        uiStr(R.string.steam_ui_achievements),
        uiStr(R.string.steam_store_dlc),
        uiStr(R.string.steam_store_cloud),
        uiStr(R.string.steam_store_media)
    )
    LaunchedEffect(tab, id, state.signedIn) {
        if (tab == 1 && state.signedIn && !state.gameActive) SteamUserData.refreshAchievements(context, id)
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title, maxLines = 1) })
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 270.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (tab) {
                0 -> {
                    val description = store.optString("short_description")
                        .replace(Regex("<[^>]+>"), "").trim()
                    if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
                    val common = pics.optJSONObject("common")
                    val developers = store.optJSONArray("developers")
                    val publishers = store.optJSONArray("publishers")
                    for ((label, values) in listOf(uiStr(R.string.steam_ui_developer) to developers, uiStr(R.string.steam_ui_publisher) to publishers)) {
                        val names = (0 until (values?.length() ?: 0)).map { values!!.optString(it) }.filter { it.isNotBlank() }
                        if (names.isNotEmpty()) Text("$label: ${names.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    val release = store.optJSONObject("release_date")?.optString("date")
                        .orEmpty().ifBlank { common?.optString("steam_release_date").orEmpty() }
                    if (release.isNotBlank()) Text(uiStr(R.string.steam_ui_release, release), style = MaterialTheme.typography.bodySmall)
                    if (description.isBlank() && release.isBlank() && developers == null) Text(uiStr(R.string.steam_store_no_data))
                }
                1 -> {
                    if (ach == null) {
                        Text(uiStr(if (SteamUserData.busy("achievements-$id")) R.string.steam_ui_loading_achievements else R.string.steam_ui_connect_achievements))
                        if (state.signedIn && !state.gameActive)
                            TextButton(onClick = { SteamUserData.refreshAchievements(context, id, true) }) {
                                Text(uiStr(R.string.steam_ui_refresh_info))
                            }
                    } else {
                        Text(uiStr(R.string.steam_ui_ach_count, ach.count { it.unlocked }, ach.size))
                        ach.forEach { achievement ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(if (achievement.unlocked) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
                                    null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Column {
                                    Text(achievement.title, style = MaterialTheme.typography.labelLarge)
                                    if (achievement.description.isNotBlank())
                                        Text(achievement.description, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                2 -> {
                    val dlcJson = store.optJSONArray("dlc") ?: JSONArray()
                    val ids = (0 until dlcJson.length()).mapNotNull { dlcJson.optInt(it).takeIf { item -> item > 0 } }
                        .ifEmpty {
                            pics.optJSONObject("extended")?.optString("listofdlc").orEmpty()
                                .split(',').mapNotNull { it.trim().toIntOrNull() }
                        }
                    val dlcNames by produceState<Map<Int, String>>(emptyMap(), id, ids.joinToString(","), state.signedIn) {
                        value = if (!state.signedIn || ids.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
                            ids.take(16).mapNotNull { dlcId ->
                                val name = runCatching { SteamSession.info(dlcId).optJSONObject("common")?.optString("name") }
                                    .getOrNull().orEmpty()
                                if (name.isNotBlank()) dlcId to name else null
                            }.toMap()
                        }
                    }
                    if (ids.isEmpty()) Text(uiStr(R.string.steam_store_no_dlc))
                    else {
                        Text(uiStr(R.string.steam_store_dlc_count, ids.size), style = MaterialTheme.typography.bodySmall)
                        ids.take(60).forEachIndexed { index, dlcId ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp))
                                Text(dlcNames[dlcId].orEmpty().ifBlank { "DLC ${index + 1}" }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                3 -> {
                    val cloud by produceState<Pair<JSONObject?, String>>(null to "", id, state.signedIn, state.gameActive, tab) {
                        value = if (state.signedIn && !state.gameActive) withContext(Dispatchers.IO) {
                            runCatching { SteamStoreMetadata.cloudListing(id) }
                                .fold({ it to "" }, { null to (it.message ?: "Steam Cloud unavailable") })
                        } else null to ""
                    }
                    if (!state.signedIn) Text(uiStr(R.string.steam_ui_enable_signin))
                    else if (cloud.second.isNotBlank()) {
                        Text(cloud.second, color = MaterialTheme.colorScheme.error)
                        Text(uiStr(R.string.steam_cloud_client_fallback), style = MaterialTheme.typography.bodySmall)
                    } else if (cloud.first == null) Text(uiStr(R.string.steam_native_cloud_checking))
                    else {
                        val files = cloud.first?.optJSONArray("files") ?: JSONArray()
                        val remote = (0 until files.length()).map { files.getJSONObject(it) }.filter { it.optInt("persistState") == 0 }
                        Text(uiStr(R.string.steam_cloud_files_found, remote.size))
                        remote.maxOfOrNull { it.optLong("timestamp") }?.takeIf { it > 0 }?.let {
                            Text(uiStr(R.string.steam_cloud_save_date, DateFormat.getDateTimeInstance().format(Date(it * 1000))))
                        }
                    }
                    if (shortcut != null) {
                        Button(enabled = state.signedIn && !state.gameActive && !SteamNativeCloud.busy,
                            onClick = { showCloud = true }) { Text(uiStr(R.string.steam_cloud_saves)) }
                    } else Text(uiStr(R.string.steam_store_cloud_setup), style = MaterialTheme.typography.bodySmall)
                }
                4 -> {
                    val images = store.optJSONArray("screenshots") ?: JSONArray()
                    val movies = store.optJSONArray("movies") ?: JSONArray()
                    if (images.length() == 0 && movies.length() == 0) Text(uiStr(R.string.steam_store_no_media))
                    else {
                        val pictures = (0 until images.length()).mapNotNull { images.optJSONObject(it)?.optString("path_thumbnail")?.takeIf { url -> url.startsWith("https://") } }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            items(pictures) { url ->
                                SteamArt(url, Modifier.width(220.dp).aspectRatio(16f / 9f).clickable {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                })
                            }
                        }
                        if (movies.length() > 0) {
                            Text(uiStr(R.string.steam_store_videos))
                            for (i in 0 until movies.length()) {
                                val movie = movies.optJSONObject(i) ?: continue
                                val url = movie.optJSONObject("mp4")?.optString("max") ?: ""
                                TextButton(enabled = url.startsWith("https://"), onClick = {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }) { Icon(Icons.Outlined.PlayCircleOutline, null); Spacer(Modifier.width(6.dp)); Text(movie.optString("name").ifBlank { uiStr(R.string.steam_store_videos) }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showCloud && shortcut != null) {
        SteamNativeCloudPanel(id, shortcut.container, { showCloud = false })
    }
}
