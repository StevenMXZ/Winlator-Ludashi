package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

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
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 4)) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("Recent") }
    val apps = rememberSteamLibrary(context, state.library)
    val demos = rememberSteamLibrary(context, state.library, enabled = tab == 4, filterType = "demo")
    val installed = remember(apps) { apps.count { it.containerName == "Steam · Installed" } }
    val user = rememberSteamUserSnapshot(context)
    val profile = user.profile
    val owned = user.owned
    val achievementCache = user.achievements
    LaunchedEffect(state.signedIn, state.libraryLoading) {
        if (state.signedIn && !state.libraryLoading && !state.gameActive) SteamUserData.refreshProfile(context)
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .09f), MaterialTheme.colorScheme.background))).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
            Icon(painterResource(R.drawable.ic_steam), null, Modifier.size(38.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Steam", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(uiStr(R.string.steam_ui_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = !state.gameActive && !state.downloading, onClick = {
                if (state.signedIn) { SteamSession.retryLibrary(); SteamUserData.refreshProfile(context, true) } else SteamSession.reconnect()
            }) { Icon(Icons.Outlined.Refresh, uiStr(R.string.steam_ui_refresh)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, uiStr(R.string.steam_ui_options)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(uiStr(R.string.steam_friends)) }, onClick = { menu = false; context.startActivity(android.content.Intent(context, SteamFriendsActivity::class.java)) })
                    DropdownMenuItem(text = { Text(uiStr(R.string.steam_ui_sign_out)) }, onClick = { menu = false; signOut() }) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to (uiStr(R.string.steam_ui_games) to Icons.Outlined.SportsEsports), 4 to (uiStr(R.string.steam_ui_demos) to Icons.Outlined.SportsEsports), 1 to (uiStr(R.string.steam_ui_downloads) to Icons.Outlined.Download), 2 to (uiStr(R.string.steam_ui_installed) to Icons.Outlined.DownloadDone), 3 to (uiStr(R.string.steam_ui_achievements) to Icons.Outlined.EmojiEvents)).forEach { (index, entry) ->
                val (title, icon) = entry
                FilterChip(tab == index, onClick = { tab = index }, label = { Text(title) }, leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) })
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        if (state.restoring || (!state.signedIn && state.savedAccount)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(steamStatusText(state.status), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (state.restoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (!state.gameActive) TextButton(onClick = { SteamSession.reconnect() }) { Text(uiStr(R.string.steam_ui_retry)) }
            }
        }
        if (state.signedIn && state.libraryLoading) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(uiStr(R.string.steam_ui_loading_library, state.libraryProgress), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { state.libraryProgress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        } else if (state.libraryError.isNotEmpty()) Text(state.libraryError, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (tab == 1) {
            SteamDownloadsScreen(state, openGame, Modifier.weight(1f))
        } else if (tab == 3) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SteamAccountCard(profile, state.signedIn, apps.size, installed) }
                item { Text(uiStr(R.string.steam_ui_achievements), style = MaterialTheme.typography.titleLarge) }
                item { Text(uiStr(R.string.steam_ui_achievements_help), style = MaterialTheme.typography.bodySmall) }
                items(achievementCache.keys.toList()) { id ->
                    val list = achievementCache[id].orEmpty()
                    OutlinedCard(onClick = { if (File(context.filesDir, "steam-library/$id.json").isFile) openGame(id) else SteamSheets.game(context, id) }) {
                        ListItem(headlineContent = { Text(apps.find { SteamLibrary.appId(it.shortcutPath) == id }?.name ?: uiStr(R.string.steam_ui_game)) },
                            supportingContent = { Text(uiStr(R.string.steam_ui_ach_count, list.count { it.unlocked }, list.size)) }, leadingContent = { Icon(Icons.Outlined.EmojiEvents, null) })
                    }
                }
            }
        } else {
            val sourceApps = if (tab == 4) demos else apps
            val filtered = remember(sourceApps, query, tab) { sourceApps.filter { it.name.contains(query, true) && (tab != 2 || it.containerName == "Steam · Installed") } }
            val visible = remember(filtered, owned, sort) { sortSteamLibrary(filtered, owned, sort) }
            LazyVerticalGrid(if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1), Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "account", span = { GridItemSpan(maxLineSpan) }) { SteamAccountCard(profile, state.signedIn, apps.size, installed) }
                item(key = "controls", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (tab == 2) uiStr(R.string.steam_ui_installed) else if (tab == 4) uiStr(R.string.steam_ui_demos) else uiStr(R.string.steam_ui_library), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { search = !search }) { Icon(Icons.Outlined.Search, uiStr(R.string.steam_ui_search_games)) }
                            Box {
                                TextButton(onClick = { sorting = true }) { Icon(Icons.Outlined.SwapVert, null); Text(steamSortLabel(sort)); Icon(Icons.Outlined.ArrowDropDown, null) }
                                DropdownMenu(sorting, { sorting = false }) { listOf("Recent", "Name", "Playtime").forEach { title -> DropdownMenuItem(text = { Text(steamSortLabel(title)) }, onClick = { sort = title; sorting = false }) } }
                            }
                            IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, uiStr(R.string.steam_ui_layout)) }
                        }
                        if (search) OutlinedTextField(query, { query = it }, label = { Text(uiStr(R.string.steam_ui_search)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
                items(visible, key = { it.id }) { app ->
                    val id = SteamLibrary.appId(app.shortcutPath)!!
                    val isInstalled = app.containerName == "Steam · Installed"
                    OutlinedCard(onClick = { if (isInstalled) openGame(id) else SteamSheets.game(context, id) }, shape = RoundedCornerShape(16.dp)) {
                        SteamArt("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg", Modifier.fillMaxWidth().padding(6.dp).aspectRatio(460f / 215f).clip(RoundedCornerShape(12.dp)))
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                val minutes = owned[id]?.optLong("playtimeForever")
                                Text(if (minutes != null) "◷ ${"%.1f".format(minutes / 60.0)} h" else if (isInstalled) uiStr(R.string.steam_ui_installed_game) else uiStr(R.string.steam_ui_not_installed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { if (isInstalled) openGame(id) else SteamSheets.game(context, id) }) { Icon(if (isInstalled) Icons.Outlined.PlayArrow else Icons.Outlined.Download, if (isInstalled) uiStr(R.string.steam_ui_game_details) else uiStr(R.string.steam_ui_download_game), tint = if (isInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (visible.isEmpty() && !state.libraryLoading) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (tab == 2) uiStr(R.string.steam_ui_no_installed) else if (tab == 4) uiStr(R.string.steam_ui_no_demos) else uiStr(R.string.steam_ui_no_games), Modifier.padding(20.dp)) }
            }
        }
    }
}

@Composable internal fun SteamArt(url: String, modifier: Modifier) {
    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, update = { if (it.tag != url) { it.tag = url; SteamArtwork.request(it.context, url).into(it) } }, onRelease = { Glide.with(it).clear(it) }, modifier = modifier)
}

@Composable private fun SteamDownloadsScreen(state: SteamUiSnapshot, openGame: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var confirmRemove by remember(state.id) { mutableStateOf(false) }
    val installed = state.id > 0 && File(context.filesDir, "steam-library/${state.id}.json").isFile
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(uiStr(R.string.steam_ui_downloads), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (state.id > 0) item {
            OutlinedCard(shape = RoundedCornerShape(20.dp)) {
                SteamArt("https://shared.steamstatic.com/store_item_assets/steam/apps/${state.id}/header.jpg", Modifier.fillMaxWidth().aspectRatio(460f / 215f))
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.name, style = MaterialTheme.typography.titleLarge)
                    Text(if (state.downloading) uiStr(R.string.steam_ui_downloading) else if (installed) uiStr(R.string.steam_ui_installed_game) else uiStr(R.string.steam_ui_paused), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.downloading && state.total > 0)
                        LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    if (state.downloading) Text(downloadTimingText(state), style = MaterialTheme.typography.bodySmall)
                    Text("${state.done / 1048576} / ${state.total / 1048576} MB", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.downloading) {
                            OutlinedButton(onClick = { SteamSession.cancel() }) {
                                Icon(Icons.Outlined.Pause, null)
                                Text(uiStr(R.string.steam_ui_pause))
                            }
                        } else if (installed) {
                            Button(onClick = { openGame(state.id) }) { Text(uiStr(R.string.steam_ui_view_game)) }
                        } else {
                            Button(enabled = state.signedIn && !state.gameActive, onClick = { SteamSession.install(state.id) }) {
                                Icon(Icons.Outlined.Download, null)
                                Text(uiStr(R.string.steam_ui_resume))
                            }
                            OutlinedButton(enabled = !state.gameActive, onClick = { confirmRemove = true }) {
                                Icon(Icons.Outlined.DeleteOutline, null)
                                Text(uiStr(R.string.steam_download_cancel_delete))
                            }
                        }
                    }
                    if (state.status.startsWith("Download failed"))
                        Text(steamStatusText(state.status), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else item {
            OutlinedCard {
                ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_no_downloads)) },
                    supportingContent = { Text(uiStr(R.string.steam_ui_choose_download)) },
                    leadingContent = { Icon(Icons.Outlined.DownloadDone, null) })
            }
        }
    }
    if (confirmRemove && state.id > 0 && !installed && !state.downloading) {
        com.winlator.cmod.ui.theme.ThemedDialog(onDismissRequest = { confirmRemove = false }) {
            com.winlator.cmod.ui.theme.ThemedDialogTitle(uiStr(R.string.steam_download_cancel_delete))
            Text(uiStr(R.string.steam_download_cancel_confirm), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmRemove = false }) { Text(uiStr(R.string.steam_ui_cancel)) }
                TextButton(onClick = {
                    confirmRemove = false
                    SteamSession.discardPaused(state.id)
                }) { Text(uiStr(R.string.steam_download_cancel_delete)) }
            }
        }
    }
}

@Composable private fun SteamAccountCard(profile: JSONObject, connected: Boolean, games: Int, installed: Int) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val hash = profile.optString("avatarHash")
            if (hash.matches(Regex("[a-fA-F0-9]{40}")) && hash.any { it != '0' }) SteamArt("https://avatars.akamai.steamstatic.com/${hash}_medium.jpg", Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            else Icon(painterResource(R.drawable.ic_steam), null, Modifier.size(36.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.optString("playerName").ifBlank { uiStr(R.string.steam_ui_profile) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (connected) androidx.compose.ui.graphics.Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant, androidx.compose.foundation.shape.CircleShape))
                    Text(if (connected) uiStr(R.string.steam_ui_connected) else uiStr(R.string.steam_ui_offline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            listOf(games to uiStr(R.string.steam_ui_games), installed to uiStr(R.string.steam_ui_installed)).forEach { (count, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(count.toString(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
