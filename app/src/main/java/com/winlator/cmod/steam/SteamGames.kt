package com.winlator.cmod.steam

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

object SteamGames {
    private val revision = mutableIntStateOf(0)
    @JvmStatic fun displayName(shortcut: Shortcut): String = cleanTitle(shortcut.name, shortcut.getExtra("steamAppId"))
    fun cleanTitle(title: String, id: String): String = if (id.toIntOrNull() != null) title.removeSuffix(" [$id]") else title
    @JvmStatic fun launchTitle(context: Context, path: String?, title: String): String = runCatching {
        val lines = File(path ?: return title).readLines()
        val id = lines.firstOrNull { it.startsWith("steamAppId=") }?.substringAfter('=') ?: return title
        cleanTitle(title, id)
    }.getOrDefault(title)
    fun changed() { revision.intValue++ }
    fun info(context: Context, id: Int) = JSONObject(File(context.filesDir, "steam-library/$id.json").readText())
    fun directory(context: Context, id: Int): File = File(context.filesDir, "steam-library/common/${SteamCatalog.installName(info(context, id))}").canonicalFile
    fun shortcut(context: Context, id: Int): Shortcut? {
        revision.intValue
        val candidates = ContainerManager(context).loadShortcuts().filter { it.getExtra("steamAppId") == id.toString() }
        val preferred = context.getSharedPreferences("steam-settings", 0).getInt("steam-container-$id", -1)
        return candidates.firstOrNull { it.container.id == preferred } ?: candidates.firstOrNull()
    }
    fun configured(context: Context, id: Int) = shortcut(context, id)?.getExtra("steamConfigured") == "1"
    fun draft(context: Context, id: Int, container: com.winlator.cmod.container.Container): Shortcut {
        container.desktopDir.mkdirs()
        val name = info(context, id).optJSONObject("common")?.optString("name").orEmpty()
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(100).ifBlank { "Steam" }
        val preferred = File(container.desktopDir, "$name.desktop")
        val file = if (!preferred.exists() || Shortcut(container, preferred).getExtra("steamAppId") == id.toString()) preferred else File(container.desktopDir, "$name [$id].desktop")
        if (!file.exists()) file.writeText("[Desktop Entry]\nName=Steam-$id\nExec=wine C:\\\\unconfigured.exe\nType=Application\n\n[Extra Data]\nsteamAppId=$id\n")
        return Shortcut(container, file)
    }
    fun uninstall(context: Context, id: Int) {
        check(!SteamSession.gameActive && !SteamSession.downloading) { "Close Steam games and pause downloads before uninstalling" }
        val shortcuts = ContainerManager(context).loadShortcuts().filter { it.getExtra("steamAppId") == id.toString() }
        val locked = mutableListOf<Int>()
        try {
            for (container in shortcuts.map { it.container }.distinctBy { it.id }) {
                check(com.winlator.cmod.core.ContainerSessionGate.edit(container.id)) { "Exit the container before uninstalling" }
                locked.add(container.id)
            }
            val dir = directory(context, id)
            val shared = File(context.filesDir, "steam-library").listFiles().orEmpty().any { file ->
                val otherId = file.nameWithoutExtension.toIntOrNull()
                otherId != null && otherId != id && file.extension == "json" && runCatching { directory(context, otherId) == dir }.getOrDefault(false)
            }
            check(!shared) { "Another installed Steam game shares this directory; uninstall the shared installation manually" }
            val root = File(context.filesDir, "steam-library/common").canonicalFile.toPath()
            check(dir.toPath().startsWith(root) && dir.toPath() != root) { "Invalid Steam installation directory" }
            if (dir.exists()) Files.walk(dir.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            check(File(context.filesDir, "steam-library/$id.json").delete()) { "Could not remove Steam installation record" }
            shortcuts.forEach { check(it.file.delete()) { "Could not remove game shortcut" } }
        } finally { locked.forEach { com.winlator.cmod.core.ContainerSessionGate.edited(it) } }
    }
}
