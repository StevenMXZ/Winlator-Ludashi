package com.winlator.cmod.stores

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.winlator.cmod.R
import com.winlator.cmod.steam.ImmersiveSteamActivity
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class StoreActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LudashiStore.find(intent.getStringExtra("store")) ?: run { finish(); return }
        if (!store.enabled(this)) { startActivity(Intent(this, StoreSettingsScreen::class.java).putExtra("store", store.id)); finish(); return }
        StoreSessions.resumeForLibrary(); StoreSessions.open(applicationContext, store)
        setContent { WinZTheme { Screen(store) } }
    }
    override fun onResume() {
        super.onResume()
        StoreSessions.resumeForLibrary()
        LudashiStore.find(intent.getStringExtra("store"))?.let {
            if (!it.enabled(this)) finish() else StoreSessions.open(applicationContext, it)
        }
    }
    @Composable private fun Screen(store: LudashiStore) {
        val state by StoreSessions.state(store).collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(intent.getIntExtra("tab", 0).coerceIn(0, 2)) }
        var query by rememberSaveable { mutableStateOf("") }
        var grid by rememberSaveable { mutableStateOf(true) }
        var menu by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(StoreSessions.allGames(state).find { it.id == intent.getStringExtra("game") }) }
        var add by remember { mutableStateOf(false) }
        var address by rememberSaveable { mutableStateOf("") }
        val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) StoreSessions.run(applicationContext, store) { StoreSessions.loginFinished(applicationContext, store) }
        }
        val games = remember(state.games, state.installed) { StoreSessions.allGames(state) }
        val visible = remember(games, state.installed, tab, query) {
            games.filter { it.title.contains(query, true) && (tab != 2 || it.key in state.installed) }
                .sortedWith(compareByDescending<StoreGame> { it.key in state.installed }.thenBy { it.title.lowercase() })
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                Column(Modifier.weight(1f)) { Text(store.title, style = MaterialTheme.typography.headlineLarge); Text(uiStr(R.string.store_library_subtitle, store.title), style = MaterialTheme.typography.bodySmall) }
                IconButton(enabled = state.signedIn && !state.busy && !state.loading && !StoreSessions.paused, onClick = { StoreSessions.refresh(applicationContext, store) }) { Icon(Icons.Outlined.Refresh, uiStr(R.string.steam_ui_refresh_info)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, uiStr(R.string.ui_more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(uiStr(R.string.gog_store_settings)) }, onClick = { menu = false; startActivity(Intent(this@StoreActivity, StoreSettingsScreen::class.java).putExtra("store", store.id)) })
                        DropdownMenuItem(text = { Text(uiStr(R.string.store_sign_in, store.title)) }, enabled = !state.busy && !state.loading,
                            onClick = { menu = false; login.launch(Intent(this@StoreActivity, StoreLoginActivity::class.java).putExtra("store", store.id)) })
                        if (state.signedIn) DropdownMenuItem(text = { Text(uiStr(R.string.store_sign_out, store.title)) }, enabled = !state.busy && !state.loading,
                            onClick = { menu = false; StoreSessions.signOut(applicationContext, store) })
                    }
                }
            }
            Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.gog_games, R.string.steam_ui_downloads, R.string.gog_installed).forEachIndexed { i, text -> FilterChip(tab == i, { tab = i }, label = { Text(uiStr(text)) }) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error.isNotBlank()) Text(state.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            if (!state.signedIn) FilledTonalButton(enabled = !state.loading, onClick = { login.launch(Intent(this@StoreActivity, StoreLoginActivity::class.java).putExtra("store", store.id)) }, modifier = Modifier.padding(14.dp)) { Text(uiStr(R.string.store_sign_in, store.title)) }
            if (tab == 1) androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val history = state.transfers.values.sortedByDescending { it.optLong("updated") }
                items(history.size) { index -> StoreTransferPanel(store, state, transfer = history[index]) }
                if (history.isEmpty()) item { Text(uiStr(R.string.store_no_downloads)) }
            }
            else {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text(uiStr(R.string.gog_search)) }, singleLine = true, modifier = Modifier.weight(1f))
                    if (store == LudashiStore.ITCH) IconButton(enabled = state.signedIn && !state.busy && !state.loading, onClick = { add = true }) { Icon(Icons.Outlined.Add, uiStr(R.string.store_add_itch)) }
                    IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, uiStr(R.string.steam_ui_layout)) }
                }
                LazyVerticalGrid(if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1), Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visible, key = { it.id }) { game ->
                        LaunchedEffect(game.id) { StoreSessions.requestArt(applicationContext, game.uri) }
                        val art = remember(game.key, state.revision) { File(cacheDir, "${store.id}-art/${game.key}.jpg").takeIf { it.isFile } }
                        fun open() { if (game.key in state.installed) StoreDetailActivity.open(this@StoreActivity, store.id, game.id) else selected = game }
                        OutlinedCard(onClick = ::open, shape = RoundedCornerShape(16.dp)) {
                            AndroidView(factory = { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
                                update = { Glide.with(it).load(art ?: game.image).error(store.icon).into(it) }, onRelease = { Glide.with(it).clear(it) },
                                modifier = Modifier.fillMaxWidth().padding(6.dp).aspectRatio(460f / 215f).clip(RoundedCornerShape(12.dp)))
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(game.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    Text(uiStr(if (game.key in state.installed) R.string.gog_installed else R.string.gog_not_installed), style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = ::open) { Icon(if (game.key in state.installed) Icons.Outlined.PlayArrow else Icons.Outlined.Download, null) }
                            }
                        }
                    }
                    if (visible.isEmpty() && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Text(uiStr(R.string.store_empty)) }
                }
            }
        }
        selected?.let { game -> InstallDialog(store, state, game) { selected = null } }
        if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text(uiStr(R.string.store_add_itch)) }, text = { OutlinedTextField(address, { address = it }, label = { Text("https://creator.itch.io/game") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { StoreSessions.addItch(applicationContext, address); add = false }) { Text(uiStr(R.string.store_add)) } }, dismissButton = { TextButton(onClick = { add = false }) { Text(uiStr(R.string.cancel)) } })
    }
    @Composable private fun InstallDialog(store: LudashiStore, state: StoreSnapshot, game: StoreGame, close: () -> Unit) {
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf("") }
        var versions by remember { mutableStateOf<List<StoreUpload>>(emptyList()) }
        var version by remember { mutableIntStateOf(0) }
        var containers by remember { mutableStateOf<List<com.winlator.cmod.container.Container>>(emptyList()) }
        var container by remember { mutableIntStateOf(0) }
        val preview = remember(game.id) { JSONObject().put("game", game.json()) }
        var showVersions by remember { mutableStateOf(false) }
        var showContainers by remember { mutableStateOf(false) }
        LaunchedEffect(game.id) {
            val result = withContext(Dispatchers.IO) { runCatching {
                val containers = com.winlator.cmod.container.ContainerManager(this@StoreActivity).containers
                val files = if (store == LudashiStore.ITCH) ItchConnector.uploads(applicationContext, game) else listOf(StoreUpload("current", uiStr(R.string.store_current_version), 0))
                containers to files
            } }
            result.onSuccess { (list, files) -> containers = list; container = list.firstOrNull()?.id ?: 0; versions = files }
                .onFailure { error = it.message.orEmpty() }
            loading = false
        }
        AlertDialog(onDismissRequest = close, title = { Text(game.title) }, text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (!loading && versions.isEmpty()) Text(uiStr(R.string.store_no_windows))
                Box {
                    OutlinedButton(enabled = !loading && versions.isNotEmpty(), onClick = { showVersions = true }) { Text(versions.getOrNull(version)?.name ?: uiStr(R.string.store_choose_download)) }
                    DropdownMenu(showVersions, { showVersions = false }) { versions.forEachIndexed { index, item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { version = index; showVersions = false }) } }
                }
                Box {
                    OutlinedButton(enabled = !loading, onClick = { showContainers = true }) { Text(containers.find { it.id == container }?.name ?: uiStr(R.string.gog_choose_container)) }
                    DropdownMenu(showContainers, { showContainers = false }) { containers.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { container = item.id; showContainers = false }) } }
                }
                StoreInformation(store.id, game.id, preview)
            }
        }, confirmButton = {
            Button(enabled = !loading && state.signedIn && !state.busy && !StoreSessions.paused && container > 0 && versions.isNotEmpty(), onClick = {
                val upload = versions[version]
                startForegroundService(Intent(this@StoreActivity, StoreDownloadService::class.java).putExtra("store", store.id).putExtra("game", game.json().toString())
                    .putExtra("container", container).putExtra("upload", JSONObject().put("id", upload.id).put("name", upload.name).put("size", upload.size).toString()))
                close()
            }) { Text(uiStr(R.string.gog_download_install)) }
        }, dismissButton = { TextButton(onClick = close) { Text(uiStr(R.string.ui_close)) } })
    }
}

@Composable internal fun StoreTransferPanel(store: LudashiStore, state: StoreSnapshot, modifier: Modifier = Modifier, transfer: JSONObject? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (!state.busy && transfer == null) { Text(uiStr(R.string.store_no_downloads), modifier); return }
    val game = remember(state.games, state.active, transfer) {
        transfer?.optJSONObject("game")?.let { StoreGame.parse(it) } ?: state.games.find { it.id == state.active }
    }
    val active = state.busy && state.active == game?.id
    val label = if (active) state.stage else uiStr(when (transfer?.optString("status")) { "completed" -> R.string.store_download_complete; "failed" -> R.string.store_download_failed; else -> R.string.store_download_paused })
    val done = if (active) state.done else transfer?.optLong("done") ?: 0L
    val total = if (active) state.total else transfer?.optLong("total") ?: 0L
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AndroidView(factory = { android.widget.ImageView(it) }, update = { Glide.with(it).load(game?.image).error(store.icon).into(it) }, onRelease = { Glide.with(it).clear(it) }, modifier = Modifier.size(48.dp))
                Column(Modifier.weight(1f)) { Text(game?.title ?: store.title, style = MaterialTheme.typography.titleSmall); Text(label, style = MaterialTheme.typography.bodySmall) }
                if (active) TextButton(onClick = { StoreSessions.cancel(store) }) { Text(uiStr(R.string.cancel)) }
                else if (transfer?.optString("status") != "completed" && !state.busy && state.signedIn && game != null) TextButton(onClick = {
                    context.startForegroundService(Intent(context, StoreDownloadService::class.java).putExtra("store", store.id).putExtra("game", game.json().toString()).putExtra("container", transfer?.optInt("container") ?: 0).putExtra("upload", transfer?.optString("upload")))
                }) { Text(uiStr(R.string.store_retry_download)) }
            }
            if (active) { if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (total > 0) Text("${done / 1048576} / ${total / 1048576} MiB")
            if (!active && transfer?.optString("error").orEmpty().isNotBlank()) Text(transfer!!.optString("error"), color = MaterialTheme.colorScheme.error)
        }
    }
}
