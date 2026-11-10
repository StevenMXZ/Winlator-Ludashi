package com.winlator.cmod.steam

import org.json.JSONObject
import java.io.File

/** Selection for the initial port: public branch, Windows, English, base game. */
object SteamCatalog {
    fun desktopExecPath(path: String): String = path.replace("\\", "\\\\\\\\")

    data class Depot(val id: Int, val manifest: Long)
    fun depots(info: JSONObject): List<Depot> {
        val depots = info.optJSONObject("depots") ?: error("No depot metadata")
        return depots.keys().asSequence().mapNotNull { key ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            val depot = depots.optJSONObject(key) ?: return@mapNotNull null
            val config = depot.optJSONObject("config") ?: JSONObject()
            val os = config.optString("oslist")
            val language = config.optString("language")
            if (os.isNotEmpty() && "windows" !in os.split(',')) return@mapNotNull null
            if (language.isNotEmpty() && language != "english") return@mapNotNull null
            if (depot.optInt("dlcappid") != 0) return@mapNotNull null
            val manifests = depot.optJSONObject("manifests") ?: return@mapNotNull null
            val raw = manifests.opt("public")
            val gid = if (raw is JSONObject) raw.optString("gid") else raw?.toString()
            val manifest = gid?.toULongOrNull()?.toLong() ?: return@mapNotNull null
            Depot(id, manifest)
        }.toList().also { require(it.isNotEmpty()) { "No supported public Windows depots" } }
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
