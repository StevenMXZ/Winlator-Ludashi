package com.winlator.cmod.steam

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object SteamStoreMetadata {
    private val cache = android.util.LruCache<Int, String>(32)
    fun fetch(id: Int): JSONObject {
        cache.get(id)?.let { return JSONObject(it) }
        val connection = URL("https://store.steampowered.com/api/appdetails?appids=$id&cc=us&l=english").openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode != 200) return JSONObject()
            val text = connection.inputStream.bufferedReader().use { it.readText().take(1500000) }
            val data = JSONObject(text).optJSONObject(id.toString())?.optJSONObject("data") ?: return JSONObject()
            cache.put(id, data.toString())
            data
        } finally { connection.disconnect() }
    }
    fun cloudListing(id: Int): JSONObject {
        val future = CompletableFuture<JSONObject>()
        SteamSession.work {
            if (future.isCancelled) return@work
            try {
                check(SteamSession.signedIn && !SteamSession.gameActive)
                future.complete(JSONObject(SteamSession.session?.getCloudFileList(id) ?: error("Steam Cloud unavailable")))
            } catch (error: Throwable) { future.completeExceptionally(error) }
        }
        return try { future.get(35, TimeUnit.SECONDS) } finally { future.cancel(false) }
    }
}

@Composable
internal fun SteamStoreDetails(id: Int, installed: Boolean, state: SteamUiSnapshot) {
    val context = LocalContext.current
    var tab by remember(id) { mutableIntStateOf(0) }
    var branch by remember(id) { mutableStateOf(SteamInstallOptions.branch(context, id)) }
    var language by remember(id) { mutableStateOf(SteamInstallOptions.language(context, id)) }
    var branchMenu by remember { mutableStateOf(false) }
    var languageMenu by remember { mutableStateOf(false) }
    val gameRevision = SteamGames.version
    val installedInfo by produceState(JSONObject(), id, installed, gameRevision) {
        value = withContext(Dispatchers.IO) {
            if (installed) runCatching { SteamGames.info(context, id) }.getOrDefault(JSONObject()) else JSONObject()
        }
    }
    val livePics by produceState(JSONObject(), id, state.signedIn, state.gameActive, gameRevision) {
        value = withContext(Dispatchers.IO) {
            if (state.signedIn && !state.gameActive) runCatching { SteamSession.info(id) }.getOrDefault(JSONObject()) else JSONObject()
        }
    }
    val pics = livePics.takeIf { it.length() > 0 } ?: installedInfo
    val store by produceState(JSONObject(), id) {
        value = withContext(Dispatchers.IO) {
            runCatching { SteamStoreMetadata.fetch(id) }.getOrDefault(JSONObject())
        }
    }
    val branches = remember(pics) { SteamCatalog.branches(pics) }
    val languages = remember(pics) { SteamCatalog.languages(pics) }
    var excludedDlc by remember(id) { mutableStateOf(SteamInstallOptions.excludedDlc(context, id)) }
    val library = remember(state.library) { runCatching { JSONObject(state.library) }.getOrDefault(JSONObject()) }
    val ownedDlc = remember(library) { SteamCatalog.ownedAppIds(library) }
    val dlcDepotApps = remember(pics) { SteamCatalog.dlcDepotApps(pics).values.toSet() }
    val selectedDlc = remember(pics, library, excludedDlc) { SteamCatalog.selectedDlc(pics, library, excludedDlc) }
    val installedDlc = remember(installedInfo) { SteamCatalog.installedDlcIds(installedInfo) }
    val licensesReady = state.signedIn && SteamCatalog.packageMetadataReady(library)
    val canEditDlc = licensesReady && !state.downloading && !state.gameActive && SteamSession.installRequestId == 0
    val downloadSize by produceState(0L, pics, branch, language, state.library, state.signedIn, installed, selectedDlc) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val lookup: ((Int) -> JSONObject)? = if (state.signedIn && !installed) SteamSession::info else null
                val depots = SteamCatalog.depots(pics, branch, language, selectedDlc, lookup)
                SteamCatalog.estimatedSize(depots, library) ?: 0L
            }.getOrDefault(0L)
        }
    }
    val installedSize by produceState<Long?>(null, id, installed, gameRevision) {
        value = if (!installed) null else withContext(Dispatchers.IO) {
            runCatching {
                val coroutine = currentCoroutineContext()
                val dir = SteamGames.directory(context, id)
                dir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                    .onEach { coroutine.ensureActive() }.filter { it.isFile }.sumOf { it.length() }
            }.getOrNull()
        }
    }
    val recordedBranch = installedInfo.optString("ludashiInstallBranch", "public")
    val recordedLanguage = installedInfo.optString("ludashiInstallLanguage", "english")
    fun readableSize(bytes: Long): String = if (bytes >= 1073741824L) "%.1f GiB".format(bytes / 1073741824.0)
        else "%.1f MiB".format(bytes / 1048576.0)
    val cached = rememberSteamGameSnapshot(context, id)
    val ach = cached.achievements
    var showCloud by remember(id) { mutableStateOf(false) }
    val shortcut by produceState<com.winlator.cmod.container.Shortcut?>(null, installed, id, SteamGames.version) {
        value = if (installed) withContext(Dispatchers.IO) { SteamGames.shortcut(context, id) } else null
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
                    val bytes = installedSize?.takeIf { installed } ?: downloadSize
                    Text(uiStr(R.string.steam_store_game_size,
                        if (bytes > 0) readableSize(bytes) else uiStr(R.string.steam_store_size_unknown)),
                        style = MaterialTheme.typography.bodySmall)
                    if (pics.optJSONObject("depots") != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { branchMenu = true }, enabled = !state.downloading && !state.gameActive,
                                    modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Text(uiStr(R.string.steam_store_version) + ": " + branch,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(branchMenu, { branchMenu = false }) {
                                    branches.forEach { option ->
                                        DropdownMenuItem(text = { Text(option) }, onClick = {
                                            SteamInstallOptions.setBranch(context, id, option)
                                            branch = option
                                            branchMenu = false
                                        })
                                    }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { languageMenu = true }, enabled = !state.downloading && !state.gameActive,
                                    modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Text(uiStr(R.string.steam_store_language) + ": " + language,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(languageMenu, { languageMenu = false }) {
                                    languages.forEach { option ->
                                        DropdownMenuItem(text = { Text(option) }, onClick = {
                                            SteamInstallOptions.setLanguage(context, id, option)
                                            language = option
                                            languageMenu = false
                                        })
                                    }
                                }
                            }
                        }
                        if (installed && (recordedBranch != branch || recordedLanguage != language)) {
                            TextButton(enabled = state.signedIn && !state.downloading && !state.gameActive,
                                onClick = { SteamSession.install(id) }) {
                                Text(uiStr(R.string.steam_store_apply_options))
                            }
                        }
                    }
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
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(ach) { achievement ->
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
                }
                2 -> {
                    val dlcJson = store.optJSONArray("dlc") ?: JSONArray()
                    val ids = ((0 until dlcJson.length()).mapNotNull { dlcJson.optInt(it).takeIf { item -> item > 0 } } +
                        SteamCatalog.dlcIds(pics) + installedDlc).distinct()
                    val dlcNames by produceState<Map<Int, String>>(emptyMap(), id, ids.joinToString(","), state.signedIn) {
                        value = if (!state.signedIn || ids.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
                            ids.take(60).mapNotNull { dlcId ->
                                currentCoroutineContext().ensureActive()
                                val name = runCatching { SteamSession.info(dlcId).optJSONObject("common")?.optString("name") }
                                    .getOrNull().orEmpty()
                                if (name.isNotBlank()) dlcId to name else null
                            }.toMap()
                        }
                    }
                    if (ids.isEmpty()) Text(uiStr(R.string.steam_store_no_dlc))
                    else {
                        Text(uiStr(R.string.steam_dlc_selected_count, selectedDlc.size, dlcDepotApps.intersect(ownedDlc).size),
                            style = MaterialTheme.typography.bodySmall)
                        if (!licensesReady) Text(uiStr(R.string.steam_dlc_wait_licenses), style = MaterialTheme.typography.bodySmall)
                        else if (installed) Text(uiStr(R.string.steam_dlc_keep_installed), style = MaterialTheme.typography.bodySmall)
                        val selectable = dlcDepotApps.intersect(ownedDlc)
                        if (selectable.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = canEditDlc && !selectedDlc.containsAll(selectable), onClick = {
                                    val next = excludedDlc - selectable
                                    SteamInstallOptions.setExcludedDlc(context, id, next)
                                    excludedDlc = next
                                }) { Text(uiStr(R.string.steam_dlc_select_all)) }
                                TextButton(enabled = canEditDlc && selectedDlc.isNotEmpty(), onClick = {
                                    val next = excludedDlc + selectable
                                    SteamInstallOptions.setExcludedDlc(context, id, next)
                                    excludedDlc = next
                                }) { Text(uiStr(R.string.steam_dlc_select_none)) }
                            }
                        }
                        ids.forEach { dlcId ->
                            val owned = dlcId in ownedDlc
                            val hasContent = dlcId in dlcDepotApps
                            val isInstalled = dlcId in installedDlc
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(checked = dlcId in selectedDlc, enabled = canEditDlc && owned && hasContent,
                                    onCheckedChange = { checked ->
                                        val next = if (checked) excludedDlc - dlcId else excludedDlc + dlcId
                                        SteamInstallOptions.setExcludedDlc(context, id, next)
                                        excludedDlc = next
                                    })
                                Column(Modifier.weight(1f)) {
                                    Text(dlcNames[dlcId].orEmpty().ifBlank { "DLC $dlcId" }, style = MaterialTheme.typography.bodyMedium)
                                    Text(uiStr(when {
                                        isInstalled -> R.string.steam_dlc_installed_content
                                        !licensesReady -> R.string.steam_dlc_license_unknown
                                        !owned -> R.string.steam_dlc_not_owned
                                        !hasContent -> R.string.steam_dlc_no_separate_content
                                        else -> R.string.steam_dlc_available
                                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (installed && selectable.isNotEmpty()) {
                            Button(enabled = canEditDlc && selectedDlc.isNotEmpty(),
                                onClick = { SteamSession.install(id) }) { Text(uiStr(R.string.steam_dlc_install_selected)) }
                        }
                    }
                }
                3 -> {
                    val cloud by produceState<Pair<JSONObject?, String>>(null to "", id, state.signedIn, state.gameActive, tab) {
                        value = if (state.signedIn && !state.gameActive) runInterruptible(Dispatchers.IO) {
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
    shortcut?.takeIf { showCloud }?.let {
        SteamNativeCloudPanel(id, it.container, { showCloud = false })
    }
}
