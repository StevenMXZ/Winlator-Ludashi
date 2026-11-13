package com.winlator.cmod.storage

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.winlator.cmod.SettingsFragment
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.ProtonPackageManager
import com.winlator.cmod.steam.SteamStorage
import com.winlator.cmod.xenvironment.ImageFs
import org.apache.commons.compress.archivers.tar.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.security.MessageDigest

internal object StorageBackup {
    private val privateRoots = setOf("contents", "opengl-bundled-v1", "gog-installed", "ludashi-stores/itch/installed", "ludashi-stores/itch/libraries")
    private val preferenceNames = listOf("steam-settings", "store-download-options", "contents_manager_prefs")
    internal fun publicRoot(context: Context): File {
        val uri = PreferenceManager.getDefaultSharedPreferences(context).getString("winlator_path_uri", null)
        return File(uri?.let { FileUtils.getFilePathFromUri(context, Uri.parse(it)) } ?: SettingsFragment.DEFAULT_WINLATOR_PATH).canonicalFile
    }
    private fun settings(context: Context): JSONObject {
        val names = preferenceNames + "${context.packageName}_preferences"
        return JSONObject().apply {
            names.forEach { name -> put(name, JSONArray().apply {
                context.getSharedPreferences(name, 0).all.forEach { (key, value) ->
                    if (key.startsWith("pending-") || key.contains("password", true) || key.contains("token", true)) return@forEach
                    val type = when (value) { is String -> "string"; is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is Set<*> -> "set"; else -> return@forEach }
                    put(JSONObject().put("key", key).put("type", type).put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
                }
            }) }
        }
    }
    private fun roots(context: Context, check: () -> Unit): List<Pair<JSONObject, File>> {
        val roots = mutableListOf<Pair<JSONObject, File>>()
        fun add(kind: String, path: String, file: File, container: Int = 0) {
            if (file.exists() && !Files.isSymbolicLink(file.toPath())) roots += JSONObject().put("kind", kind).put("path", path).put("container", container).put("original", file.canonicalPath) to file.canonicalFile
        }
        privateRoots.forEach { add("files", it, File(context.filesDir, it)) }
        File(context.filesDir, "steam-library").listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.forEach { add("steam-record", it.name, it) }
        add("public", "", publicRoot(context))
        ProtonPackageManager.getInstalledIdentifiers(context).forEach { add("proton", it, ProtonPackageManager.getInstallDir(context, it)) }
        val manager = ContainerManager(context)
        manager.containers.forEach { c ->
            add("saves", "", File(c.rootDir, ".wine/drive_c/users/${ImageFs.USER}"), c.id)
        }
        val snapshot = StorageRepository.scan(context, check)
        snapshot.entries.filter { it.target.category == StorageCategory.GAMES }.forEach { entry ->
            val file = entry.target.file.canonicalFile
            if (file.toPath().startsWith(publicRoot(context).toPath())) return@forEach
            val container = manager.containers.firstOrNull { file.toPath().startsWith(it.rootDir.canonicalFile.toPath()) }
            if (container != null) add("container-game", file.relativeTo(container.rootDir).invariantSeparatorsPath, file, container.id)
            else {
                add("steam-game", file.name, file)
                roots.last().first.put("appId", entry.target.id)
            }
        }
        return roots.distinctBy { it.second.path }
    }
    private inline fun <T> locked(context: Context, operation: () -> T): T {
        check(!StorageRepository.busy() && ContainerSessionGate.maintain()) { "Close games and finish downloads first" }
        try { return operation() } finally { StorageRepository.cached = null; ContainerSessionGate.maintained() }
    }
    fun backup(context: Context, uri: Uri, check: () -> Unit) = locked(context) {
        val roots = roots(context, check)
        val manifest = JSONObject().put("format", "ludashi-data").put("version", 1).put("roots", JSONArray(roots.map { it.first })).put("settings", settings(context))
        context.contentResolver.openFileDescriptor(uri, "w")?.use { descriptor ->
            val destination = runCatching { File(android.system.Os.readlink("/proc/self/fd/${descriptor.fd}")).canonicalFile }.getOrNull()
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).buffered().use { stream ->
                write(stream, roots, manifest, check, destination)
            }
        } ?: error("Cannot write the backup")
    }
    internal fun write(stream: OutputStream, roots: List<Pair<JSONObject, File>>, manifest: JSONObject, check: () -> Unit = {}, destination: File? = null) {
        TarArchiveOutputStream(stream).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
            tar.putArchiveEntry(TarArchiveEntry("ludashi.json").apply { size = bytes.size.toLong() }); tar.write(bytes); tar.closeArchiveEntry()
            val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes) }
            var count = 0
            roots.forEachIndexed { index, (descriptor, root) ->
                Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    fun excluded(path: Path): Boolean {
                        if (path.toFile().canonicalFile == destination) return true
                        val rel = root.toPath().relativize(path).toString().replace('\\', '/')
                        return descriptor.getString("kind") == "public" && (rel.substringBefore('/').lowercase() in setOf("containers", "logs", "cache", "backups") || path.fileName.toString().startsWith("Ludashi-backup-"))
                    }
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        check(); return if (excluded(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        check(); if (excluded(file)) return FileVisitResult.CONTINUE
                        val rel = root.toPath().relativize(file).toString().replace('\\', '/')
                        val name = "data/$index/${if (root.isFile) root.name else rel}"
                        val entry = if (attrs.isSymbolicLink) {
                            val link = Files.readSymbolicLink(file)
                            if (link.isAbsolute || !file.parent.resolve(link).normalize().startsWith(root.toPath())) return FileVisitResult.CONTINUE
                            TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = link.toString() }
                        } else {
                            if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                            TarArchiveEntry(name).apply { size = attrs.size(); mode = mode(file) }
                        }
                        header(digest, entry); count++
                        tar.putArchiveEntry(entry)
                        if (attrs.isRegularFile) Files.newInputStream(file).use { input -> copy(input, tar, check, digest) }
                        tar.closeArchiveEntry(); return FileVisitResult.CONTINUE
                    }
                })
            }
            val completed = JSONObject().put("count", count).put("sha256", hex(digest.digest())).toString().toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("complete.json").apply { size = completed.size.toLong() })
            tar.write(completed); tar.closeArchiveEntry()
        }
    }
    private fun mode(path: Path): Int = runCatching {
        val permissions = Files.getPosixFilePermissions(path)
        PosixFilePermissions.toString(permissions).foldIndexed(0) { i, value, c -> if (c != '-') value or (1 shl (8 - i)) else value }
    }.getOrDefault(420)
    private fun permissions(path: Path, mode: Int) {
        val text = "rwxrwxrwx".mapIndexed { i, c -> if (mode and (1 shl (8 - i)) != 0) c else '-' }.joinToString("")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(text))
    }
    internal fun member(root: Path, name: String): Path {
        require(name.isNotBlank() && !name.contains('\\') && !Paths.get(name).isAbsolute) { "Invalid backup path" }
        val path = root.resolve(name).normalize()
        require(path != root && path.startsWith(root)) { "Invalid backup path" }
        var parent: Path? = path
        while (parent != null && parent.startsWith(root)) {
            require(!Files.isSymbolicLink(parent)) { "Backup path crosses a symbolic link" }
            parent = parent.parent
        }
        return path
    }
    private fun destination(context: Context, root: JSONObject): File {
        val path = root.getString("path")
        fun below(base: File, value: String) = member(base.canonicalFile.toPath(), value).toFile()
        return when (root.getString("kind")) {
            "files" -> { require(path in privateRoots); below(context.filesDir, path) }
            "public" -> { require(path.isEmpty()); publicRoot(context) }
            "proton" -> { require(ProtonPackageManager.isKnownPackage(path)); ProtonPackageManager.getInstallDir(context, path) }
            "steam-record" -> { require(path.matches(Regex("[0-9]+\\.json"))); below(File(context.filesDir, "steam-library"), path) }
            "steam-game", "legacy-steam-game" -> {
                require(path.isNotBlank() && path !in setOf(".", "..") && '/' !in path && '\\' !in path)
                below(if (root.getString("kind") == "steam-game") SteamStorage.root(context) else File(context.filesDir, "steam-library/common"), path)
            }
            "saves", "shortcut", "container-game" -> {
                val c = ContainerManager(context).containers.firstOrNull { it.id == root.getInt("container") }
                    ?: error("Create container ${root.getInt("container")} before restoring its games and saves")
                when (root.getString("kind")) {
                    "saves" -> { require(path.isEmpty()); below(c.rootDir, ".wine/drive_c/users/${ImageFs.USER}") }
                    "shortcut" -> { require(path.endsWith(".desktop") || path.endsWith(".json")); require('/' !in path); below(c.desktopDir, path) }
                    else -> {
                        require(path.matches(Regex("\\.wine/drive_c/(GOG Games/Ludashi-[0-9]+|Ludashi Games/itch-[a-f0-9]{32})")))
                        below(c.rootDir, path)
                    }
                }
            }
            else -> error("Unsupported backup data")
        }.also { require(!Files.isSymbolicLink(it.toPath())) { "Backup destination is a symbolic link" } }
    }
    private fun header(digest: MessageDigest, entry: TarArchiveEntry) {
        digest.update("${entry.name}\u0000${entry.size}\u0000${entry.mode}\u0000${entry.linkName}\u0000".toByteArray(Charsets.UTF_8))
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun copy(input: InputStream, output: OutputStream, check: () -> Unit, digest: MessageDigest? = null) {
        val buffer = ByteArray(128 * 1024)
        while (true) { check(); val count = input.read(buffer); if (count < 0) return; output.write(buffer, 0, count); digest?.update(buffer, 0, count) }
    }
    internal fun read(input: InputStream, stage: File, modes: MutableMap<Path, Int>, links: MutableMap<Path, String>, validate: (JSONObject) -> Unit = {}, check: () -> Unit = {}): JSONObject {
        return TarArchiveInputStream(input).use { tar ->
            val first = tar.nextTarEntry ?: error("Empty backup")
            require(first.name == "ludashi.json" && first.isFile && first.size in 1..8_388_608) { "This is not a Ludashi backup" }
            val metadata = tar.readBytes()
            val manifest = JSONObject(metadata.toString(Charsets.UTF_8))
            require(manifest.getString("format") == "ludashi-data" && manifest.getInt("version") == 1)
            val definitions = manifest.getJSONArray("roots")
            (0 until definitions.length()).forEach { validate(definitions.getJSONObject(it)) }
            val seen = mutableSetOf<Path>()
            val digest = MessageDigest.getInstance("SHA-256").apply { update(metadata) }
            var complete = false
            while (true) {
                check(); val entry = tar.nextTarEntry ?: break
                require(!complete) { "Unexpected data after the backup footer" }
                if (entry.name == "complete.json") {
                    require(entry.isFile && entry.size in 1..4096)
                    val footer = JSONObject(tar.readBytes().toString(Charsets.UTF_8))
                    require(footer.getInt("count") == seen.size && footer.getString("sha256") == hex(digest.digest())) { "Backup integrity check failed" }
                    complete = true; continue
                }
                header(digest, entry)
                val parts = entry.name.split('/', limit = 3)
                require(parts.size == 3 && parts[0] == "data") { "Invalid backup entry" }
                val index = parts[1].toIntOrNull() ?: error("Invalid backup root")
                require(index in 0 until definitions.length())
                val target = member(stage.resolve(index.toString()).toPath(), parts[2])
                require(seen.add(target)) { "Duplicate backup entry" }
                if (entry.isSymbolicLink) {
                    val link = Paths.get(entry.linkName)
                    require(!link.isAbsolute && target.parent.resolve(link).normalize().startsWith(stage.resolve(index.toString()).toPath())) { "Unsafe backup link" }
                    links[target] = entry.linkName
                } else {
                    require(entry.isFile && entry.size >= 0 && entry.size <= stage.usableSpace - 16 * 1024 * 1024) { "Unsupported entry or insufficient storage" }
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { copy(tar, it, check, digest) }
                    require(Files.size(target) == entry.size) { "Incomplete backup" }
                    modes[target] = entry.mode and 511
                }
            }
            require(complete) { "Incomplete backup" }
            manifest
        }
    }
    private fun applySettings(context: Context, prefs: JSONObject, commit: Boolean) {
        val editors = mutableListOf<android.content.SharedPreferences.Editor>()
        val allowed = preferenceNames + "${context.packageName}_preferences"
        prefs.keys().forEach { name ->
            require(name in allowed)
            val current = context.getSharedPreferences(name, 0)
            val edit = current.edit()
            current.all.keys.filterNot { it.startsWith("pending-") || it.contains("password", true) || it.contains("token", true) }.forEach(edit::remove)
            val rows = prefs.getJSONArray(name)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val key = row.getString("key")
                require(!key.contains("password", true) && !key.contains("token", true))
                when (row.getString("type")) {
                    "string" -> edit.putString(key, row.getString("value")); "boolean" -> edit.putBoolean(key, row.getBoolean("value"))
                    "int" -> edit.putInt(key, row.getInt("value")); "long" -> edit.putLong(key, row.getLong("value")); "float" -> edit.putFloat(key, row.getDouble("value").toFloat())
                    "set" -> edit.putStringSet(key, row.getJSONArray("value").let { values -> (0 until values.length()).map { values.getString(it) }.toSet() })
                    else -> error("Invalid backup setting")
                }
            }
            editors.add(edit)
        }
        if (commit) editors.forEach { check(it.commit()) { "Could not restore settings" } }
    }
    fun restore(context: Context, uri: Uri, check: () -> Unit) = locked(context) {
        val stage = File(context.cacheDir, "storage-restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val previousSettings = settings(context)
        val changes = mutableListOf<Pair<Path, Path?>>()
        val modes = mutableMapOf<Path, Int>()
        val links = mutableMapOf<Path, String>()
        try {
            val manifest = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                read(input, stage, modes, links, { destination(context, it) }, check)
            } ?: error("Cannot read the backup")
            applySettings(context, manifest.getJSONObject("settings"), false)
            val definitions = manifest.getJSONArray("roots")
            val destinations = (0 until definitions.length()).map { destination(context, definitions.getJSONObject(it)) }
            for (i in 0 until definitions.length()) {
                val definition = definitions.getJSONObject(i)
                if (definition.getString("kind") != "steam-record") continue
                val recordFile = stage.resolve("$i/${definition.getString("path")}")
                if (!recordFile.isFile) continue
                val record = JSONObject(recordFile.readText())
                val appId = definition.getString("path").removeSuffix(".json")
                val game = (0 until definitions.length()).firstOrNull { definitions.getJSONObject(it).optString("appId") == appId }
                val previous = record.optString("ludashiInstallPath")
                val public = (0 until definitions.length()).firstOrNull { definitions.getJSONObject(it).getString("kind") == "public" }
                val directory = if (game != null) destinations[game] else if (public != null && previous.isNotBlank()) {
                    val oldRoot = Paths.get(definitions.getJSONObject(public).getString("original"))
                    val oldPath = Paths.get(previous).normalize()
                    if (oldPath != oldRoot && oldPath.startsWith(oldRoot)) member(destinations[public].toPath(), oldRoot.relativize(oldPath).toString()).toFile() else null
                } else null
                if (directory != null) recordFile.writeText(record.put("ludashiInstallPath", directory.path).put("ludashiInstallRoot", directory.parent).toString())
            }
            fun install(source: Path, link: String? = null) {
                check()
                val relative = stage.toPath().relativize(source)
                val index = relative.getName(0).toString().toInt()
                val base = destinations[index]
                val rootFile = definitions.getJSONObject(index).getString("kind") in setOf("steam-record", "shortcut")
                val target = if (rootFile) {
                    require(link == null) { "Metadata cannot be a symbolic link" }
                    require(relative.nameCount == 2 && relative.fileName.toString() == base.name)
                    member(base.parentFile.canonicalFile.toPath(), base.name)
                } else member(base.canonicalFile.toPath(), relative.subpath(1, relative.nameCount).toString())
                Files.createDirectories(target.parent)
                require(!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) { "Cannot overwrite a directory" }
                val previous = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) target.resolveSibling(".ludashi-old-${UUID.randomUUID()}").also { Files.move(target, it) } else null
                changes += target to previous
                if (link != null) Files.createSymbolicLink(target, Paths.get(link))
                else {
                    val pending = target.resolveSibling(".ludashi-restore-${UUID.randomUUID()}")
                    try {
                        Files.newInputStream(source).use { input -> Files.newOutputStream(pending).use { copy(input, it, check) } }
                        if (target.startsWith(context.filesDir.canonicalFile.toPath())) permissions(pending, modes.getValue(source))
                        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(pending) }
                }
            }
            modes.keys.forEach { install(it) }
            links.forEach { (path, value) -> install(path, value) }
            links.keys.forEach { source ->
                val relative = stage.toPath().relativize(source)
                val base = destinations[relative.getName(0).toString().toInt()].canonicalFile.toPath()
                val installed = base.resolve(relative.subpath(1, relative.nameCount)).toFile().canonicalFile.toPath()
                require(installed.startsWith(base)) { "Backup link escapes its destination" }
            }
            applySettings(context, manifest.getJSONObject("settings"), true)
            changes.forEach { it.second?.let { old -> runCatching { Files.deleteIfExists(old) } } }
        } catch (failure: Throwable) {
            runCatching { applySettings(context, previousSettings, true) }
            changes.asReversed().forEach { (target, previous) -> runCatching { Files.deleteIfExists(target); if (previous != null) Files.move(previous, target) } }
            throw failure
        } finally { stage.deleteRecursively() }
    }
}
