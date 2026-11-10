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
    fun record(info: JSONObject, dir: File): JSONObject = JSONObject(info.toString()).put("ludashiInstallPath", dir.canonicalPath).put("ludashiInstallRoot", dir.canonicalFile.parent)
    fun directory(context: Context, info: JSONObject): File = info.optString("ludashiInstallPath").takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
        ?: File(context.filesDir, "steam-library/common/${SteamCatalog.installName(info)}").canonicalFile
    fun installationRoot(context: Context, info: JSONObject): File = info.optString("ludashiInstallRoot").takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
        ?: File(context.filesDir, "steam-library/common").canonicalFile
}
