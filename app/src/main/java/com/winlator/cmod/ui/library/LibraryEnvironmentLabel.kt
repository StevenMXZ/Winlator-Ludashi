package com.winlator.cmod.ui.library

import android.content.Context
import androidx.compose.runtime.Composable
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.WineInfo

// Reuse shortcut metadata from the loader; do not reopen and decode every shortcut again.
internal fun resolveLibraryEnvironmentLabels(context: Context, items: List<LibraryItem>): List<LibraryItem> = runCatching {
    if (items.none { it.wineVersion != null }) return items
    val contents = ContentsManager(context).apply { syncContents() }
    val runtimes = mutableMapOf<String, String>()
    items.map { item ->
        val identifier = item.wineVersion ?: return@map item
        val runtime = runtimes.getOrPut(identifier) {
            val info = WineInfo.fromIdentifier(context, contents, identifier)
            var version = info.fullVersion()
            if (version.endsWith(".0")) version = version.dropLast(2)
            (if (info.type.equals("proton", true)) "Proton " else "Wine ") + version + " " + info.getArch()
        }
        item.copy(containerName = "$runtime · ${item.renderer ?: "Vulkan"}")
    }
}.getOrDefault(items)

@Composable internal fun libraryEnvironmentLabel(item: LibraryItem): String = item.containerName
