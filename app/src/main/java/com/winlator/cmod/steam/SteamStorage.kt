package com.winlator.cmod.steam

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Installation records stay private; content may live on any directly writable filesystem. */
object SteamStorage {
    fun root(context: Context): File = context.getSharedPreferences("steam-settings", 0).getString("download-root", null)
        ?.let(::File) ?: File(context.filesDir, "steam-library/common")
    fun setRoot(context: Context, directory: File?) {
        check(!SteamSession.downloading && !SteamSession.gameActive) { "Pause downloads and close games first" }
        if (directory == null) { context.getSharedPreferences("steam-settings", 0).edit().remove("download-root").apply(); return }
        require(directory.isAbsolute) { "Choose an absolute filesystem path" }
        val root = File(directory.canonicalFile, "LudashiSteam").apply { check(mkdirs() || isDirectory) { "Cannot create Steam library" } }
        val probe = File.createTempFile(".ludashi-write-", ".tmp", root)
        try { probe.writeText("Steam library") } finally { probe.delete() }
        context.getSharedPreferences("steam-settings", 0).edit().putString("download-root", root.path).apply()
    }
    fun download(context: Context, id: Int, name: String): File {
        val prefs = context.getSharedPreferences("steam-settings", 0)
        val installed = File(context.filesDir, "steam-library/$id.json")
        if (installed.isFile) return directory(context, JSONObject(installed.readText()))
        val legacy = File(context.filesDir, "steam-library/common/$name")
        val root = prefs.getString("pending-root-$id", null)?.let(::File) ?: (if (legacy.isDirectory) legacy.parentFile else root(context)).canonicalFile.also {
            prefs.edit().putString("pending-root-$id", it.path).commit()
        }
        return File(root, name).canonicalFile.also { require(it.parentFile == root.canonicalFile); check(it.mkdirs() || it.isDirectory) }
    }
    fun record(info: JSONObject, dir: File, branch: String = "public", language: String = "english"): JSONObject =
        JSONObject(info.toString()).put("ludashiInstallPath", dir.canonicalPath)
            .put("ludashiInstallRoot", dir.canonicalFile.parent)
            .put("ludashiInstallBranch", branch).put("ludashiInstallLanguage", language)
    fun directory(context: Context, info: JSONObject): File = info.optString("ludashiInstallPath").takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
        ?: File(context.filesDir, "steam-library/common/${SteamCatalog.installName(info)}").canonicalFile
    fun installationRoot(context: Context, info: JSONObject): File = info.optString("ludashiInstallRoot").takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
        ?: File(context.filesDir, "steam-library/common").canonicalFile
    fun removePending(context: Context, id: Int, name: String) {
        check(id > 0 && name.isNotBlank()) { "Invalid paused Steam download" }
        val record = File(context.filesDir, "steam-library/$id.json")
        check(!record.isFile) { "Installed games cannot be deleted as a paused download" }
        val prefs = context.getSharedPreferences("steam-settings", 0)
        val stored = prefs.getString("pending-root-$id", null) ?: error("No paused download directory recorded")
        val root = File(stored).canonicalFile
        val targetDir = File(root, name).canonicalFile
        check(targetDir.parentFile == root && targetDir != root) { "Unsafe Steam download directory" }
        check(!java.nio.file.Files.isSymbolicLink(File(root, name).toPath())) { "Unsafe download symlink" }
        val installedRecords = File(context.filesDir, "steam-library").listFiles().orEmpty().filter {
            it.isFile && it.extension == "json" && it.nameWithoutExtension.toIntOrNull() != null
        }
        check(installedRecords.none { file ->
            runCatching { directory(context, JSONObject(file.readText())) == targetDir }.getOrDefault(false)
        }) { "Another installed Steam game uses this directory" }
        if (targetDir.exists()) {
            java.nio.file.Files.walk(targetDir.toPath()).use { stream ->
                stream.sorted(java.util.Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) }
            }
        }
        prefs.edit().remove("pending-root-$id").apply()
    }

}
