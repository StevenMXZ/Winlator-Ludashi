package com.winlator.cmod.components

import android.content.Context
import android.util.Base64
import android.util.Log
import com.winlator.cmod.components.offline.OfflineTools
import com.winlator.cmod.components.offline.PackageInstaller
import com.winlator.cmod.components.offline.PrefixPlacer
import com.winlator.cmod.components.offline.RegValue
import com.winlator.cmod.components.offline.WineRegistryFile
import com.winlator.cmod.container.Container
import com.winlator.cmod.components.ComponentDownloads as Downloader
import com.winlator.cmod.core.TarCompressorUtils
import com.winlator.cmod.core.unpack.Innoextract
import com.winlator.cmod.core.unpack.SevenZip
import com.winlator.cmod.xenvironment.ImageFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream


object OfflineComponentInstaller {
    private const val TAG = "OfflineComponents"
    private const val DLL_OVERRIDES = "Software\\Wine\\DllOverrides"
    private const val INSTALLS_PREFS = "component_installs"

    private val FILE_STEPS = setOf("download_archive", "archive_extract", "copy_dll", "copy_file", "override_dll", "register_dll")
    private val INSTALLER_STEPS = setOf("install_exe", "install_msi")
    /** Done here without any installer running: a Windows version, a registry value, files out of a cabinet, "uninstall Wine Mono". */
    private val OFFLINE_STEPS = setOf("set_windows", "set_register_key", "get_from_cab", "uninstall")
    /** Installers that are their own setup program (NSIS, InnoSetup, IExpress): nothing inside is a package to lay out. */
    private val RUNS_OWN_SETUP = setOf(
        "K-Lite", "ffdshow", "lavfilters702", "lavfilters741", "quicktime72", "dirac", "webview2", "aairruntime",
        "gfw", "ie8_kb2936068", "art2k7min", "vcredist6sp6", "VulkanRT", "jet40", "mdac28", "oalinst",
        "dotnet20", "dotnet20sp1", "dotnet35", "dotnet35sp1",
    )
    /** The steps a recording makes redundant: everything that places files or edits the registry. */
    private val SNAPSHOT_COVERS = INSTALLER_STEPS + setOf("download_archive", "archive_extract", "copy_dll", "copy_file") + OFFLINE_STEPS
    /** The keys the catalog nests under "environment" on a few .NET entries. */
    private val NESTED_KEYS = setOf("url", "file_name", "file_checksum", "file_size")
    private val STEAMUSER = Regex("""(?i)(C:\\users\\)steamuser\b""")

    private fun ComponentStep.field(key: String): String = str(key).ifEmpty {
        if (key in NESTED_KEYS) obj.optJSONObject("environment")?.optString(key, "").orEmpty() else ""
    }

    fun hasSnapshot(c: Component): Boolean = c.snapshot.startsWith("https://github.com/") && c.snapshot.endsWith(".snapshot.json")

    private fun isPackage(step: ComponentStep): Boolean = step.action in INSTALLER_STEPS && step.field("url").startsWith("https://") &&
        (step.action == "install_msi" || listOf(step.field("file_name"), step.field("url").substringBefore('?'))
            .any { it.endsWith(".msi", ignoreCase = true) || it.endsWith(".exe", ignoreCase = true) })

    private fun fileStepOk(step: ComponentStep): Boolean = when (step.action) {
        "download_archive", "archive_extract" -> step.str("url").let { url ->
            !url.startsWith("http") || url.startsWith("https://") &&
                url.substringBefore('?').lowercase().let { n ->
                    listOf(".tar.xz", ".txz", ".tar.zst", ".zip", ".exe", ".cab").any { n.endsWith(it) }
                }
        }
        "copy_dll", "copy_file" -> ".." !in step.str("dest")
        else -> true
    }

    private fun offlineStep(step: ComponentStep): Boolean = step.action == "delete_dlls" || step.action in OFFLINE_STEPS ||
        step.action in FILE_STEPS && fileStepOk(step)

    /** Whether [c] itself can be laid out here, its dependencies aside. */
    private fun ownOffline(c: Component): Boolean {
        // A component with no steps of its own is a bundle of others (directmusic, directshow).
        if (!c.ready || c.steps.isEmpty() && !hasSnapshot(c) && c.dependencies.isEmpty()) return false
        if (hasSnapshot(c)) return true
        val installers = c.steps.filter { it.action in INSTALLER_STEPS }
        return if (installers.isNotEmpty()) {
            c.name !in RUNS_OWN_SETUP && installers.all { isPackage(it) } && c.steps.all { it in installers || offlineStep(it) }
        } else {
            c.steps.all { offlineStep(it) }
        }
    }

    /** True when [c] and every component it bundles can be installed without opening the container. */
    fun canInstall(c: Component, all: Map<String, Component> = ComponentCatalog.byName, depth: Int = 0): Boolean {
        if (depth > 8 || !ownOffline(c)) return false
        return c.dependencies.all { d -> all[d]?.let { canInstall(it, all, depth + 1) } ?: false }
    }

    // ---- installing --------------------------------------------------------------------------

    private class SevenZipTools(private val context: Context) : OfflineTools() {
        override fun extract(archive: File, dest: File): Boolean = try {
            dest.mkdirs()
            val proc = SevenZip.newProcess(context, "x", "-y", "-bd", "-bso0", "-bsp0", "-o${dest.absolutePath}", archive.absolutePath)
                .redirectErrorStream(true).start()
            drain(proc)
            proc.waitFor() <= 1
        } catch (e: Exception) {
            Log.w(TAG, "7-Zip x ${archive.name}", e); false
        }

        override fun canOpen(archive: File): Boolean = try {
            val proc = SevenZip.newProcess(context, "l", "-ba", archive.absolutePath).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.errorStream.close()
            proc.waitFor() == 0 && out.isNotBlank()
        } catch (e: Exception) {
            false
        }

        private fun drain(proc: Process) {
            val buf = ByteArray(1 shl 14)
            proc.inputStream.use { while (it.read(buf) >= 0) Unit }
        }
    }

    private class InnoTools(private val context: Context) : OfflineTools() {
        override fun extract(archive: File, dest: File): Boolean =
            Innoextract.isAvailable(context) && Innoextract.extract(context, archive, dest, {}, {}).exitCode == 0

        override fun canOpen(archive: File): Boolean = false
    }

    /** Where one component is put together before it goes into the prefix. */
    private class Stage(val root: File) {
        val drive = File(root, "drive_c")
        val registry = ArrayList<RegValue>()
        val overrides = LinkedHashMap<String, String>()
        var windowsVersion: String? = null
        val notes = ArrayList<String>()
    }

    /**
     * Installs [c] and the components it bundles that this container does not have yet into
     * [container]'s prefix. [onProgress] gets 0..1, [onStage] a line for what is happening.
     * Returns null on success, else why it failed.
     */
    fun install(
        context: Context, container: Container, c: Component,
        all: Map<String, Component> = ComponentCatalog.byName,
        onProgress: (Float) -> Unit = {}, onStage: (String) -> Unit = {},
    ): String? {
        val wine = File(container.rootDir, ".wine")
        if (!wine.isDirectory) return "Container has no Wine prefix yet — launch it once first."
        if (!canInstall(c, all)) return "${c.name} can't be installed without opening the container"
        if (!SevenZip.isAvailable(context)) return "The bundled 7-Zip is missing"
        val prefs = context.getSharedPreferences(INSTALLS_PREFS, Context.MODE_PRIVATE)
        val key = "c${container.id}"
        val have = prefs.getStringSet(key, emptySet()).orEmpty()
        val order = ArrayList<Component>()
        fun visit(x: Component, depth: Int) {
            if (depth > 8 || order.any { it.name == x.name }) return
            x.dependencies.mapNotNull { all[it] }.forEach { visit(it, depth + 1) }
            if (x.name == c.name || x.name !in have) order.add(x)
        }
        visit(c, 0)
        // Same filesystem as the prefix, so placing a file is a rename, not a second copy.
        val work = File(container.rootDir, ".ludashi-components").apply { OfflineTools.deleteTree(this); mkdirs() }
        val archives = HashMap<String, File>()
        return try {
            order.forEachIndexed { i, x ->
                val lo = i.toFloat() / order.size
                val hi = (i + 1).toFloat() / order.size
                val step: (Float) -> Unit = { f -> if (f >= 0) onProgress(lo + (hi - lo) * f.coerceIn(0f, 1f)) }
                onStage(if (order.size > 1) "${x.name} (${i + 1} of ${order.size})" else x.name)
                installOne(context, wine, x, work, archives, step) { s -> onStage("${x.name}: $s") }?.let { return "${x.name}: $it" }
                if (x.name != c.name) {
                    val now = prefs.getStringSet(key, emptySet()).orEmpty()
                    prefs.edit().putStringSet(key, now + x.name).apply()
                }
            }
            onProgress(1f)
            null
        } catch (e: Exception) {
            Log.e(TAG, "install ${c.name}", e)
            e.message ?: e.javaClass.simpleName
        } finally {
            OfflineTools.deleteTree(work)
        }
    }

    private fun installOne(
        context: Context, wine: File, c: Component, work: File, archives: HashMap<String, File>,
        onProgress: (Float) -> Unit, onStage: (String) -> Unit,
    ): String? {
        val stage = Stage(File(work, "stage-${c.name}").apply { OfflineTools.deleteTree(this); mkdirs() })
        val tools = SevenZipTools(context)
        val engine = PackageInstaller(tools, ImageFs.USER)
        val temp = File(work, "temp-${c.name}").apply { OfflineTools.deleteTree(this); mkdirs() }
        // Archives the catalog opened, by the name a later get_from_cab step calls them.
        val named = HashMap<String, File>()
        val cabs = HashMap<String, File>()
        var source: File? = null
        val steps = if (hasSnapshot(c)) c.steps.filter { it.action !in SNAPSHOT_COVERS } else c.steps
        val units = (steps.size + if (hasSnapshot(c)) 3 else 0).coerceAtLeast(1)
        var done = 0
        // A step reports 0..1 of its own share; a recording 0..3 (download, files, registry).
        fun part(f: Float) = onProgress(((done + f.coerceAtLeast(0f)) / units * 0.9f).coerceAtMost(0.9f))

        if (hasSnapshot(c)) {
            replaySnapshot(context, c, stage, work, tools, { part(it) }, onStage)?.let { return it }
            done += 3
        }
        for (step in steps) {
            part(0f)
            when (step.action) {
                "install_msi", "install_exe" -> {
                    val url = step.field("url")
                    val name = url.substringBefore('?').substringAfterLast('/')
                    val ext = if (listOf(step.field("file_name"), name).any { it.endsWith(".exe", true) }) "exe" else "msi"
                    val file = File(work, "${c.name}-${c.steps.indexOf(step)}.$ext")
                    onStage("downloading $name")
                    if (!Downloader.downloadFile(url, file) { f -> if (f >= 0) part(f * 0.5f) }) return "download failed: $name"
                    checksumNote(step, file, stage)
                    onStage("installing $name")
                    try {
                        val result = engine.install(file, stage.root, work) { f, text ->
                            if (f >= 0) part(0.5f + f.toFloat() * 0.5f)
                            if (text.isNotEmpty()) onStage(text)
                        }
                        stage.registry += result.registry
                        stage.notes += result.notes
                        Log.i(TAG, "${c.name}: ${result.how} ${result.packages} files=${result.files} registry=${result.registryCount}")
                    } finally {
                        file.delete()
                    }
                }
                "download_archive", "archive_extract" -> {
                    val url = step.str("url")
                    if (url.startsWith("http")) {
                        source = archives[url] ?: run {
                            val name = url.substringBefore('?').substringAfterLast('/')
                            val file = File(work, "a${archives.size}-$name")
                            onStage("downloading $name")
                            if (!Downloader.downloadFile(url, file) { f -> if (f >= 0) part(f * 0.7f) }) return "download failed: $name"
                            checksumNote(step, file, stage)
                            onStage("unpacking $name")
                            val dir = File(work, "a${archives.size}-x").apply { mkdirs() }
                            if (!openArchive(engine, file, dir)) return "could not unpack $name"
                            file.delete()
                            archives[url] = dir
                            dir
                        }
                        named[step.str("file_name").ifEmpty { url.substringBefore('?').substringAfterLast('/') }] = source!!
                    }
                }
                // Files picked out of a cabinet: one a catalog step downloaded (DirectX's redistributable),
                // or cabinets an earlier pick put under temp/.
                "get_from_cab" -> {
                    val pattern = step.str("source")
                    val opened = named[pattern]?.let { listOf(it) }
                    val containers: List<File> = opened ?: run {
                        val folder = File(temp, pattern.substringBeforeLast('/', "").replace('\\', '/'))
                        val rx = globRegex(pattern.substringAfterLast('/'))
                        folder.listFiles { f -> f.isFile && rx.matches(f.name) }?.sortedBy { it.name }.orEmpty()
                    }
                    if (containers.isEmpty()) return "nothing is called $pattern"
                    val dest = step.str("dest").replace('\\', '/')
                    for (container in containers) {
                        val unpacked = if (container.isDirectory) container else cabs.getOrPut(container.path) {
                            File(work, "cab${cabs.size}").also { dir ->
                                dir.mkdirs()
                                if (!openArchive(engine, container, dir)) return "could not open ${container.name}"
                            }
                        }
                        if (dest.startsWith("temp/", ignoreCase = true)) {
                            copyMatching(unpacked, step.str("file_name"), File(temp, dest.substring(5).trimEnd('/')), null)
                        } else {
                            copyToSystem(stage, unpacked, step.str("file_name"), dest)
                        }
                    }
                }
                "copy_dll", "copy_file" -> {
                    val from = source ?: return "a copy step before any archive"
                    copyToSystem(stage, from, step.str("file_name"), step.str("dest"))
                }
                "override_dll" -> {
                    fun add(dll: String, type: String) {
                        if (dll.isNotEmpty()) stage.overrides[dll] = type.ifEmpty { "native,builtin" }
                    }
                    add(step.str("dll"), step.str("type"))
                    step.bundle()?.let { b -> for (i in 0 until b.length()) b.getJSONObject(i).let { add(it.optString("value"), it.optString("data")) } }
                }
                "set_windows" -> step.str("version").takeIf { it.isNotEmpty() }?.let { stage.windowsVersion = it }
                "set_register_key" -> registryStep(step)?.let { stage.registry += it } ?: return "a registry value this can't write: ${step.str("key")}"
                // The DLLs register_dll names are ones Wine ships and has registered at the same path, so
                // the native file now there is the one the registration loads. "Uninstall Wine Mono" is the
                // mscoree override beside it, and the earlier Mono's files going as the new one is placed
                // (clearOldMono). delete_dlls makes room for a copy that is made anyway.
                "register_dll", "uninstall", "delete_dlls" -> Unit
                else -> return "unsupported step ${step.action}"
            }
            done++
        }

        // Into the prefix: the files, then the registry values, then the Windows version. Each noted as
        // it goes in (the files it adds, the originals it replaces, the registry before and after), so
        // removing the component later puts the prefix back: ComponentUndo.
        onStage("placing files")
        part(0f)
        val journal = ComponentUndo.open(wine.parentFile!!, c.name)
        try {
            if (c.steps.any { it.action == "uninstall" && it.str("file_name").equals("Wine Mono", ignoreCase = true) }) {
                clearOldMono(journal, stage, wine).takeIf { it > 0 }?.let { Log.i(TAG, "${c.name}: removed $it file(s) of the earlier Wine Mono") }
            }
            if (stage.drive.isDirectory) journal.place(stage.drive, File(wine, "drive_c"))
            onProgress(0.95f)
            onStage("writing the registry")
            stage.overrides.forEach { (dll, type) -> stage.registry += RegValue("HKCU", DLL_OVERRIDES, dll, "sz", type) }
            if (stage.registry.isNotEmpty()) {
                val system = WineRegistryFile(File(wine, "system.reg"))
                val user = WineRegistryFile(File(wine, "user.reg"))
                for (v in stage.registry) (if (v.hive == "HKCU") user else system).apply(v)
                system.save()
                user.save()
                Log.i(TAG, "${c.name}: ${system.written + user.written} registry value(s) written")
            }
            stage.windowsVersion?.let { setWindowsVersion(File(wine, "system.reg"), it) }
        } finally {
            journal.close()
        }
        stage.notes.distinct().forEach { Log.i(TAG, "${c.name}: $it") }
        OfflineTools.deleteTree(stage.root)
        OfflineTools.deleteTree(temp)
        return null
    }

    /**
     * "Uninstall Wine Mono" ahead of a Wine Mono: the files of the one in the prefix that the new one
     * does not have (another version's assemblies) are taken out, each noted so removing the component
     * puts them back. Nothing goes when the component brings no Mono of its own (.NET Framework). The
     * placeholder DLLs Mono's setup had Wine make under Microsoft.NET stay: they are the same for every
     * version and cannot be made here.
     */
    private fun setWindowsVersion(file: File, version: String) {
        // This value belongs in user.reg, not system.reg.
        val user = WineRegistryFile(File(file.parentFile, "user.reg"))
        user.apply(RegValue("HKCU", "Software\\Wine", "Version", "sz", version))
        user.save()
    }

    private fun clearOldMono(journal: ComponentUndo.Journal, stage: Stage, wine: File): Int =
        PrefixPlacer.removeStale(File(stage.drive, "windows/mono"), PrefixPlacer.resolve(File(wine, "drive_c"), "windows/mono")) {
            journal.beforeWrite(it)
        }

    /** An archive a catalog step downloaded: a tar.xz/zip here, an installer or cabinet by [PackageInstaller]. */
    private fun openArchive(engine: PackageInstaller, file: File, dest: File): Boolean = try {
        val name = file.name.lowercase()
        when {
            name.endsWith(".zip") -> unzip(file, dest)
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, file, dest)
            name.endsWith(".tar.zst") -> TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, file, dest)
            else -> {
                engine.unpackForSteps(file, dest) { _, _ -> }
                true
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "open ${file.name}", e); false
    }

    private fun unzip(file: File, dest: File): Boolean {
        val root = dest.canonicalFile
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(root, entry.name).canonicalFile
                if (!entry.isDirectory && out.path.startsWith(root.path + File.separator)) {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                }
                entry = zip.nextEntry
            }
        }
        return true
    }

    /** The catalog's MD5 is noted, not enforced: several were taken from the upstream source and are stale. */
    private fun checksumNote(step: ComponentStep, file: File, stage: Stage) {
        val md5 = step.field("file_checksum").takeIf { it.length == 32 } ?: return
        if (!digest(file, "MD5").equals(md5, ignoreCase = true)) stage.notes += "${file.name}: the catalog's checksum does not match"
    }

    private fun registryStep(step: ComponentStep): RegValue? {
        val full = step.str("key").replace("\\\\", "\\").trim('\\')
        val hive = when (full.substringBefore('\\').uppercase()) {
            "HKLM", "HKEY_LOCAL_MACHINE" -> "HKLM"
            "HKCU", "HKEY_CURRENT_USER" -> "HKCU"
            else -> return null
        }
        val key = full.substringAfter('\\', "")
        if (key.isEmpty()) return null
        return typed(hive, key, step)
    }

    private fun typed(hive: String, key: String, step: ComponentStep): RegValue {
        val data = step.str("data")
        return when (step.str("type").uppercase()) {
            "REG_DWORD" -> RegValue(hive, key, step.str("value"), "dword",
                data.trim().let { if (it.startsWith("0x")) it.removePrefix("0x").toLongOrNull(16) else it.toLongOrNull() ?: it.toLongOrNull(16) } ?: 0L)
            "REG_EXPAND_SZ" -> RegValue(hive, key, step.str("value"), "expand_sz", data)
            else -> RegValue(hive, key, step.str("value"), "sz", data)
        }
    }

    /** A copy into the system folders: win64/system32 -> system32, win32/syswow64 -> syswow64, plus a subfolder. */
    private fun copyToSystem(stage: Stage, from: File, pattern: String, dest: String) {
        val d = dest.replace('\\', '/')
        val (arch, base) = when (d.substringBefore('/').lowercase()) {
            "win64", "system32" -> "win64" to "system32"
            "win32", "syswow64" -> "win32" to "syswow64"
            else -> null to "system32"
        }
        val sub = d.substringAfter('/', "").trimEnd('/')
        copyMatching(from, pattern, File(stage.drive, "windows/$base" + if (sub.isEmpty()) "" else "/$sub"), arch)
    }

    private fun globRegex(pattern: String) =
        Regex("^" + pattern.split("*").joinToString(".*") { Regex.escape(it) } + "$", RegexOption.IGNORE_CASE)


    private fun copyMatching(srcRoot: File, pattern: String, dest: File, arch: String?) {
        if (pattern.isEmpty()) return
        val rx = globRegex(pattern)
        var matches = srcRoot.walkTopDown().filter { it.isFile && rx.matches(it.name) }.toList()
        if (arch != null) {
            val inArch = matches.filter { it.path.contains("${File.separator}$arch${File.separator}", true) }
            val other = if (arch == "win64") "win32" else "win64"
            matches = inArch.ifEmpty { matches.filterNot { it.path.contains("${File.separator}$other${File.separator}", true) } }
        }
        dest.mkdirs()
        matches.forEach { f -> f.copyTo(File(dest, f.name), overwrite = true) }
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- recordings ---------------------------------------------------------------------------

    /**
     * Lays [c] out from its recording into [stage]: the files from the archive beside it, or out of
     * the vendor's installer (by the path the recording maps, else by fingerprint), small generated
     * ones inline; and the recorded registry values. The recorder ran as "steamuser"; the paths and
     * values that name that user are moved to this prefix's.
     */
    private fun replaySnapshot(
        context: Context, c: Component, stage: Stage, work: File, tools: OfflineTools,
        part: (Float) -> Unit, onStage: (String) -> Unit,
    ): String? {
        val name = c.snapshot.substringAfterLast('/')
        val json = File(work, "${c.name}.snapshot.json")
        onStage("downloading the recording")
        if (!Downloader.downloadFile(c.snapshot, json) { f -> if (f >= 0) part(f * 0.3f) }) return "download failed: $name"
        val snapshot = runCatching { JSONObject(json.readText()) }.getOrNull() ?: return "the recording could not be read"
        json.delete()
        if (snapshot.optInt("schema") != 1) return "the recording is of a newer kind than this app reads"
        val files = snapshot.optJSONArray("files") ?: JSONArray()
        var placed = 0
        var missing = 0
        val archive = snapshot.optJSONObject("files_archive")
        if (archive != null) {
            val archiveName = archive.optString("name")
            val url = c.snapshot.substringBeforeLast('/') + "/" + archiveName
            val file = File(work, "${c.name}.files.tar.xz")
            onStage("downloading $archiveName")
            if (!Downloader.downloadFile(url, file) { f -> if (f >= 0) part(0.3f + f * 1.5f) }) return "download failed: $archiveName"
            archive.optString("sha256").takeIf { it.length == 64 }?.let { sha ->
                if (!digest(file, "SHA-256").equals(sha, ignoreCase = true)) return "checksum mismatch: $archiveName"
            }
            onStage("unpacking $archiveName")
            val unpacked = File(work, "${c.name}.files").apply { mkdirs() }
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, file, unpacked)) return "could not unpack $archiveName"
            file.delete()
            val drive = File(unpacked, "drive_c")
            if (drive.isDirectory) {
                for (f in OfflineTools.walkFiles(drive)) {
                    val rel = userPath(f.relativeTo(drive).invariantSeparatorsPath)
                    moveInto(f, File(stage.drive, rel))
                    placed++
                }
            }
            OfflineTools.deleteTree(unpacked)
        } else {
            // The files come out of the installer the catalog names, by the paths the recording maps.
            val step = c.steps.firstOrNull { it.action in INSTALLER_STEPS && it.field("url").startsWith("https://") }
                ?: return "the recording needs the installer, which the catalog does not name"
            val url = step.field("url")
            val installerName = url.substringBefore('?').substringAfterLast('/')
            val installer = File(work, "${c.name}-snapshot-src.bin")
            val unpacked = File(work, "${c.name}-snapshot-src")
            try {
                onStage("downloading $installerName")
                if (!Downloader.downloadFile(url, installer) { f -> if (f >= 0) part(0.3f + f * 1.5f) }) return "download failed: $installerName"
                onStage("unpacking $installerName")
                val recorder = snapshot.optJSONObject("installer")
                val innoTried = recorder?.optJSONArray("extractors")?.toString().orEmpty().contains("innoextract")
                val innoFailed = snapshot.optJSONArray("notes")?.toString().orEmpty().contains("innoextract extracted nothing")
                try {
                    PackageInstaller(tools, ImageFs.USER).unpackOnly(installer, unpacked,
                        if (innoTried && !innoFailed) InnoTools(context) else null) { _, text -> if (text.isNotEmpty()) onStage(text) }
                } catch (e: Exception) {
                    return "could not open $installerName"
                }
                installer.delete()
                val byPrint = lazy { fingerprints(unpacked, files) }
                for (i in 0 until files.length()) {
                    val entry = files.getJSONObject(i)
                    val rel = entry.optString("path")
                    if (rel.isEmpty() || rel.split('/').any { it == ".." }) continue
                    val target = File(stage.drive, userPath(rel))
                    val src = entry.optString("source")
                    when {
                        src.isNotEmpty() -> {
                            val size = entry.optLong("size", -1)
                            val sha = entry.optString("sha256")
                            val byPath = File(unpacked, src).takeIf { it.isFile && (size < 0 || it.length() == size) && ".." !in src.split('/') }
                            val found = byPath?.takeIf { sha.length != 64 || digest(it, "SHA-256").equals(sha, true) }
                                ?: byPrint.value[sha.lowercase()]
                            if (found != null) {
                                target.parentFile?.mkdirs()
                                found.copyTo(target, overwrite = true)
                                placed++
                            } else missing++
                        }
                        entry.has("data") -> {
                            target.parentFile?.mkdirs()
                            target.writeBytes(Base64.decode(entry.getString("data"), Base64.DEFAULT))
                            placed++
                        }
                        else -> missing++
                    }
                    if (i % 20 == 0) part(1.8f + 1.0f * (i + 1) / maxOf(1, files.length()))
                }
            } finally {
                installer.delete()
                OfflineTools.deleteTree(unpacked)
            }
        }
        if (missing > 0) stage.notes += "$missing file(s) the installer generates could not be reproduced here"
        val values = snapshot.optJSONArray("registry") ?: JSONArray()
        for (i in 0 until values.length()) toRegValue(values.getJSONObject(i))?.let { stage.registry += it }
        if (placed == 0 && values.length() == 0) return "the recording holds nothing to install"
        Log.i(TAG, "${c.name}: recording placed $placed file(s), ${values.length()} registry value(s), $missing missing")
        return null
    }

    /** A recorded path under drive_c, with the recorder's user moved to this prefix's. */
    private fun userPath(rel: String): String =
        if (rel.startsWith("users/steamuser/", ignoreCase = true)) "users/${ImageFs.USER}/" + rel.substring("users/steamuser/".length) else rel

    private fun moveInto(src: File, target: File) {
        target.parentFile?.mkdirs()
        if (!src.renameTo(target)) {
            src.copyTo(target, overwrite = true)
            src.delete()
        }
    }

    /** sha256 -> unpacked file, for the recorded files whose path did not lead to them. */
    private fun fingerprints(root: File, files: JSONArray): Map<String, File> {
        val sizes = HashSet<Long>()
        for (i in 0 until files.length()) files.getJSONObject(i).optLong("size", -1).takeIf { it >= 0 }?.let { sizes += it }
        val out = HashMap<String, File>()
        for (f in OfflineTools.walkFiles(root)) {
            if (f.length() in sizes) out.putIfAbsent(digest(f, "SHA-256").lowercase(), f)
        }
        return out
    }

    private fun toRegValue(o: JSONObject): RegValue? {
        val hive = o.optString("hive")
        val type = o.optString("type")
        val key = o.optString("key").trim('\\')
        if (hive !in setOf("HKLM", "HKCU") || key.isEmpty() || '\u0000' in key) return null
        val name = if (o.isNull("name")) "" else o.optString("name")
        val data: Any? = when (type) {
            "key" -> null
            "dword" -> o.optLong("data")
            "multi_sz" -> o.optJSONArray("data")?.let { a -> (0 until a.length()).map { STEAMUSER.replace(a.optString(it), "$1${ImageFs.USER}") } }
                ?: listOf(o.optString("data"))
            "sz", "expand_sz" -> STEAMUSER.replace(o.optString("data"), "$1${ImageFs.USER}")
            "binary" -> o.optString("data")
            "append", "prepend" -> o.optString("data")
            else -> return null
        }
        return RegValue(hive, key, name, type, data, o.optString("separator", ";"))
    }
}
