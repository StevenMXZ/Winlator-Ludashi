package com.winlator.cmod.stores

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.core.unpack.SevenZip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

internal object StoreInstalls {
    fun extract(context: Context, store: LudashiStore, archive: File, destination: File) {
        StoreNetwork.check(context, store)
        if (archive.name.endsWith(".zip", true)) {
            val entries = java.util.zip.ZipFile(archive).use { zip -> zip.size() }
            require(entries > 0) { "Empty game archive" }
            var extracted = 0
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                val seen = mutableSetOf<String>()
                while (true) {
                    StoreNetwork.check(context, store)
                    val entry = zip.nextEntry ?: break
                    val relative = entry.name.trimEnd('/')
                    val target = safeStorePath(destination, relative)
                    check(seen.add(relative.lowercase())) { "Archive has duplicate file paths" }
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var bytes = 0L
                            while (true) {
                                StoreNetwork.check(context, store)
                                val size = zip.read(buffer); if (size < 0) break
                                check(target.parentFile.usableSpace > size + 1024 * 1024L) { "Not enough space for this game" }
                                output.write(buffer, 0, size); bytes += size
                            }
                            check(entry.size < 0 || bytes == entry.size) { "Incomplete ZIP entry" }
                        }
                    }
                    zip.closeEntry(); extracted++
                }
            }
            check(extracted == entries) { "Incomplete ZIP archive" }
        } else if (archive.extension.equals("exe", true)) {
            check(archive.inputStream().use { it.read() == 'M'.code && it.read() == 'Z'.code }) { "The download was not a Windows executable" }
            archive.inputStream().use { input ->
                safeStorePath(destination, archive.name).outputStream().use { output -> copyPayload(context, store, input, output) }
            }
        } else {
            check(SevenZip.isAvailable(context)) { "The archive extractor is unavailable" }
            val listing = SevenZip.newProcess(context, "l", "-slt", "-ba", archive.path).redirectErrorStream(true).start()
            StoreSessions.trackProcess(store.id, listing)
            listing.outputStream.close()
            listing.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                StoreNetwork.check(context, store)
                if (line.startsWith("Path = ")) safeStorePath(destination, line.substringAfter("Path = "))
                check(!line.startsWith("Symbolic Link = ") && !line.startsWith("Hard Link = ")) { "Store archive contains links" }
            } }
            check(listing.waitFor() == 0) { "Could not inspect the game archive" }
            val process = SevenZip.newProcess(context, "x", "-y", "-o${destination.path}", archive.path).redirectErrorStream(true).start()
            StoreSessions.trackProcess(store.id, process)
            process.outputStream.close()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { StoreNetwork.check(context, store) } }
            check(process.waitFor() == 0) { "Could not extract the game archive" }
        }
        destination.walkTopDown().forEach { StoreNetwork.check(context, store); check(!java.nio.file.Files.isSymbolicLink(it.toPath())) { "Game archive contains symbolic links" } }
    }
    internal fun copyPayload(context: Context, store: LudashiStore, input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            StoreNetwork.check(context, store)
            val count = input.read(buffer); if (count < 0) return
            StoreNetwork.check(context, store)
            output.write(buffer, 0, count)
        }
    }
    fun publish(context: Context, game: StoreGame, stage: File, containerId: Int): JSONObject {
        StoreNetwork.check(context, game.store)
        val executables = stage.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.onEach { StoreNetwork.check(context, game.store) }.filter {
            it.isFile && it.extension.equals("exe", true) && !Regex("^(unins|uninstall|crashreport|vc_redist|vcredist)", RegexOption.IGNORE_CASE).containsMatchIn(it.name)
        }.take(512).map { it.relativeTo(stage).invariantSeparatorsPath }.toList()
        check(executables.isNotEmpty()) { "This package contains no Windows executable" }
        val installedSize = stage.walkTopDown().onEach { StoreNetwork.check(context, game.store) }.filter { it.isFile }.sumOf { it.length() }
        val container = ContainerManager(context).getContainerById(containerId) ?: error("Container was removed")
        val target = File(container.rootDir, ".wine/drive_c/Ludashi Games/${game.store.id}-${game.key}")
        val desktop = File(container.desktopDir, "${game.store.id}-${game.key}.desktop")
        val selected = StoreExecutables.default(executables, game.title)
        check(ContainerSessionGate.edit(containerId)) { "Exit the container before finishing this installation" }
        var moved = false
        var committed = false
        try {
            check(!target.exists() && !desktop.exists()) { "An installation already exists at this location" }
            target.parentFile?.mkdirs()
            check(stage.renameTo(target)) { "Could not finalize the game directory" }; moved = true
            val record = JSONObject().put("installedSize", installedSize).put("game", game.json()).put("path", target.canonicalPath).put("container", containerId)
                .put("shortcut", desktop.path).put("executables", JSONArray(executables)).put("executable", selected)
            val information = File(context.filesDir, "store-information/${game.store.id}-${storeHash(game.id)}.json")
            if (information.isFile) runCatching { record.put("information", JSONObject(information.readText())) }
            if (selected.isNotBlank()) writeShortcut(context, record)
            icon(context, game, containerId)
            atomicStoreFile(File(StoreSessions.records(context, game.store), "${game.key}.json"), record.toString())
            committed = true
            StoreSessions.installed(context, game.store)
            return record
        } finally {
            if (moved && !committed) {
                if (target.renameTo(stage)) StoreSessions.discard(stage) else if (!StoreSessions.paused) target.deleteRecursively()
                desktop.delete()
            }
            ContainerSessionGate.edited(containerId)
        }
    }
    fun writeShortcut(context: Context, record: JSONObject) {
        val game = StoreGame.parse(record.getJSONObject("game"))
        val container = ContainerManager(context).getContainerById(record.getInt("container")) ?: error("Container was removed")
        val executable = record.getString("executable")
        val candidates = record.getJSONArray("executables")
        require((0 until candidates.length()).any { candidates.getString(it) == executable })
        val target = safeStorePath(File(record.getString("path")), executable)
        val drive = File(container.rootDir, ".wine/drive_c").canonicalFile
        require(target.isFile && target.toPath().startsWith(drive.toPath()))
        val desktop = File(record.getString("shortcut")).canonicalFile
        require(desktop.parentFile == container.desktopDir.canonicalFile)
        val windows = "C:\\" + target.relativeTo(drive).path.replace('/', '\\')
        val name = game.title.replace('\n', ' ').replace('\r', ' ')
        val previous = if (desktop.isFile) desktop.readText() else ""
        val entry = updateStoreDesktop(previous, "Desktop Entry", linkedMapOf("Name" to name, "Exec" to "wine ${com.winlator.cmod.steam.SteamCatalog.desktopExecPath(windows)}", "Type" to "Application", "Icon" to "${game.store.id}-${game.key}"))
        atomicStoreFile(desktop, updateStoreDesktop(entry, "Extra Data", mapOf("ludashiStore" to game.store.id, "storeGameId" to game.id.replace('\n', ' ').replace('\r', ' '))))
    }
    fun shortcut(context: Context, record: JSONObject): Shortcut {
        val container = ContainerManager(context).getContainerById(record.getInt("container")) ?: error("Container was removed")
        val desktop = File(record.getString("shortcut")).canonicalFile
        require(desktop.isFile && desktop.parentFile == container.desktopDir.canonicalFile)
        return Shortcut(container, desktop)
    }
    fun icon(context: Context, game: StoreGame, containerId: Int) {
        val container = ContainerManager(context).getContainerById(containerId) ?: return
        val cover = File(context.cacheDir, "${game.store.id}-art/${game.key}.jpg")
        val bitmap = if (cover.isFile) BitmapFactory.decodeFile(cover.path) else null
        val icon = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(icon)
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, 64, 64), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)); bitmap.recycle()
        } else context.getDrawable(game.store.icon)?.apply { setBounds(0, 0, 64, 64); draw(canvas) }
        val target = File(container.getIconsDir(64), "${game.store.id}-${game.key}.png")
        target.parentFile?.mkdirs()
        target.outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }; icon.recycle()
    }
    fun uninstall(context: Context, record: JSONObject) {
        val game = StoreGame.parse(record.getJSONObject("game"))
        val containerId = record.getInt("container")
        check(ContainerSessionGate.edit(containerId)) { "Exit the container before uninstalling" }
        try {
            val container = ContainerManager(context).getContainerById(containerId) ?: error("Container was removed")
            val expected = File(container.rootDir, ".wine/drive_c/Ludashi Games/${game.store.id}-${game.key}").canonicalFile
            require(File(record.getString("path")).canonicalFile == expected)
            check(expected.deleteRecursively()) { "Could not remove game files" }
            val desktop = File(record.getString("shortcut")).canonicalFile
            require(desktop.parentFile == container.desktopDir.canonicalFile)
            desktop.delete()
            File(container.getIconsDir(64), "${game.store.id}-${game.key}.png").delete()
            check(File(StoreSessions.records(context, game.store), "${game.key}.json").delete())
            StoreSessions.installed(context, game.store)
        } finally { ContainerSessionGate.edited(containerId) }
    }
}
