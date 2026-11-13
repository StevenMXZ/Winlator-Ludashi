package com.winlator.cmod.gog

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.steam.ImmersiveSteamActivity
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

class GogActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!GogSettings.enabled(this)) {
            startActivity(Intent(this, GogSettingsActivity::class.java)); finish(); return
        }
        GogLibrary.open(applicationContext)
        setContent { WinZTheme { GogScreen(intent.getStringExtra("game")) } }
    }
    override fun onResume() {
        super.onResume()
        com.winlator.cmod.stores.StoreSessions.resumeForLibrary()
        if (!GogSettings.enabled(this)) finish() else GogLibrary.open(applicationContext)
    }
    @Composable private fun GogScreen(initialGame: String?) {
        val state by GogLibrary.state.collectAsStateWithLifecycle()
        var selected by rememberSaveable { mutableStateOf(initialGame) }
        var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra("gog_tab", 0).coerceIn(0, 2)) }
        var grid by rememberSaveable { mutableStateOf(true) }
        var query by rememberSaveable { mutableStateOf("") }
        var accountMenu by remember { mutableStateOf(false) }
        val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra("code")?.let { GogLibrary.signIn(applicationContext, it) }
        }
        val games = remember(state.games, state.installed) {
            (state.games + state.installed.values.mapNotNull { runCatching { GogGame.parse(it) }.getOrNull() }).distinctBy { it.id }
        }
        val visible = remember(games, query, tab, state.installed) {
            games.filter { it.title.contains(query, true) && (tab != 2 || it.id in state.installed) }.sortedWith(compareByDescending<GogGame> { it.id in state.installed }.thenBy { it.title.lowercase() })
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                Column(Modifier.weight(1f)) {
                    Text("GOG", style = MaterialTheme.typography.headlineLarge)
                    Text(uiStr(R.string.gog_library_subtitle), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(enabled = state.signedIn && !state.loading && !state.busy, onClick = { GogLibrary.refresh(applicationContext) }) { Icon(Icons.Outlined.Refresh, uiStr(R.string.steam_ui_refresh_info)) }
                Box {
                    IconButton(onClick = { accountMenu = true }) { Icon(Icons.Outlined.MoreVert, uiStr(R.string.ui_more_options)) }
                    DropdownMenu(accountMenu, { accountMenu = false }) {
                        DropdownMenuItem(text = { Text(uiStr(R.string.gog_store_settings)) }, onClick = {
                            accountMenu = false; startActivity(Intent(this@GogActivity, GogSettingsActivity::class.java))
                        })
                        DropdownMenuItem(text = { Text(uiStr(if (state.signedIn) R.string.gog_sign_out else R.string.gog_sign_in)) }, enabled = !state.busy && !state.loading,
                            onClick = { accountMenu = false; if (state.signedIn) GogLibrary.signOut(applicationContext) else login.launch(Intent(this@GogActivity, GogLoginActivity::class.java)) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.gog_games, R.string.steam_ui_downloads, R.string.gog_installed).forEachIndexed { index, text ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(uiStr(text)) })
                }
            }
            if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.stage, Modifier.padding(horizontal = 16.dp)) }
            if (state.error.isNotBlank()) Text(state.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (!state.signedIn && tab != 2) {
                FilledTonalButton(enabled = !state.loading, onClick = { login.launch(Intent(this@GogActivity, GogLoginActivity::class.java)) }, modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Outlined.Login, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.gog_sign_in))
                }
            }
            if (tab == 1) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val history = state.transfers.values.sortedByDescending { it.optLong("updated") }
                    items(history, key = { it.optString("id") }) { transfer -> GogDownloadPanel(state, transfer) }
                    if (history.isEmpty()) item { Text(uiStr(R.string.gog_no_downloads)) }
                }
            } else {
                OutlinedTextField(query, { query = it }, label = { Text(uiStr(R.string.gog_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(14.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(uiStr(if (tab == 2) R.string.gog_installed else R.string.steam_ui_library), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, uiStr(R.string.steam_ui_layout)) }
                }
                LazyVerticalGrid(if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1), Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = { it.id }) { game ->
                        LaunchedEffect(game.id) { GogLibrary.requestArt(applicationContext, "gog://${game.id}") }
                        val cachedArt = remember(game.id, state.revision) { File(cacheDir, "gog-art/${game.id}.jpg").takeIf { it.isFile } }
                        fun openGame() { if (game.id in state.installed) com.winlator.cmod.stores.StoreDetailActivity.open(this@GogActivity, "gog", game.id) else selected = game.id }
                        OutlinedCard(onClick = ::openGame, shape = RoundedCornerShape(16.dp)) {
                            AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                                update = { Glide.with(it).load(cachedArt ?: game.image).error(R.drawable.ic_gog).into(it) },
                                onRelease = { Glide.with(it).clear(it) }, modifier = Modifier.fillMaxWidth().padding(6.dp).aspectRatio(460f / 215f).clip(RoundedCornerShape(12.dp)))
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(uiStr(if (game.id in state.installed) R.string.gog_installed else R.string.gog_not_installed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = ::openGame) { Icon(if (game.id in state.installed) Icons.Outlined.PlayArrow else Icons.Outlined.Download, null) }
                            }
                        }
                    }
                    if (visible.isEmpty() && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Text(uiStr(R.string.gog_empty_library)) }
                }
            }
        }
        selected?.let { id -> games.find { it.id == id }?.let { GogGameDialog(it, state) { selected = null } } }
    }
    @Composable private fun GogGameDialog(game: GogGame, state: GogState, close: () -> Unit) {
        val scope = rememberCoroutineScope()
        var error by remember(game.id) { mutableStateOf("") }
        var loading by remember(game.id) { mutableStateOf(false) }
        var installers by remember(game.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
        var selectedInstaller by remember(game.id) { mutableIntStateOf(0) }
        var containers by remember { mutableStateOf<List<com.winlator.cmod.container.Container>>(emptyList()) }
        var container by remember { mutableIntStateOf(0) }
        var showInstallers by remember { mutableStateOf(false) }
        var showContainers by remember { mutableStateOf(false) }
        var showExecutables by remember { mutableStateOf(false) }
        var confirmRemove by remember { mutableStateOf(false) }
        val record = state.installed[game.id]
        LaunchedEffect(game.id, state.signedIn, record != null) {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching {
                val list = ContainerManager(this@GogActivity).containers
                val versions = if (record == null && state.signedIn) GogApi.installers(GogApi.get(applicationContext, "https://api.gog.com/products/${game.id}?expand=downloads")) else emptyList()
                list to versions
            } }
            result.onSuccess { (list, versions) ->
                containers = list; container = list.firstOrNull()?.id ?: 0; installers = versions
                selectedInstaller = versions.indexOfFirst { it.optString("language") in listOf("en", "english") }.coerceAtLeast(0)
            }.onFailure { error = it.message.orEmpty() }
            loading = false
        }
        fun operation(action: () -> Unit) { scope.launch { loading = true; error = ""; withContext(Dispatchers.IO) { runCatching(action) }.onFailure { error = it.message.orEmpty() }; loading = false } }
        AlertDialog(onDismissRequest = { if (!loading) close() }, title = { Text(game.title) }, text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (record == null) {
                    if (state.signedIn && !loading && installers.isEmpty()) Text(uiStr(R.string.gog_no_windows_installer))
                    Box {
                        OutlinedButton(enabled = !loading && installers.isNotEmpty() && !state.busy, onClick = { showInstallers = true }) {
                            Text(installers.getOrNull(selectedInstaller)?.let { "${it.optString("language")} · ${it.optString("version")}" } ?: uiStr(R.string.gog_installer))
                        }
                        DropdownMenu(showInstallers, { showInstallers = false }) {
                            installers.forEachIndexed { index, item -> DropdownMenuItem(text = { Text("${item.optString("language")} · ${item.optString("version")} · ${item.optLong("total_size") / 1048576} MiB") }, onClick = { selectedInstaller = index; showInstallers = false }) }
                        }
                    }
                    Box {
                        OutlinedButton(enabled = !loading && !state.busy, onClick = { showContainers = true }) { Text(containers.find { it.id == container }?.name ?: uiStr(R.string.gog_choose_container)) }
                        DropdownMenu(showContainers, { showContainers = false }) {
                            containers.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { container = item.id; showContainers = false }) }
                        }
                    }
                    Text(uiStr(R.string.gog_install_help), style = MaterialTheme.typography.bodySmall)
                    com.winlator.cmod.stores.StoreInformation("gog", game.id, org.json.JSONObject())
                } else {
                    Text(uiStr(R.string.gog_installed))
                    Box {
                        TextButton(enabled = !loading && !state.busy, onClick = { showExecutables = true }) { Text(record.optString("executable").ifBlank { uiStr(R.string.gog_choose_executable) }) }
                        DropdownMenu(showExecutables, { showExecutables = false }) {
                            val choices = record.getJSONArray("executables")
                            for (i in 0 until choices.length()) {
                                val path = choices.getString(i)
                                DropdownMenuItem(text = { Text(path) }, onClick = {
                                    showExecutables = false
                                    operation {
                                        val updated = JSONObject(record.toString()).put("executable", path).put("arguments", "")
                                        check(com.winlator.cmod.core.ContainerSessionGate.edit(updated.getInt("container"))) { "Exit the container before changing the executable" }
                                        try {
                                            GogDownloadService.writeShortcut(applicationContext, game.id, updated)
                                            GogLibrary.atomic(File(GogLibrary.records(applicationContext), "${game.id}.json"), updated.toString())
                                            GogLibrary.updateInstalled(applicationContext)
                                        } finally { com.winlator.cmod.core.ContainerSessionGate.edited(updated.getInt("container")) }
                                    }
                                })
                            }
                        }
                    }
                    TextButton(enabled = !loading && record.optString("executable").isNotBlank(), onClick = {
                        runCatching {
                            val shortcut = GogLibrary.shortcut(this@GogActivity, game.id)
                            val host = supportFragmentManager.findFragmentByTag("gog-shortcut-settings") ?: androidx.fragment.app.Fragment().also {
                                supportFragmentManager.beginTransaction().add(it, "gog-shortcut-settings").commitNow()
                            }
                            com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog.show(host, shortcut)
                        }.onFailure { error = it.message.orEmpty() }
                    }) { Text(uiStr(R.string.gog_shortcut_settings)) }
                    TextButton(enabled = !loading && !state.busy, onClick = { confirmRemove = true }) { Text(uiStr(R.string.steam_ui_uninstall)) }
                }
                TextButton(onClick = {
                    val prefs = getSharedPreferences("gog-settings", 0)
                    prefs.edit().putBoolean("favorite-${game.id}", !prefs.getBoolean("favorite-${game.id}", false)).apply()
                    GogLibrary.mutable.update { it.copy(revision = it.revision + 1) }
                }) { Text(uiStr(R.string.gog_toggle_favorite)) }
            }
        }, confirmButton = {
            Button(enabled = !loading && !state.busy && (if (record == null) state.signedIn && installers.isNotEmpty() && container > 0 else record.optString("executable").isNotBlank()), onClick = {
                if (record != null) runCatching { GogLibrary.play(this@GogActivity, game.id); close() }.onFailure { error = it.message.orEmpty() }
                else {
                    startForegroundService(Intent(this@GogActivity, GogDownloadService::class.java).putExtra("game", game.id)
                        .putExtra("container", container).putExtra("installer", installers[selectedInstaller].toString()))
                    close()
                }
            }) { Text(uiStr(if (record == null) R.string.gog_download_install else R.string.gog_play)) }
        }, dismissButton = { TextButton(enabled = !loading, onClick = close) { Text(uiStr(R.string.ui_close)) } })
        if (confirmRemove && record != null) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text(uiStr(R.string.steam_ui_uninstall)) },
            text = { Text(uiStr(R.string.gog_uninstall_help)) }, confirmButton = { TextButton(onClick = {
                confirmRemove = false
                operation {
                    val cid = record.getInt("container")
                    check(com.winlator.cmod.core.ContainerSessionGate.edit(cid)) { "Exit the container before uninstalling" }
                    try {
                        val target = File(record.getString("path")).canonicalFile
                        val containerRoot = ContainerManager(applicationContext).getContainerById(cid)?.rootDir ?: error("Container no longer exists")
                        require(target.parentFile == File(containerRoot, ".wine/drive_c/GOG Games").canonicalFile && target.name == "Ludashi-${game.id}")
                        check(target.deleteRecursively()) { "Cannot remove game files" }
                        File(record.getString("shortcut")).delete()
                        check(File(GogLibrary.records(applicationContext), "${game.id}.json").delete())
                        GogLibrary.updateInstalled(applicationContext)
                    } finally { com.winlator.cmod.core.ContainerSessionGate.edited(cid) }
                }
            }) { Text(uiStr(R.string.steam_ui_uninstall)) } }, dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(uiStr(R.string.cancel)) } })
    }
}
