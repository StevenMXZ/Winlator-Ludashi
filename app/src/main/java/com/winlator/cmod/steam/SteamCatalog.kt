package com.winlator.cmod.steam

import org.json.JSONObject
import android.content.Context
import java.io.File

/** Windows base-game depot selection and Steam install metadata. */
object SteamCatalog {
    fun desktopExecPath(path: String): String = path.replace("\\", "\\\\\\\\")

    data class Depot(
        val id: Int,
        val manifest: Long,
        val size: Long = 0,
        val manifestAppId: Int = 0,
        val manifestBranch: String = "public",
    )

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
                key.toIntOrNull() != null && (depot.optJSONObject("manifests")?.has(name) == true ||
                    (depot.optInt("depotfromapp") > 0 && depot.optInt("sharedinstall") != 1))
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

    fun depots(
        info: JSONObject,
        branch: String = "public",
        language: String = "english",
        sourceInfo: ((Int) -> JSONObject)? = null,
    ): List<Depot> {
        val all = info.optJSONObject("depots") ?: error("No depot metadata")
        require(branch in branches(info)) { "Selected Steam version is unavailable" }
        require(language in languages(info)) { "Selected Steam language is unavailable" }
        val sources = mutableMapOf<Int, JSONObject>()
        val lookup = sourceInfo?.let { fetch -> { appId: Int -> sources.getOrPut(appId) { fetch(appId) } } }
        val shared = mutableListOf<Depot>()
        val local = mutableListOf<Depot>()
        for (key in all.keys()) {
            val id = key.toIntOrNull() ?: continue
            val depot = all.optJSONObject(key) ?: continue
            // Mode 1 is a separate shared installation, such as Steamworks Common Redistributables.
            val resolved = resolveDepot(id, depot, branch, language,
                lookup.takeUnless { depot.optInt("sharedinstall") == 1 }) ?: continue
            if (resolved.manifestAppId == 0) local.add(resolved) else shared.add(resolved)
        }
        // Base files must be in place before the game's own depots are applied.
        return (shared + local).also { require(it.isNotEmpty()) { "No Windows depots for the selected version and language" } }
    }

    private fun matchesInstall(depot: JSONObject, language: String): Boolean {
        val config = depot.optJSONObject("config")
        val os = config?.optString("oslist").orEmpty()
        val depotLanguage = config?.optString("language").orEmpty()
        return (os.isBlank() || os.split(',').any { it.trim().equals("windows", true) }) &&
            (depotLanguage.isBlank() || depotLanguage.equals(language, true)) &&
            (config?.optString("lowviolence") !in listOf("1", "true")) &&
            depot.optInt("dlcappid") == 0
    }

    private fun resolveDepot(
        id: Int,
        entry: JSONObject,
        branch: String,
        language: String,
        sourceInfo: ((Int) -> JSONObject)?,
    ): Depot? {
        var depot = entry
        var appId = 0
        val visited = mutableSetOf<Int>()
        while (matchesInstall(depot, language)) {
            val manifests = depot.optJSONObject("manifests")
            val manifestBranch = if (manifests?.has(branch) == true) branch else "public"
            val raw = manifests?.opt(manifestBranch)
            if (raw != null) {
                val gid = if (raw is JSONObject) raw.optString("gid") else raw.toString()
                val manifest = gid.toULongOrNull()?.toLong() ?: return null
                return Depot(id, manifest, if (raw is JSONObject) raw.optLong("size").coerceAtLeast(0) else 0,
                    appId, manifestBranch)
            }
            appId = depot.optInt("depotfromapp")
            if (appId <= 0 || sourceInfo == null) {
                check(visited.isEmpty()) { "Steam manifest missing for shared depot $id" }
                return null
            }
            check(visited.add(appId)) { "Circular Steam metadata for shared depot $id" }
            depot = sourceInfo(appId).optJSONObject("depots")?.optJSONObject(id.toString())
                ?: error("Steam app $appId has no metadata for shared depot $id")
        }
        return null
    }

    fun manifestList(depots: List<Depot>): String =
        depots.joinToString(",") { "${it.id}:${it.manifest.toULong()}:0" }

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
