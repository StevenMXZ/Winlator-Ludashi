package com.winlator.cmod.ui.library

import com.winlator.cmod.steam.*
import com.winlator.cmod.gog.GogLibrary
import com.winlator.cmod.gog.GogDownloadPanel
import com.winlator.cmod.stores.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.steam.SteamActivity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.winlator.cmod.MainActivity
import com.winlator.cmod.ui.LibraryToolbarActions
import com.winlator.cmod.ui.applyAppFullscreen
import com.winlator.cmod.ui.theme.WinZTheme
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class LibraryItem @JvmOverloads constructor(
    val id: String,
    val shortcutPath: String,
    val name: String,
    val containerName: String,
    val coverPath: String?,
    val bannerPath: String?,
    val iconPath: String?,
    val fallbackIcon: Bitmap?,
    val favorite: Boolean,
    val lastRunAt: Long,
    val wineVersion: String? = null,
    val renderer: String? = null,
    val steamAppId: Int? = null
)

@Stable
interface LibraryCallbacks {
    fun onOpen(shortcutPath: String)
    fun onRun(shortcutPath: String)
    fun onGridViewChanged(gridView: Boolean)
    fun onAction(shortcutPath: String, action: String)
    fun onArtworkNeeded(shortcutPath: String, kind: String)
}

class LibraryComposeController internal constructor(
    private val context: Context,
    private val items: MutableState<List<LibraryItem>>,
    private val grid: MutableState<Boolean>,
    private val query: MutableState<String>,
    private val selectedShortcutPath: MutableState<String?>,
    private val showAddGames: MutableState<Boolean>
) {
    private val statePreferences = context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
    private val metadataGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    companion object { private val metadataWorker = java.util.concurrent.Executors.newSingleThreadExecutor() }

    fun setItems(value: List<LibraryItem>) {
        val snapshot = value.toList()
        items.value = snapshot
        val generation = metadataGeneration.incrementAndGet()
        metadataWorker.execute {
            if (metadataGeneration.get() != generation) return@execute
            val resolved = resolveLibraryEnvironmentLabels(context, snapshot)
            mainHandler.post {
                if (metadataGeneration.get() == generation) items.value = resolved
            }
        }
    }

    fun dispose() { metadataGeneration.incrementAndGet() }

    fun showAddGamesChooser() { showAddGames.value = true }
    fun setGridView(value: Boolean) { grid.value = value }
    fun setSearchQuery(value: String?) { query.value = value?.trim().orEmpty() }

    fun setSelectedShortcutPath(value: String?) {
        selectedShortcutPath.value = value
        statePreferences.edit().apply {
            if (value.isNullOrEmpty()) remove("selected_shortcut_path")
            else putString("selected_shortcut_path", value)
        }.apply()
    }
}

class LibraryComposeBinding internal constructor(
    val view: ComposeView,
    val controller: LibraryComposeController
)

object LibraryComposeHost {
    const val ACTION_SETTINGS = "settings"
    const val ACTION_ICON = "icon"
    const val ACTION_CLONE = "clone"
    const val ACTION_HOME = "home"
    const val ACTION_EXPORT = "export"
    const val ACTION_REMOVE = "remove"
    const val ACTION_FAVORITE = "favorite"

    @JvmStatic
    fun create(
        context: Context,
        initialGridView: Boolean,
        callbacks: LibraryCallbacks
    ): LibraryComposeBinding {
        val activity = context as? MainActivity
        applyAppFullscreen(activity)
        activity?.let(LibraryToolbarActions::install)

        val items = mutableStateOf<List<LibraryItem>>(emptyList())
        val grid = mutableStateOf(initialGridView)
        val query = mutableStateOf("")
        val showAddGames = mutableStateOf(false)
        val selectedShortcutPath = mutableStateOf(
            context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
                .getString("selected_shortcut_path", null)
        )
        val controller = LibraryComposeController(
            context.applicationContext,
            items,
            grid,
            query,
            selectedShortcutPath,
            showAddGames
        )
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZTheme {
                    CompositionLocalProvider(LocalArtworkMotion provides rememberArtworkMotionEnabled()) {
                        val steam = observeSteam()
                        val gog by GogLibrary.state.collectAsStateWithLifecycle()
                        val gogEnabled = com.winlator.cmod.gog.GogSettings.enabled(context)
                        val gogItems by produceState<List<LibraryItem>>(emptyList(), gog.games, gog.installed, gog.revision, gogEnabled) {
                            value = withContext(Dispatchers.IO) { GogLibrary.items(context, gog) }
                        }
                        val itch by StoreSessions.state(LudashiStore.ITCH).collectAsStateWithLifecycle()
                        val amazon by StoreSessions.state(LudashiStore.AMAZON).collectAsStateWithLifecycle()
                        val itchEnabled = LudashiStore.ITCH.enabled(context)
                        val amazonEnabled = LudashiStore.AMAZON.enabled(context)
                        val libraryOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        LaunchedEffect(libraryOwner, gogEnabled, itchEnabled, amazonEnabled) {
                            libraryOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                                StoreSessions.resumeForLibrary()
                                if (gogEnabled) GogLibrary.open(context)
                                if (itchEnabled) StoreSessions.open(context, LudashiStore.ITCH)
                                if (amazonEnabled) StoreSessions.open(context, LudashiStore.AMAZON)
                                awaitCancellation()
                            }
                        }
                        val itchItems by produceState<List<LibraryItem>>(emptyList(), itch.games, itch.installed, itch.revision, itchEnabled) {
                            value = withContext(Dispatchers.IO) { StoreSessions.items(context, LudashiStore.ITCH, itch) }
                        }
                        val amazonItems by produceState<List<LibraryItem>>(emptyList(), amazon.games, amazon.installed, amazon.revision, amazonEnabled) {
                            value = withContext(Dispatchers.IO) { StoreSessions.items(context, LudashiStore.AMAZON, amazon) }
                        }
                        val enabled = SteamSettings.enabled(context)
                        val steamFilterAvailable = steamLibraryFilterAvailable(enabled, steam.signedIn, steam.savedAccount)
                        LaunchedEffect(enabled) {
                            val saved = withContext(Dispatchers.IO) { enabled && SteamCredentials.load(context) != null }
                            if (saved) context.startForegroundService(Intent(context, SteamService::class.java))
                        }
                        val steamItems = rememberSteamLibrary(context, steam.library, enabled)
                        val merged = remember(items.value, steamItems, gogItems, gog.installed, gogEnabled, itchItems, amazonItems, itch.installed, amazon.installed, itchEnabled, amazonEnabled) {
                            val knownSteam = steamItems.mapNotNull { SteamLibrary.appId(it.shortcutPath) }.toSet()
                            val gogShortcuts = if (gogEnabled) gog.installed.values.map { it.optString("shortcut") }.toSet() else emptySet()
                            val storeShortcuts = (if (itchEnabled) itch.installed.values else emptyList()) + (if (amazonEnabled) amazon.installed.values else emptyList())
                            val knownStoreShortcuts = storeShortcuts.map { it.optString("shortcut") }.toSet()
                            val local = items.value.filter { it.shortcutPath !in knownStoreShortcuts && (it.steamAppId == null || it.steamAppId !in knownSteam) && it.shortcutPath !in gogShortcuts }
                                .map { item ->
                                    item.steamAppId?.let { id ->
                                        item.copy(id = "steam-$id", shortcutPath = "steam://$id", containerName = "Steam · Installed")
                                    } ?: item
                                }.distinctBy { it.id }
                            local + steamItems + gogItems + itchItems + amazonItems
                        }
                        val combined = remember(context, callbacks) { object : LibraryCallbacks {
                            override fun onOpen(path: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.open(context, path) else if (GogLibrary.id(path) != null) GogLibrary.openGame(context, path) else if (LudashiStore.fromPath(path) != null) StoreSessions.openGame(context, path) else callbacks.onOpen(path) }
                            override fun onRun(path: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.open(context, path) else if (GogLibrary.id(path) != null) GogLibrary.openGame(context, path) else if (LudashiStore.fromPath(path) != null) StoreSessions.openGame(context, path) else callbacks.onRun(path) }
                            override fun onGridViewChanged(value: Boolean) = callbacks.onGridViewChanged(value)
                            override fun onAction(path: String, action: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.open(context, path) else if (GogLibrary.id(path) != null) GogLibrary.openGame(context, path) else if (LudashiStore.fromPath(path) != null) StoreSessions.openGame(context, path) else callbacks.onAction(path, action) }
                            override fun onArtworkNeeded(path: String, kind: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.requestArtwork(context, path, kind) else if (GogLibrary.id(path) != null) GogLibrary.requestArt(context, path) else if (LudashiStore.fromPath(path) != null) StoreSessions.requestArt(context, path) else callbacks.onArtworkNeeded(path, kind) }
                        } }
                        Column(Modifier.fillMaxSize()) {
                            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                            if (configuration.screenWidthDp <= configuration.screenHeightDp) {
                                SteamDownloadPanel(steam)
                                if (gogEnabled && gog.busy) GogDownloadPanel(gog, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                                if (itchEnabled && itch.busy) StoreTransferPanel(LudashiStore.ITCH, itch, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                                if (amazonEnabled && amazon.busy) StoreTransferPanel(LudashiStore.AMAZON, amazon, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                            }
                            Box(Modifier.weight(1f)) {
                                LibraryRootWithoutEmptyDescription(merged, grid.value, query.value, selectedShortcutPath, combined, showAddGames, steamFilterAvailable)
                            }
                        }
                    }
                }
            }
        }
        return LibraryComposeBinding(view, controller)
    }
}

internal enum class LibraryFilter { All, Favorites, Recent, Steam }

internal fun steamLibraryFilterAvailable(enabled: Boolean, signedIn: Boolean, savedAccount: Boolean): Boolean =
    enabled && (signedIn || savedAccount)

internal fun filterLibraryItems(items: List<LibraryItem>, filter: LibraryFilter): List<LibraryItem> {
    return when (filter) {
        LibraryFilter.All -> items.sortedByDescending(::isInstalledLibraryItem)
        LibraryFilter.Favorites -> items.filter { it.favorite }
        LibraryFilter.Recent -> items.sortedByDescending { it.lastRunAt }
        LibraryFilter.Steam -> items.filter { SteamLibrary.appId(it.shortcutPath) != null }
    }
}

internal fun filterLibraryStores(items: List<LibraryItem>, mask: Int): List<LibraryItem> = items.filter {
    val bit = if (it.shortcutPath.startsWith("steam://")) 2 else if (it.shortcutPath.startsWith("gog://")) 4 else LudashiStore.fromPath(it.shortcutPath)?.bit ?: 1
    mask and bit != 0
}

internal fun isInstalledLibraryItem(item: LibraryItem): Boolean {
    val remote = item.shortcutPath.startsWith("steam://") || item.shortcutPath.startsWith("gog://") || LudashiStore.fromPath(item.shortcutPath) != null
    return !remote || item.containerName.endsWith("· Installed")
}
