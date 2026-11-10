package com.winlator.cmod.steam

import org.json.JSONObject
import android.content.Context
import java.io.File

/** Selection for the initial port: public branch, Windows, English, base game. */
object SteamCatalog {
    fun desktopExecPath(path: String): String = path.replace("\\", "\\\\\\\\")

    data class Depot(val id: Int, val manifest: Long, val size: Long = 0)

    fun branches(info: JSONObject): List<String> {
        val depots = info.optJSONObject("depots") ?: return listOf("public")
        val entries = depots.optJSONObject("branches") ?: return listOf("public")
        val available = entries.keys().asSequence().filter { name ->
            val entry = entries.optJSONObject(name) ?: return@filter false
            name == "public" || (entry.optString("pwdrequired") !in listOf("1", "true") &&
                entry.optString("passwordrequired") !in listOf("1", "true"))
        }.filter { name ->
            name == "public" || depots.keys().asSequence().any { key ->
                val depot = depots.optJSONObject(key) ?: return@any false
                key.toIntOrNull() != null && depot.optJSONObject("manifests")?.has(name) == true
            }
        }.toList()
        return (listOf("public") + available.filter { it != "public" }.sorted()).distinct()
    }

    fun languages(info: JSONObject): List<String> {
        val depots = info.optJSONObject("depots") ?: return listOf("english")
        val names = depots.keys().asSequence().mapNotNull { key ->
            if (key.toIntOrNull() == null) return@mapNotNull null
            val config = depots.optJSONObject(key)?.optJSONObject("config") ?: return@mapNotNull null
            val os = config.optString("oslist")
            if (os.isNotBlank() && os.split(',').none { it.trim().equals("windows", true) }) return@mapNotNull null
            config.optString("language").lowercase().takeIf { it.isNotBlank() }
        }.toList()
        return (listOf("english") + names.filter { it != "english" }.sorted()).distinct()
    }

    fun depots(info: JSONObject, branch: String = "public", language: String = "english"): List<Depot> {
        val all = info.optJSONObject("depots") ?: error("No depot metadata")
        require(branch in branches(info)) { "Selected Steam version is unavailable" }
        require(language in languages(info)) { "Selected Steam language is unavailable" }
        return all.keys().asSequence().mapNotNull { key ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            val depot = all.optJSONObject(key) ?: return@mapNotNull null
            val config = depot.optJSONObject("config") ?: JSONObject()
            val os = config.optString("oslist")
            val depotLanguage = config.optString("language").lowercase()
            if (os.isNotBlank() && os.split(',').none { it.trim().equals("windows", true) }) return@mapNotNull null
            if (depotLanguage.isNotBlank() && depotLanguage != language) return@mapNotNull null
            if (depot.optInt("dlcappid") != 0) return@mapNotNull null
            val manifests = depot.optJSONObject("manifests") ?: return@mapNotNull null
            val raw = manifests.opt(branch) ?: manifests.opt("public") ?: return@mapNotNull null
            val gid = if (raw is JSONObject) raw.optString("gid") else raw.toString()
            val manifest = gid.toULongOrNull()?.toLong() ?: return@mapNotNull null
            Depot(id, manifest, if (raw is JSONObject) raw.optLong("size").coerceAtLeast(0) else 0)
        }.toList().also { require(it.isNotEmpty()) { "No Windows depots for the selected version and language" } }
    }
    fun installName(info: JSONObject): String {
        val name = info.getJSONObject("config").getString("installdir")
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it in "/\\\r\n\"" }) { "Invalid install directory" }
        return name
    }
    fun executables(info: JSONObject, dir: File): List<String> {
        val launch = info.optJSONObject("config")?.optJSONObject("launch") ?: return emptyList()
        return launch.keys().asSequence().mapNotNull { key ->
            val entry = launch.optJSONObject(key) ?: return@mapNotNull null
            val os = entry.optJSONObject("config")?.optString("oslist").orEmpty()
            if (os.isNotEmpty() && "windows" !in os.split(',')) return@mapNotNull null
            val exe = entry.optString("executable").replace('\\', '/')
            val candidate = File(dir, exe).canonicalFile
            if (exe.isEmpty() || !candidate.toPath().startsWith(dir.canonicalFile.toPath()) || !candidate.isFile || !exe.endsWith(".exe", true)) null else exe
        }.distinct().toList()
    }
}

object SteamInstallOptions {
    private fun prefs(context: Context) = context.getSharedPreferences("steam-settings", 0)
    fun branch(context: Context, id: Int): String = prefs(context).getString("install-branch-$id", "public") ?: "public"
    fun language(context: Context, id: Int): String = prefs(context).getString("install-language-$id", "english") ?: "english"
    fun setBranch(context: Context, id: Int, value: String) {
        check(!SteamSession.downloading && !SteamSession.gameActive)
        prefs(context).edit().putString("install-branch-$id", value).apply()
    }
    fun setLanguage(context: Context, id: Int, value: String) {
        check(!SteamSession.downloading && !SteamSession.gameActive)
        prefs(context).edit().putString("install-language-$id", value).apply()
    }
}
