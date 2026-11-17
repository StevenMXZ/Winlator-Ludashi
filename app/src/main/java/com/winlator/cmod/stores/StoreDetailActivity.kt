package com.winlator.cmod.stores

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.winlator.cmod.R
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.gog.*
import com.winlator.cmod.steam.ImmersiveSteamActivity
import com.winlator.cmod.ui.library.*
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class StoreDetailActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.FrameLayout(this).apply { id = android.R.id.custom })
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(android.R.id.custom,
            StoreDetailFragment().apply { arguments = Bundle().apply { putString("store", intent.getStringExtra("store")); putString("game", intent.getStringExtra("game")) } }).commit()
    }
    override fun onResume() { super.onResume(); StoreSessions.resumeForLibrary() }
    companion object {
        fun open(context: Context, store: String, id: String) { context.startActivity(Intent(context, StoreDetailActivity::class.java).putExtra("store", store).putExtra("game", id)) }
    }
}

class StoreDetailFragment : Fragment() {
    private data class Detail(val title: String, val brand: String, val artwork: File?, val record: JSONObject, val shortcut: Shortcut?, val installed: Boolean = true)
    private val storeId get() = requireArguments().getString("store")!!
    private val gameId get() = requireArguments().getString("game")!!
    private fun notice(text: String) { if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show() }
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { WinZTheme {
            var revision by remember { mutableIntStateOf(0) }
            var error by remember { mutableStateOf("") }
            var choose by remember { mutableStateOf(false) }
            var launchAfterChoice by remember { mutableStateOf(false) }
            var pendingLaunch by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            val context = requireContext()
            val gog by GogLibrary.state.collectAsStateWithLifecycle()
            val extraStore = LudashiStore.find(storeId)
            val extra = extraStore?.let { StoreSessions.state(it).collectAsStateWithLifecycle().value }
            val installedRecord = if (storeId == "gog") gog.installed[gameId] else extra?.installed?.get(storeHash(gameId).take(32))
            val detail by produceState<Detail?>(null, revision, installedRecord, if (storeId == "gog") gog.revision else extra?.revision) {
                val result = withContext(Dispatchers.IO) { runCatching { load(context) } }
                result.onSuccess { value = it }.onFailure { error = it.message.orEmpty() }
            }
            val scope = rememberCoroutineScope()
            val item = detail
            if (item == null) Column(Modifier.fillMaxSize().padding(24.dp)) { if (error.isBlank()) CircularProgressIndicator() else Text(error) }
            else {
                fun launch() {
                    if (busy) return
                    val shortcut = item.shortcut
                    if (shortcut == null) { launchAfterChoice = true; choose = true; return }
                    val prefs = context.getSharedPreferences(if (storeId == "gog") "gog-settings" else "ludashi-store-games", 0)
                    val key = if (storeId == "gog") "last-run-$gameId" else "$storeId-${storeHash(gameId).take(32)}-last"
                    prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                    if (storeId == "gog") GogLibrary.mutable.update { it.copy(revision = it.revision + 1) }
                    else LudashiStore.find(storeId)?.let { store -> StoreSessions.state(store).update { it.copy(revision = it.revision + 1) } }
                    startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id).putExtra("shortcut_path", shortcut.file.path).putExtra("shortcut_name", shortcut.name))
                }
                LaunchedEffect(pendingLaunch, item.shortcut) { if (pendingLaunch && item.shortcut != null) { pendingLaunch = false; launch() } }
                val callbacks = object : StoreDetailCallbacks {
                    override fun onStoreExecutable() { launchAfterChoice = false; choose = true }
                    override fun onPlay() = launch()
                    override fun onConfigure() {
                        if (item.shortcut == null) { launchAfterChoice = false; choose = true } else com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog.show(this@StoreDetailFragment, item.shortcut)
                    }
                    override fun onArguments() {
                        startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", item.record.getInt("container")))
                    }
                    override fun onSaves() { item.shortcut?.let { GameSavesComposeDialog.show(this@StoreDetailFragment, it) } ?: notice(uiStr(R.string.gog_choose_executable)) }
                    override fun onFavorite(favorite: Boolean) {
                        val gog = storeId == "gog"
                        context.getSharedPreferences(if (gog) "gog-settings" else "ludashi-store-games", 0).edit()
                            .putBoolean(if (gog) "favorite-$gameId" else "$storeId-${storeHash(gameId).take(32)}-favorite", favorite).apply()
                        if (gog) GogLibrary.mutable.update { it.copy(revision = it.revision + 1) }
                        else LudashiStore.find(storeId)?.let { store -> StoreSessions.state(store).update { it.copy(revision = it.revision + 1) } }
                    }
                    override fun onRemove() {
                        com.winlator.cmod.ui.ConfirmActionDialog.show(this@StoreDetailFragment, uiStr(R.string.steam_ui_uninstall), uiStr(R.string.gog_uninstall_help), uiStr(R.string.steam_ui_uninstall), {
                            if (storeId == "gog") GogLibrary.uninstall(context, gameId, item.record) else StoreInstalls.uninstall(context, item.record)
                        }, { requireActivity().finish() })
                    }
                    override fun onHome() { item.shortcut?.let { HomeShortcuts.pin(requireActivity(), it) } ?: notice(uiStr(R.string.gog_choose_executable)) }
                }
                val prefs = context.getSharedPreferences(if (storeId == "gog") "gog-settings" else "ludashi-store-games", 0)
                val favorite = prefs.getBoolean(if (storeId == "gog") "favorite-$gameId" else "$storeId-${storeHash(gameId).take(32)}-favorite", false)
                GameDetailComposeHost.StoreContent(item.title, item.brand, item.artwork?.path, favorite, item.shortcut, callbacks, item.record.optLong("installedSize").takeIf { it > 0 }?.let { if (it >= 1073741824L) "%.1f GiB".format(it / 1073741824.0) else "%.1f MiB".format(it / 1048576.0) } ?: if (StoreSessions.paused) "—" else null, installed = item.installed, executable = item.record.optString("executable"), installOptions = { StoreInstallOptions(storeId, gameId, item.record) }) {
                    StoreInformation(storeId, gameId, item.record)
                    if (storeId == "itch") {
                        val description = item.record.optJSONObject("game")?.optJSONObject("metadata")?.optJSONObject("information")?.optString("description").orEmpty()
                        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                if (choose) StoreExecutablePicker(item.title,
                    item.record.getJSONArray("executables").let { options -> (0 until options.length()).map { options.getString(it) } },
                    item.record.optString("executable"), busy, { choose = false; launchAfterChoice = false }) { path ->
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching {
                                        val record = JSONObject(item.record.toString()).put("executable", path)
                                        val cid = record.getInt("container")
                                        check(com.winlator.cmod.core.ContainerSessionGate.edit(cid)) { "Exit the container before changing its shortcut" }
                                        try {
                                            if (storeId == "gog") {
                                                GogDownloadService.writeShortcut(context, gameId, record)
                                                GogLibrary.atomic(File(GogLibrary.records(context), "$gameId.json"), record.toString()); GogLibrary.updateInstalled(context)
                                            } else {
                                                val store = LudashiStore.find(storeId)!!
                                                StoreInstalls.writeShortcut(context, record)
                                                atomicStoreFile(File(StoreSessions.records(context, store), "${storeHash(gameId).take(32)}.json"), record.toString()); StoreSessions.installed(context, store)
                                            }
                                        } finally { com.winlator.cmod.core.ContainerSessionGate.edited(cid) }
                                    } }
                                    busy = false
                                    result.onSuccess { choose = false; pendingLaunch = launchAfterChoice; revision++ }.onFailure { notice(it.message.orEmpty()) }
                                }
                }
            }
        } }
    }
    private fun load(context: Context): Detail {
        if (storeId == "gog") {
            require(gameId.toLongOrNull()?.let { it > 0 } == true)
            val file = File(GogLibrary.records(context), "$gameId.json")
            if (!file.isFile) {
                val game = GogLibrary.state.value.games.find { it.id == gameId } ?: error("Game not found in the GOG library")
                return Detail(game.title, "GOG", File(context.cacheDir, "gog-art/$gameId.jpg").takeIf { it.isFile }, game.json(), null, false)
            }
            val record = JSONObject(file.readText())
            if (!record.has("installedSize") && !StoreSessions.paused) {
                val bytes = File(record.getString("path")).walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.sumOf { it.length() }
                record.put("installedSize", bytes)
                GogLibrary.atomic(File(GogLibrary.records(context), "$gameId.json"), record.toString())
            }
            GogDownloadService.ensureIcon(context, gameId, record)
            val shortcut = runCatching { GogLibrary.shortcut(context, gameId) }.getOrNull()
            return Detail(record.getString("title"), "GOG", File(context.cacheDir, "gog-art/$gameId.jpg").takeIf { it.isFile }, record, shortcut)
        }
        val store = LudashiStore.find(storeId) ?: error("Unknown store")
        val file = File(StoreSessions.records(context, store), "${storeHash(gameId).take(32)}.json")
        if (!file.isFile) {
            val game = StoreSessions.state(store).value.games.find { it.id == gameId } ?: ItchCatalog.selected(context, gameId) ?: error("Game not found in the library")
            StoreSessions.requestArt(context, game.uri)
            return Detail(game.title, store.title, File(context.cacheDir, "$storeId-art/${game.key}.jpg").takeIf { it.isFile }, JSONObject().put("game", game.json()), null, false)
        }
        val record = JSONObject(file.readText())
        val game = StoreGame.parse(record.getJSONObject("game"))
        return Detail(game.title, store.title, File(context.cacheDir, "$storeId-art/${game.key}.jpg").takeIf { it.isFile }, record, runCatching { StoreInstalls.shortcut(context, record) }.getOrNull())
    }
}
