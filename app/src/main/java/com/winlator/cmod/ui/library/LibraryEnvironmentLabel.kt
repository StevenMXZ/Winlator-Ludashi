package com.winlator.cmod.ui.library

import android.content.Context
import androidx.compose.runtime.Composable
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.WineInfo

private fun environmentText(
    context: Context,
    contents: ContentsManager,
    shortcutPath: String,
    fallback: String,
    shortcuts: Map<String, Shortcut>
): String = runCatching {
    val shortcut = shortcuts[shortcutPath] ?: return@runCatching fallback
    val info = WineInfo.fromIdentifier(context, contents, shortcut.container.getWineVersion())
    var version = info.fullVersion()
    if (version.endsWith(".0")) version = version.dropLast(2)
    val runtime = (if (info.type.equals("proton", true)) "Proton " else "Wine ") + version + " " + info.getArch()
    val renderer = when {
        shortcut.getUseDisplayX() -> "DisplayX"
        shortcut.getRendererNative() -> "EGL"
        else -> "Vulkan"
    }
    "$runtime · $renderer"
}.getOrDefault(fallback)

internal fun resolveLibraryEnvironmentLabels(context: Context, items: List<LibraryItem>): List<LibraryItem> = runCatching {
    val manager = ContainerManager(context)
    val shortcuts = manager.loadShortcuts().filterNotNull().associateBy { it.file.path }
    val contents = ContentsManager(context).apply { syncContents() }
    items.map { item ->
        item.copy(
            containerName = environmentText(context, contents, item.shortcutPath, item.containerName, shortcuts)
        )
    }
}.getOrDefault(items)

@Composable
internal fun libraryEnvironmentLabel(item: LibraryItem): String = item.containerName
