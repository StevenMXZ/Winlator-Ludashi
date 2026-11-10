package com.winlator.cmod.steam

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.winlator.cmod.R
import org.json.JSONObject
import java.io.File

@Composable fun SteamManagerScreen(state: SteamUiSnapshot, initialTab: Int, onBack: () -> Unit, signOut: () -> Unit, openGame: (Int) -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Recent") }
    val apps = rememberSteamLibrary(context, state.library)
    val installed = remember(apps) { apps.count { it.containerName == "Steam · Installed" } }
    val user = rememberSteamUserSnapshot(context)
    val profile = user.profile
    val owned = user.owned
    val achievementCache = user.achievements
    LaunchedEffect(state.signedIn, state.libraryLoading) {
        if (state.signedIn && !state.libraryLoading && !state.gameActive) SteamUserData.refreshProfile(context)
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .09f), MaterialTheme.colorScheme.background))).displayCutoutPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Icon(painterResource(R.drawable.ic_steam), null, Modifier.size(38.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Steam", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Your Steam library on Ludashi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = !state.gameActive && !state.downloading, onClick = {
                if (state.signedIn) { SteamSession.retryLibrary(); SteamUserData.refreshProfile(context, true) } else SteamSession.reconnect()
            }) { Icon(Icons.Outlined.Refresh, "Refresh Steam") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Steam options") }
                DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; signOut() }) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Games" to Icons.Outlined.SportsEsports, "Downloads" to Icons.Outlined.Download, "Installed" to Icons.Outlined.DownloadDone, "Achievements" to Icons.Outlined.EmojiEvents).forEachIndexed { index, (title, icon) ->
                FilterChip(tab == index, onClick = { tab = index }, label = { Text(title) }, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) })
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        if (state.restoring || (!state.signedIn && state.savedAccount)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (state.restoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (!state.gameActive) TextButton(onClick = { SteamSession.reconnect() }) { Text("Retry") }
            }
        }
        if (state.signedIn && state.libraryLoading) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Loading Steam library… ${state.libraryProgress}%", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { state.libraryProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        } else if (state.libraryError.isNotEmpty()) Text(state.libraryError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (tab != 1) {
            SteamAccountCard(profile, state.signedIn, apps.size, installed,
                if (achievementCache.isEmpty()) null else achievementCache.values.sumOf { list -> list.count { it.unlocked } })
        }
        if (tab == 1) {
            SteamDownloadsScreen(state, openGame, Modifier.weight(1f))
        } else if (tab == 3) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Achievements", style = MaterialTheme.typography.titleLarge) }
                item { Text("Open a game to load its Steam achievements. Saved results remain available offline.", style = MaterialTheme.typography.bodySmall) }
                items(achievementCache.keys.toList()) { id ->
                    val list = achievementCache[id].orEmpty()
                    OutlinedCard(onClick = { if (File(context.filesDir, "steam-library/$id.json").isFile) openGame(id) else SteamSheets.game(context, id) }) {
                        ListItem(headlineContent = { Text(apps.find { SteamLibrary.appId(it.shortcutPath) == id }?.name ?: "Steam game") },
                            supportingContent = { Text("${list.count { it.unlocked }} / ${list.size} unlocked") }, leadingContent = { Icon(Icons.Outlined.EmojiEvents, null) })
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tab == 2) "Installed" else "Library", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { search = !search }) { Icon(Icons.Outlined.Search, "Search games") }
                Box {
                    TextButton(onClick = { sorting = true }) { Icon(Icons.Outlined.SwapVert, null); Text(sort); Icon(Icons.Outlined.ArrowDropDown, null) }
                    DropdownMenu(sorting, { sorting = false }) { listOf("Recent", "Name", "Playtime").forEach { title -> DropdownMenuItem(text = { Text(title) }, onClick = { sort = title; sorting = false }) } }
                }
                IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, "Change layout") }
            }
            if (search) OutlinedTextField(query, { query = it }, label = { Text("Search Steam games") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            val filtered = remember(apps, query, tab) { apps.filter { it.name.contains(query, true) && (tab != 2 || it.containerName == "Steam · Installed") } }
            val visible = remember(filtered, owned, sort) { sortSteamLibrary(filtered, owned, sort) }
            LazyVerticalGrid(if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1), Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { app ->
                    val id = SteamLibrary.appId(app.shortcutPath)!!
                    val isInstalled = app.containerName == "Steam · Installed"
                    OutlinedCard(onClick = { if (isInstalled) openGame(id) else SteamSheets.game(context, id) }, shape = RoundedCornerShape(16.dp)) {
                        SteamArt("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg", Modifier.fillMaxWidth().padding(6.dp).aspectRatio(460f / 215f).clip(RoundedCornerShape(12.dp)))
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                val minutes = owned[id]?.optLong("playtimeForever")
                                Text(if (minutes != null) "◷ ${"%.1f".format(minutes / 60.0)} h" else if (isInstalled) "Installed" else "Not installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { if (isInstalled) openGame(id) else SteamSheets.game(context, id) }) { Icon(if (isInstalled) Icons.Outlined.PlayArrow else Icons.Outlined.Download, if (isInstalled) "Game details" else "Download Steam game", tint = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (visible.isEmpty() && !state.libraryLoading) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (tab == 2) "No installed Steam games" else "No games found", Modifier.padding(20.dp)) }
            }
        }
    }
}

@Composable internal fun SteamArt(url: String, modifier: Modifier) {
    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, update = { if (it.tag != url) { it.tag = url; Glide.with(it).load(url).error(R.drawable.icon_wine).into(it) } }, onRelease = { Glide.with(it).clear(it) }, modifier = modifier)
}

@Composable private fun SteamDownloadsScreen(state: SteamUiSnapshot, openGame: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Downloads", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (state.id > 0) item {
            OutlinedCard(shape = RoundedCornerShape(20.dp)) {
                SteamArt("https://shared.steamstatic.com/store_item_assets/steam/apps/${state.id}/header.jpg", Modifier.fillMaxWidth().aspectRatio(460f / 215f))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (state.downloading) "Downloading" else if (File(context.filesDir, "steam-library/${state.id}.json").isFile) "Installed" else "Paused", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.downloading && state.total > 0) LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${state.done / 1048576} / ${state.total / 1048576} MB", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.downloading) OutlinedButton(onClick = { SteamSession.cancel() }) { Icon(Icons.Outlined.Pause, null); Text("Pause") }
                        else if (File(context.filesDir, "steam-library/${state.id}.json").isFile) Button(onClick = { openGame(state.id) }) { Text("View game") }
                        else Button(enabled = state.signedIn && !state.gameActive, onClick = { SteamSession.install(state.id) }) { Icon(Icons.Outlined.Download, null); Text("Resume") }
                    }
                    if (state.status.startsWith("Download failed")) Text(state.status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else item {
            OutlinedCard { ListItem(headlineContent = { Text("No active downloads") }, supportingContent = { Text("Choose a game from your library to download") }, leadingContent = { Icon(Icons.Outlined.DownloadDone, null) }) }
        }
    }
}

@Composable private fun SteamAccountCard(profile: JSONObject, connected: Boolean, games: Int, installed: Int, achievements: Int?) {
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val hash = profile.optString("avatarHash")
                if (hash.matches(Regex("[a-fA-F0-9]{40}")) && hash.any { it != '0' }) SteamArt("https://avatars.akamai.steamstatic.com/${hash}_medium.jpg", Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                else Icon(painterResource(R.drawable.ic_steam), null, Modifier.size(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.optString("playerName").ifBlank { "Steam profile" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (connected) "● Connected" else "● Offline", style = MaterialTheme.typography.labelMedium, color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Triple(Icons.Outlined.SportsEsports, games.toString(), "Games"), Triple(Icons.Outlined.DownloadDone, installed.toString(), "Installed"), Triple(Icons.Outlined.EmojiEvents, achievements?.toString() ?: "—", "Cached unlocks")).forEach { (icon, count, title) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(icon, null, Modifier.size(19.dp)); Text(count, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(title, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
