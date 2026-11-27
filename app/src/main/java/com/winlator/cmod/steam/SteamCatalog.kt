package com.winlator.cmod.steam

import org.json.JSONObject
import android.content.Context
import java.io.File

/** Windows game/DLC depot selection and Steam install metadata. */
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
    ): List<Depot> = depots(info, branch, language, emptySet(), sourceInfo)

    fun depots(
        info: JSONObject,
        branch: String,
        language: String,
        selectedDlc: Set<Int>,
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
                lookup.takeUnless { depot.optInt("sharedinstall") == 1 }, selectedDlc) ?: continue
            if (resolved.manifestAppId == 0) local.add(resolved) else shared.add(resolved)
        }
        // Base files must be in place before the game's own depots are applied.
        return (shared + local).also { require(it.isNotEmpty()) { "No Windows depots for the selected version and language" } }
    }

    /** Estimate from owned PICS packages, not every public manifest.
     * Package metadata is advisory: the native downloader still validates depot keys and
     * manifest access (including demos, shared depots and resumed installations).
     */
    internal fun estimatedSize(depots: List<Depot>, library: JSONObject): Long? {
        if (depots.isEmpty()) return null
        val packages = library.optJSONArray("packages")
        val ownedIds = mutableSetOf<Int>()
        var complete = packages != null && packages.length() > 0
        for (i in 0 until (packages?.length() ?: 0)) {
            val pkg = packages?.optJSONObject(i)
            val ids = pkg?.optJSONArray("depot_ids")
            if (pkg?.optBoolean("pics_fetched") != true || ids == null) {
                complete = false
                continue
            }
            for (j in 0 until ids.length()) {
                ids.optInt(j).takeIf { it > 0 }?.let { ownedIds.add(it) }
            }
        }
        val selected = depots.filter { it.id in ownedIds }
        val estimate = when {
            depots.size == 1 -> depots
            complete && selected.isNotEmpty() -> selected
            else -> return null // No reliable selection yet; never sum alternative variants.
        }
        return estimate.takeIf { it.all { depot -> depot.size > 0 } }?.sumOf { it.size }
    }

    private fun dlcAppId(depot: JSONObject): Int =
        depot.optInt("dlcappid").takeIf { it > 0 } ?: (depot.optJSONObject("config")?.optInt("dlcappid") ?: 0)

    internal fun dlcDepotApps(info: JSONObject): Map<Int, Int> {
        val depots = info.optJSONObject("depots") ?: return emptyMap()
        return depots.keys().asSequence().mapNotNull { key ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            val entry = depots.optJSONObject(key) ?: return@mapNotNull null
            dlcAppId(entry).takeIf { it > 0 }?.let { id to it }
        }.toMap()
    }

    internal fun dlcIds(info: JSONObject): Set<Int> =
        (info.optJSONObject("extended")?.optString("listofdlc").orEmpty()
            .split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { id -> id > 0 } } +
            dlcDepotApps(info).values).toSet()

    internal fun ownedAppIds(library: JSONObject): Set<Int> {
        val apps = library.optJSONArray("owned_apps") ?: return emptySet()
        return (0 until apps.length()).mapNotNull { apps.optJSONObject(it)?.optInt("id")?.takeIf { id -> id > 0 } }.toSet()
    }

    internal fun packageMetadataReady(library: JSONObject): Boolean {
        val packages = library.optJSONArray("packages") ?: return false
        return packages.length() > 0 && (0 until packages.length()).all {
            packages.optJSONObject(it)?.optBoolean("pics_fetched") == true
        }
    }

    internal fun selectedDlc(info: JSONObject, library: JSONObject, excluded: Set<Int>): Set<Int> =
        dlcDepotApps(info).values.toSet().intersect(ownedAppIds(library)) - excluded

    internal fun installedDlcIds(record: JSONObject): Set<Int> {
        val installed = record.optString("ludashiInstalledDepots").split(',')
            .mapNotNull { it.substringBefore(':').toIntOrNull() }.toSet()
        return dlcDepotApps(record).filterKeys { it in installed }.values.toSet()
    }

    // Unselected DLC files are retained; retain their manifest records as well.
    internal fun installedManifestList(previous: JSONObject, attempted: List<Depot>, installed: List<Depot>): String {
        val retainedDlcDepots = dlcDepotApps(previous).keys - attempted.map { it.id }.toSet()
        val retained = previous.optString("ludashiInstalledDepots").split(',')
            .filter { it.substringBefore(':').toIntOrNull() in retainedDlcDepots }
        return (installed.map { "${it.id}:${it.manifest.toULong()}:0" } + retained).joinToString(",")
    }

    private fun matchesInstall(depot: JSONObject, language: String, selectedDlc: Set<Int>): Boolean {
        val config = depot.optJSONObject("config")
        val os = config?.optString("oslist").orEmpty()
        val depotLanguage = config?.optString("language").orEmpty()
        return (os.isBlank() || os.split(',').any { it.trim().equals("windows", true) }) &&
            (depotLanguage.isBlank() || depotLanguage.equals(language, true)) &&
            (config?.optString("lowviolence") !in listOf("1", "true")) &&
            (dlcAppId(depot) == 0 || dlcAppId(depot) in selectedDlc)
    }

    private fun resolveDepot(
        id: Int,
        entry: JSONObject,
        branch: String,
        language: String,
        sourceInfo: ((Int) -> JSONObject)?,
        selectedDlc: Set<Int>,
    ): Depot? {
        var depot = entry
        var appId = 0
        val visited = mutableSetOf<Int>()
        while (matchesInstall(depot, language, selectedDlc)) {
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
    fun excludedDlc(context: Context, id: Int): Set<Int> =
        prefs(context).getStringSet("install-dlc-excluded-$id", emptySet()).orEmpty()
            .mapNotNull { it.toIntOrNull() }.toSet()
    fun setExcludedDlc(context: Context, id: Int, excluded: Set<Int>) {
        check(!SteamSession.downloading && !SteamSession.gameActive && SteamSession.installRequestId == 0)
        prefs(context).edit().putStringSet("install-dlc-excluded-$id", excluded.map { it.toString() }.toSet()).apply()
    }
    fun setBranch(context: Context, id: Int, value: String) {
        check(!SteamSession.downloading && !SteamSession.gameActive)
        prefs(context).edit().putString("install-branch-$id", value).apply()
    }
    fun setLanguage(context: Context, id: Int, value: String) {
        check(!SteamSession.downloading && !SteamSession.gameActive)
        prefs(context).edit().putString("install-language-$id", value).apply()
    }
}
