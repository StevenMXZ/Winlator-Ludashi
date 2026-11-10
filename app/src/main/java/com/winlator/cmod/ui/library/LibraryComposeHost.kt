package com.winlator.cmod.ui.library

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
                        LibraryRootWithoutEmptyDescription(items.value, grid.value, query.value, selectedShortcutPath, callbacks)
                    }
                }
            }
        }
        return LibraryComposeBinding(view, controller)
    }
}

internal enum class LibraryFilter { All, Favorites, Recent }

