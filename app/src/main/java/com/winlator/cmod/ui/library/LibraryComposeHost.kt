package com.winlator.cmod.ui.library

import com.winlator.cmod.steam.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import android.content.Intent
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
data class LibraryItem(
    val id: String,
    val shortcutPath: String,
    val name: String,
    val containerName: String,
    val coverPath: String?,
    val bannerPath: String?,
    val iconPath: String?,
    val fallbackIcon: Bitmap?,
    val favorite: Boolean,
    val lastRunAt: Long
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
    private val selectedShortcutPath: MutableState<String?>
) {
    private val statePreferences = context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
    private val metadataGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setItems(value: List<LibraryItem>) {
        val snapshot = value.toList()
        items.value = snapshot
        val generation = metadataGeneration.incrementAndGet()
        Thread {
            val resolved = resolveLibraryEnvironmentLabels(context, snapshot)
            mainHandler.post {
                if (metadataGeneration.get() == generation) items.value = resolved
            }
        }.start()
    }

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
        val selectedShortcutPath = mutableStateOf(
            context.getSharedPreferences("library_compose_state", Context.MODE_PRIVATE)
                .getString("selected_shortcut_path", null)
        )
        val controller = LibraryComposeController(
            context.applicationContext,
            items,
            grid,
            query,
            selectedShortcutPath
        )
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZTheme {
                    CompositionLocalProvider(LocalArtworkMotion provides rememberArtworkMotionEnabled()) {
                        val steam = observeSteam()
                        val enabled = SteamSettings.enabled(context)
                        LaunchedEffect(enabled) {
                            if (enabled && SteamCredentials.load(context) != null) context.startForegroundService(Intent(context, SteamService::class.java))
                        }
                        val steamItems = remember(enabled, steam.library, steam.tick / 3) { if (enabled) SteamLibrary.items(context, steam.library) else emptyList() }
                        val steamPaths by produceState(emptySet<String>(), items.value) {
                            value = withContext(Dispatchers.IO) { items.value.filter { item -> runCatching { java.io.File(item.shortcutPath).readText().contains("steamAppId=") }.getOrDefault(false) }.map { it.shortcutPath }.toSet() }
                        }
                        val merged = items.value.filterNot { it.shortcutPath in steamPaths } + steamItems
                        val combined = object : LibraryCallbacks {
                            override fun onOpen(path: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.open(context, path) else callbacks.onOpen(path) }
                            override fun onRun(path: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.open(context, path) else callbacks.onRun(path) }
                            override fun onGridViewChanged(value: Boolean) = callbacks.onGridViewChanged(value)
                            override fun onAction(path: String, action: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.open(context, path) else callbacks.onAction(path, action) }
                            override fun onArtworkNeeded(path: String, kind: String) { if (SteamLibrary.appId(path) != null) SteamLibrary.requestArtwork(context, path, kind) else callbacks.onArtworkNeeded(path, kind) }
                        }
                        Column(Modifier.fillMaxSize()) {
                            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                            if (configuration.screenWidthDp <= configuration.screenHeightDp) SteamDownloadPanel(steam)
                            Box(Modifier.weight(1f)) {
                                LibraryRootWithoutEmptyDescription(merged, grid.value, query.value, selectedShortcutPath, combined)
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

internal fun filterLibraryItems(items: List<LibraryItem>, filter: LibraryFilter): List<LibraryItem> {
    val local = items.filter { SteamLibrary.appId(it.shortcutPath) == null || it.containerName == "Steam · Installed" }
    return when (filter) {
        LibraryFilter.All -> local
        LibraryFilter.Favorites -> items.filter { it.favorite }
        LibraryFilter.Recent -> local.sortedByDescending { it.lastRunAt }
        LibraryFilter.Steam -> items.filter { SteamLibrary.appId(it.shortcutPath) != null }
    }
}

