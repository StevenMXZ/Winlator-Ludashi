package com.winlator.cmod.storage

import android.content.Context
import android.os.Environment
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.gog.*
import com.winlator.cmod.steam.*
import com.winlator.cmod.stores.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

internal object StorageRepository {
    @Volatile var cached: StorageSnapshot? = null
    private fun json(file: File) = runCatching { JSONObject(file.readText()) }.getOrNull()
    private fun records(root: File) = root.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
    fun busy() = ContainerSessionGate.anyBusy() || SteamSession.downloading || com.winlator.cmod.components.ComponentDownloads.busy || com.winlator.cmod.contents.Downloader.isBusy() || SteamNativeCloud.busy || GogLibrary.state.value.busy || StoreSessions.state(LudashiStore.ITCH).value.busy
    fun caches(context: Context) = (listOf(context.cacheDir, context.codeCacheDir) + context.externalCacheDirs.filterNotNull()).distinctBy { it.canonicalPath }
    private fun targets(context: Context): List<StorageTarget> {
        val files = context.filesDir
        val result = mutableListOf(StorageTarget(context.dataDir, StorageCategory.OTHER, "Ludashi"))
        fun node(file: File, category: StorageCategory, title: String, source: String = "", id: String = "", removable: Boolean = false) {
            if (file.exists() && !Files.isSymbolicLink(file.toPath())) result.add(StorageTarget(file.canonicalFile, category, title, source, id, removable))
        }
        node(File(files, "imagefs"), StorageCategory.SYSTEM, "Wine / Linux")
        context.applicationInfo.sourceDir?.let { node(File(it), StorageCategory.SYSTEM, "Ludashi APK") }
        context.applicationInfo.splitSourceDirs.orEmpty().forEach { node(File(it), StorageCategory.SYSTEM, File(it).name) }
        node(File(context.applicationInfo.nativeLibraryDir), StorageCategory.SYSTEM, "Ludashi native libraries")
        context.getExternalFilesDirs(null).filterNotNull().forEach { node(it, StorageCategory.OTHER, "Ludashi") }
        caches(context).forEach { node(it, StorageCategory.CACHE, it.name, "cache", removable = true) }
        val components = listOf(File(files, "contents"), File(files, "opengl-bundled-v1"), File(files, "imagefs/opt/installed-wine"))
        for (root in components) {
            node(root, StorageCategory.COMPONENTS, root.name)
            root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) }.forEach { type ->
                if (root.name == "contents") type.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.forEach { version -> node(version, StorageCategory.COMPONENTS, "${type.name} · ${version.name}") }
                else node(type, StorageCategory.COMPONENTS, type.name)
            }
        }
        val containers = ContainerManager(context).containers
        containers.forEach { node(it.rootDir, StorageCategory.CONTAINERS, it.name, "container", it.id.toString()) }
        node(File(files, "steam-library/common"), StorageCategory.DOWNLOADS, "Steam")
        for (record in records(File(files, "steam-library"))) {
            val id = record.nameWithoutExtension.toIntOrNull() ?: continue
            val data = json(record) ?: continue
            runCatching {
                val dir = SteamStorage.directory(context, data)
                val root = SteamStorage.installationRoot(context, data)
                require(dir.toPath().startsWith(root.toPath()) && dir != root && dir.parentFile == root)
                node(dir, StorageCategory.GAMES, data.optJSONObject("common")?.optString("name").orEmpty().ifBlank { dir.name }, "steam", id.toString(), true)
            }
        }
        val prefs = context.getSharedPreferences("steam-settings", 0)
        prefs.all.filterKeys { it.startsWith("pending-root-") }.forEach { (key, value) ->
            val id = key.removePrefix("pending-root-").toIntOrNull() ?: return@forEach
            if (!File(files, "steam-library/$id.json").exists()) {
                val name = prefs.getString("pending-name-$id", null) ?: SteamSession.downloadName.takeIf { SteamSession.downloadId == id } ?: return@forEach
                val root = File(value as? String ?: return@forEach).canonicalFile
                val target = File(root, name)
                if (target.canonicalFile.parentFile == root) node(target, StorageCategory.DOWNLOADS, name, "steam-partial", id.toString(), true)
            }
        }
        for (record in records(File(files, "gog-installed"))) {
            val data = json(record) ?: continue
            val id = record.nameWithoutExtension
            val container = containers.find { it.id == data.optInt("container") } ?: continue
            val expected = File(container.rootDir, ".wine/drive_c/GOG Games/Ludashi-$id")
            if (id.toLongOrNull()?.let { it > 0 } == true && File(data.optString("path")).canonicalFile == expected.canonicalFile)
                node(expected, StorageCategory.GAMES, data.optString("title").ifBlank { "GOG-$id" }, "gog", id, true)
        }
        val store = LudashiStore.ITCH
        for (record in records(File(files, "ludashi-stores/itch/installed"))) {
            val data = json(record) ?: continue
            runCatching {
                val game = StoreGame.parse(data.getJSONObject("game"))
                val container = containers.find { it.id == data.optInt("container") } ?: return@runCatching
                val expected = File(container.rootDir, ".wine/drive_c/Ludashi Games/itch-${game.key}")
                if (game.store == store && File(data.optString("path")).canonicalFile == expected.canonicalFile)
                    node(expected, StorageCategory.GAMES, game.title, "itch", game.key, true)
            }
        }
        node(File(files, "store-transfers"), StorageCategory.DOWNLOADS, "itch.io")
        node(File(files, "tmp"), StorageCategory.DOWNLOADS, "Component installers")
        val partialRoots = listOf(File(files, "store-transfers"), StoreDownloadPreferences.folder(context, "itch"), StoreDownloadPreferences.folder(context, "gog"))
        val gogNames = records(File(files, "gog-transfers")).associate { it.nameWithoutExtension to json(it)?.optString("title").orEmpty() }
        partialRoots.forEach { root ->
            root.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.forEach { folder ->
                val itch = folder.name.matches(Regex("itch-[0-9a-f]{32}-[0-9a-f-]{36}"))
                val gog = folder.name in gogNames && folder.name.toLongOrNull()?.let { it > 0 } == true
                if (itch || gog) node(folder, StorageCategory.DOWNLOADS, if (gog) gogNames.getValue(folder.name).ifBlank { "GOG" } else "itch.io", "partial", folder.canonicalPath, true)
            }
        }
        node(File(files, "logs"), StorageCategory.LOGS, "Ludashi logs", "logs", removable = true)
        val logPreference = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).getString("winlator_path_uri", null)
        val publicRoot = logPreference?.let { com.winlator.cmod.core.FileUtils.getFilePathFromUri(context, android.net.Uri.parse(it)) }
            ?: com.winlator.cmod.SettingsFragment.DEFAULT_WINLATOR_PATH
        val publicLogs = File(publicRoot, "logs")
        node(publicLogs, StorageCategory.LOGS, "Ludashi logs")
        publicLogs.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in listOf("txt", "log") }.forEach { log ->
            node(log, StorageCategory.LOGS, log.name, "log", removable = true)
        }
        val saves = File(Environment.getExternalStorageDirectory(), "Winlator/Saves")
        node(saves, StorageCategory.BACKUPS, "Saves")
        saves.listFiles().orEmpty().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.forEach { game ->
            game.listFiles().orEmpty().filter { it.isFile && it.extension.equals("zip", true) }.forEach { backup -> node(backup, StorageCategory.BACKUPS, "${game.name} · ${backup.name}", "backup", removable = true) }
        }
        return result
    }
    fun scan(context: Context, check: () -> Unit): StorageSnapshot {
        val targets = targets(context)
        return StorageScanner.measure(listOf(context.dataDir) + targets.map { it.file }, targets, check).also { cached = it }
    }
    fun remove(context: Context, entry: StorageEntry) {
        check(!busy()) { com.winlator.cmod.ui.uiStr(com.winlator.cmod.R.string.storage_busy) }
        val target = targets(context).find { it.file.canonicalFile == entry.target.file.canonicalFile && it.source == entry.target.source && it.removable }
            ?: error("This storage entry is no longer available")
        when (target.source) {
            "steam" -> SteamGames.uninstall(context, target.id.toInt())
            "gog" -> GogLibrary.uninstall(context, target.id, JSONObject(File(context.filesDir, "gog-installed/${target.id}.json").readText()))
            "itch" -> StoreInstalls.uninstall(context, JSONObject(File(context.filesDir, "ludashi-stores/itch/installed/${target.id}.json").readText()))
            else -> {
                check(ContainerSessionGate.maintain()) { "A container is busy" }
                try {
                    when (target.source) {
                        "cache", "logs", "partial" -> {
                            val keep = targets(context).filter { it.category in listOf(StorageCategory.GAMES, StorageCategory.COMPONENTS, StorageCategory.CONTAINERS, StorageCategory.BACKUPS) }.map { it.file }
                            StorageScanner.clear(target.file, keep)
                            if (target.source == "partial") target.file.delete()
                        }
                        "backup", "log" -> check(target.file.delete()) { "Could not remove the save backup" }
                        "steam-partial" -> SteamStorage.removePending(context, target.id.toInt(), target.file.name)
                    }
                } finally { ContainerSessionGate.maintained() }
            }
        }
        cached = null
    }
}
