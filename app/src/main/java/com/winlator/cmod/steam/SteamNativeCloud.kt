package com.winlator.cmod.steam

import android.content.Context
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.feature.stores.steam.ludashisteam.LudashiSteamSession
import com.winlator.cmod.xenvironment.ImageFs
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Explicit native Cloud operation. No periodic job, Wine process or gameplay counters. */
object SteamNativeCloud {
    @Volatile var busy = false
        private set
    private const val MAX_FILE = 64L * 1024 * 1024
    data class Entry(val name: String, val local: File, val remoteSha: String?, val localSha: String?, val size: Long = 0, val time: Long = 0)
    data class Plan(val id: Int, val user: Long, val container: Container, val change: Long, val entries: List<Entry>) {
        val conflicts get() = entries.count { it.remoteSha != null && it.localSha != null && it.remoteSha != it.localSha }
    }
    enum class Choice { Cloud, Local }
    enum class Direction { Download, Upload }
    private fun sha(file: File): String {
        require(file.length() <= MAX_FILE) { "Save exceeds 64 MiB" }
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    internal fun resolve(raw: String, roots: Map<String, File>, user: Long): File {
        var path = raw.replace('\\', '/').replace("{64BitSteamID}", user.toString()).replace("{Steam3AccountID}", (user and 0xffffffffL).toString())
        require(path.isNotBlank() && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) && path.split('/').none { it == ".." }) { "Unsafe Steam Cloud path" }
        if (path.startsWith("ROOT_MOD", true)) path = "%Root%/" + path.substring(8).trimStart('/')
        var root = roots.getValue("steamuserdata")
        if (path.startsWith('%')) {
            val end = path.indexOf('%', 1); require(end > 1) { "Invalid Steam Cloud root" }
            val key = path.substring(1, end).lowercase()
            root = requireNotNull(roots[key]) { "Unsupported Steam Cloud root: $key" }
            path = path.substring(end+1).trimStart('/')
        } else if (path.startsWith("remote/", true)) path = path.substring(7)
        require(!path.contains('%') && !path.contains('{') && !path.contains('}') && path.isNotBlank()) { "Unresolved Steam Cloud path" }
        val base = root.canonicalFile
        return File(base, path).canonicalFile.also { require(it != base && it.toPath().startsWith(base.toPath())) { "Steam Cloud path escapes its save folder" } }
    }
    private fun roots(context: Context, id: Int, container: Container, user: Long): Map<String, File> {
        val drive = File(container.rootDir, ".wine/drive_c")
        val profile = File(drive, "users/${ImageFs.USER}")
        return mapOf("gameinstall" to SteamGames.directory(context, id),
            "steamuserdata" to File(drive, "Program Files (x86)/Steam/userdata/${user and 0xffffffffL}/$id/remote"),
            "steamuserbasestorage" to File(drive, "Program Files (x86)/Steam/userdata/${user and 0xffffffffL}/$id/remote"),
            "winmydocuments" to File(profile, "Documents"), "steamclouddocuments" to File(profile, "Documents"),
            "winappdatalocal" to File(profile, "AppData/Local"), "winappdatalocallow" to File(profile, "AppData/LocalLow"),
            "winappdataroaming" to File(profile, "AppData/Roaming"), "winsavedgames" to File(profile, "Saved Games"),
            "winprogramdata" to File(drive, "ProgramData"), "root" to profile, "windowshome" to profile)
    }
    private fun remoteName(list: JSONObject, item: JSONObject): String {
        val prefixes = list.optJSONArray("pathPrefixes") ?: JSONArray()
        val index = item.optInt("pathPrefixIndex", -1)
        val prefix = if (index in 0 until prefixes.length()) prefixes.getString(index).replace('\\', '/').trimEnd('/') else ""
        val name = item.getString("fileName").replace('\\', '/')
        return if (name.startsWith('%') || prefix.isBlank()) name else "$prefix/$name"
    }
    private fun listing(session: LudashiSteamSession, id: Int): JSONObject = JSONObject(session.getCloudFileList(id) ?: error("Steam Cloud request timed out")).also { require(it.has("files") && it.has("currentChangeNumber")) { "Invalid Steam Cloud response" } }
    fun preview(context: Context, id: Int, container: Container): Plan = operation(context, container) { session, user ->
        val listing = listing(session, id)
        val roots = roots(context, id, container, user)
        val entries = linkedMapOf<String, Entry>()
        val localPaths = mutableSetOf<String>()
        val files = listing.getJSONArray("files")
        require(files.length() <= 5000) { "Cloud library too large for one sync" }
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            if (item.optInt("persistState") != 0) continue // Preserve deletions; never delete saves implicitly.
            val name = remoteName(listing, item)
            val target = resolve(name, roots, user)
            require(localPaths.add(target.path)) { "Conflicting Cloud variants map to the same local file; choose a variant in Steam first" }
            val hash = item.getString("sha").lowercase()
            require(hash.matches(Regex("[a-f0-9]{40}"))) { "Invalid Cloud checksum" }
            require(item.getLong("size") in 0..MAX_FILE) { "Cloud file exceeds 64 MiB" }
            require(!target.exists() || target.isFile && target.length() <= MAX_FILE) { "Local save exceeds supported size" }
            entries[name] = Entry(name, target, hash, if (target.isFile) sha(target) else null, item.getLong("size"), item.optLong("timestamp"))
        }
        // Discover NEW local saves only from the game's explicit Windows UFS patterns.
        val patterns = JSONObject(SteamGames.info(context, id).optJSONObject("ufs")?.optJSONObject("savefiles")?.toString() ?: "{}")
        patterns.put("ludashi-remote", JSONObject().put("root", "SteamUserData").put("path", "").put("pattern", "*").put("recursive", "1"))
        if (patterns != null) for (key in patterns.keys()) {
            val pattern = patterns.optJSONObject(key) ?: continue
            val root = pattern.optString("root").trim('%').lowercase()
            if (root !in roots) continue
            val path = pattern.optString("path").trim('/', '\\')
            val token = (if (root == "steamuserdata") "" else "%${pattern.getString("root").trim('%')}%/") + if (path.isBlank()) "" else "$path/"
            val folder = resolve(token + ".ludashi-path-probe", roots, user).parentFile!!
            if (!folder.isDirectory) continue
            val wildcard = pattern.optString("pattern", "*")
            require(!wildcard.contains('/') && !wildcard.contains('\\')) { "Unsupported UFS filename pattern" }
            val regex = Regex(wildcard.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
            val recursive = pattern.optString("recursive", "1") != "0"
            val candidates = if (recursive) folder.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.asSequence() else folder.listFiles().orEmpty().asSequence()
            var count = 0
            for (file in candidates) {
                require(++count <= 10000) { "UFS pattern scans too many files" }
                if (!file.isFile || !regex.matches(file.name) || file.name.equals("steam_autocloud.vdf", true) || file.name.endsWith(".ludashi-cloud-tmp")) continue
                val name = token + file.relativeTo(folder).invariantSeparatorsPath
                val target = resolve(name, roots, user)
                if (target.path in localPaths) continue
                require(file.length() <= MAX_FILE && entries.size < 5000)
                localPaths.add(target.path)
                entries[name] = Entry(name, target, null, sha(file))
            }
        }
        Plan(id, user, container, listing.getLong("currentChangeNumber"), entries.values.toList())
    }
    fun synchronize(context: Context, plan: Plan, direction: Direction, progress: (Int, Int) -> Unit): String = operation(context, plan.container) { session, user ->
        check(user == plan.user) { "Steam account changed; preview again" }
        val now = listing(session, plan.id)
        check(now.getLong("currentChangeNumber") == plan.change) { "Steam Cloud changed; preview again" }
        plan.entries.forEach { e -> check((if (e.local.isFile) sha(e.local) else null) == e.localSha) { "Local saves changed; preview again" } }
        val downloads = if (direction == Direction.Download) plan.entries.filter { it.remoteSha != null && it.remoteSha != it.localSha } else emptyList()
        val uploads = if (direction == Direction.Upload) plan.entries.filter { it.localSha != null && it.localSha != it.remoteSha } else emptyList()
        val deadline = android.os.SystemClock.elapsedRealtime() + 300000
        val backup = File(context.filesDir, "steam-cloud-backups/$user/${plan.id}/${System.currentTimeMillis()}").apply { mkdirs() }
        val staged = mutableMapOf<Entry, File>()
        var done = 0; val total = downloads.size + uploads.size
        progress(0, total)
        if (total == 0) return@operation "Already up to date"
        for ((index, entry) in downloads.withIndex()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Sync exceeded five minutes; retry remaining files" }
            val bytes = session.downloadCloudFile(plan.id, entry.name) ?: error("Cloud download failed; originals preserved")
            check(bytes.size.toLong() == entry.size && MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == entry.remoteSha) { "Cloud checksum mismatch; originals preserved" }
            val file = File(backup, "$index.download").apply { writeBytes(bytes) }; staged[entry] = file
            progress(++done, total)
        }
        // All downloads are validated before replacing any save. Retain the previous copies and a manifest.
        val manifest = JSONArray()
        for ((index, entry) in downloads.withIndex()) {
            if (entry.local.isFile) entry.local.copyTo(File(backup, "$index.original"))
            manifest.put(JSONObject().put("path", entry.local.path).put("backup", "$index.original"))
        }
        File(backup, "manifest.json").writeText(manifest.toString())
        for ((entry, file) in staged) {
            check((if (entry.local.isFile) sha(entry.local) else null) == entry.localSha) { "Local save changed during transfer; originals retained" }
            entry.local.parentFile?.mkdirs()
            val tmp = File(entry.local.parentFile, entry.local.name+".ludashi-cloud-tmp")
            try {
                file.copyTo(tmp, true)
                Files.move(tmp.toPath(), entry.local.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { tmp.delete() }
            if (entry.time > 0) entry.local.setLastModified(entry.time*1000)
        }
        if (uploads.isNotEmpty()) {
            val prefs = context.getSharedPreferences("steam-settings", 0)
            val client = prefs.getLong("native-cloud-client", 0).takeIf { it != 0L } ?: (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).also { prefs.edit().putLong("native-cloud-client", it).commit() }
            check(listing(session, plan.id).getLong("currentChangeNumber") == plan.change) { "Cloud changed during transfer; review the new conflict before uploading" }
            val batch = session.beginCloudUploadBatch(plan.id, uploads.map { it.name }, emptyList(), client) ?: error("Steam refused Cloud upload; local saves kept")
            var success = false
            try {
                for (entry in uploads) {
                    check(android.os.SystemClock.elapsedRealtime() < deadline) { "Sync exceeded five minutes" }
                    check(sha(entry.local) == entry.localSha) { "Save changed during sync" }
                    check(session.uploadCloudFile(plan.id, entry.name, entry.local.readBytes(), entry.localSha!!, entry.local.lastModified()/1000, batch.batchId)) { "Cloud upload failed; local saves kept" }
                    progress(++done, total)
                }
                success = true
            } finally { check(session.completeCloudUploadBatch(plan.id, batch.batchId, if (success) 1 else 2)) { "Steam did not confirm the upload batch" } }
            val confirmed = listing(session, plan.id)
            val finalHashes = mutableMapOf<String, String>()
            val a = confirmed.getJSONArray("files")
            for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optInt("persistState") == 0) finalHashes[remoteName(confirmed, it)] = it.optString("sha") }
            check(uploads.all { finalHashes[it.name]?.equals(it.localSha, true) == true }) { "Steam has not confirmed all files; local saves kept" }
        }
        // Native completion is recorded only after transfers and upload verification succeed.
        val report = File(context.filesDir, "steam-cloud/$user/${plan.container.id}-${plan.id}.txt")
        report.parentFile?.mkdirs(); report.writeText("appid=${plan.id}\nphase=${if (direction == Direction.Download) "launch" else "exit"}\nstate=2\ntransfers=$total\n")
        progress(total, total)
        backup.path
    }
    @Synchronized private fun <T> operation(context: Context, container: Container, block: (LudashiSteamSession, Long) -> T): T {
        check(!busy && !SteamSession.gameActive && !SteamSession.downloading) { "Close games and pause downloads before Cloud sync" }
        check(SteamSettings.enabled(context) && SteamSession.signedIn) { "Connect to Steam first" }
        busy = true
        val future = CompletableFuture<T>()
        SteamSession.work {
            var lease = false
            try {
                check(!SteamSession.gameActive && SteamSession.signedIn)
                check(ContainerSessionGate.edit(container.id)) { "Close this container before syncing saves" }; lease = true
                val user = SteamCredentials.load(context)?.getLong("id") ?: error("Sign in first")
                future.complete(block(SteamSession.session ?: error("Steam disconnected"), user))
            } catch (error: Throwable) { future.completeExceptionally(error) }
            finally { if (lease) ContainerSessionGate.edited(container.id); busy = false }
        }
        // Completion belongs to the worker, including after UI dismissal; do not release the gate early.
        return try { future.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
    }
}
