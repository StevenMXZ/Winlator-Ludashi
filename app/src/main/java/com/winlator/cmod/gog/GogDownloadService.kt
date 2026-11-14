package com.winlator.cmod.gog

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.winlator.cmod.R
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.core.unpack.Innoextract
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GogDownloadService : Service() {
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        com.winlator.cmod.stores.DownloadNotifications.channel(this, "gog-downloads")
        startForeground(311, com.winlator.cmod.stores.DownloadNotifications.build(this, "gog-downloads", "GOG", getString(R.string.gog_preparing)))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gameId = intent?.getStringExtra("game")
        val containerId = intent?.getIntExtra("container", 0) ?: 0
        val installer = intent?.getStringExtra("installer")
        if (gameId == null || installer == null || containerId <= 0) { stopSelf(); return START_NOT_STICKY }
        if (!stopped.compareAndSet(false, true)) return START_NOT_STICKY
        startForeground(311, com.winlator.cmod.stores.DownloadNotifications.build(this, "gog-downloads", "GOG", getString(R.string.gog_preparing)))
        GogLibrary.cancelRequested = false
        GogLibrary.cancelled = false
        GogLibrary.mutable.update { it.copy(busy = true, cancellable = true, activeId = gameId, stage = getString(R.string.gog_preparing), error = "", done = 0, total = 0, speed = 0) }
        GogLibrary.worker.execute {
            try { install(gameId, containerId, JSONObject(installer)) }
            catch (error: Exception) {
                val state = GogLibrary.mutable.value
                val paused = GogLibrary.cancelled || com.winlator.cmod.stores.StoreSessions.paused
                val message = if (paused) "" else error.message ?: "GOG installation failed"
                val game = state.games.find { it.id == gameId } ?: state.transfers[gameId]?.let { runCatching { GogGame.parse(it) }.getOrNull() }
                if (game != null) runCatching { GogTransfers.save(this, game, if (paused) { if (GogLibrary.cancelRequested) "cancelled" else "paused" } else "failed", state.done, state.total, containerId, installer, message) }
                GogLibrary.mutable.update { it.copy(error = message) }
            }
            finally {
                GogLibrary.mutable.update { it.copy(busy = false, cancellable = true, stage = "") }
                stopped.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { GogLibrary.cancelled = true; super.onDestroy() }
    private fun install(id: String, containerId: Int, installer: JSONObject) {
        check(GogSettings.enabled(this)) { "GOG integration is disabled" }
        require(id.toLongOrNull()?.let { it > 0 } == true)
        check(Innoextract.isAvailable(this)) { "The GOG installer extractor is unavailable" }
        check(GogLibrary.mutable.value.installed[id] == null) { "This GOG game is already installed" }
        val game = GogLibrary.mutable.value.games.find { it.id == id } ?: GogLibrary.mutable.value.transfers[id]?.let { GogGame.parse(it) } ?: error("GOG game is missing from the library")
        GogTransfers.save(this, game, "preparing", 0, 0, containerId, installer.toString())
        val container = ContainerManager(this).getContainerById(containerId) ?: error("Container no longer exists")
        val downloads = File(com.winlator.cmod.stores.StoreDownloadPreferences.folder(this, "gog"), id).apply { mkdirs() }
        val parts = installer.getJSONArray("files")
        val total = (0 until parts.length()).sumOf { parts.getJSONObject(it).optLong("size").coerceAtLeast(0) }
        check(downloads.usableSpace > total + 64L * 1024 * 1024) { "Not enough space for the GOG installer" }
        GogLibrary.mutable.update { it.copy(total = total) }
        var completed = 0L
        val meter = com.winlator.cmod.stores.TransferMeter()
        val pacer = com.winlator.cmod.stores.TransferPacer(com.winlator.cmod.stores.StoreDownloadPreferences.limit(this, "gog"))
        var lastUpdate = 0L
        var lastSaved = 0L
        val files = mutableListOf<File>()
        for (i in 0 until parts.length()) {
            check(!GogLibrary.cancelled) { "Download paused" }
            val part = parts.getJSONObject(i)
            val link = GogApi.get(this, part.getString("downlink"))
            val address = link.getString("downlink")
            val name = GogApi.fileName(address)
            require(files.none { it.name == name }) { "Duplicate GOG download filename" }
            val target = File(downloads, name)
            val temporary = File(downloads, "$name.pending")
            GogLibrary.mutable.update { it.copy(stage = getString(R.string.gog_downloading)) }
            val integrity = GogApi.integrity(this, link)
            if (temporary.isFile && GogApi.verifyFile(temporary, integrity)) {
                check(temporary.renameTo(target)) { "Cannot finalize GOG installer" }
            }
            if (target.isFile) {
                val valid = GogApi.verifyFile(target, integrity)
                if (valid) { files.add(target); completed += target.length(); continue }
                check(target.delete()) { "Cannot replace an invalid GOG installer" }
            }
            GogApi.download(address, temporary, integrity,
                { GogLibrary.cancelled }, { done, actualSize ->
                    pacer.pace(completed + done) { check(!GogLibrary.cancelled && !com.winlator.cmod.stores.StoreSessions.paused) { "Download paused" } }
                    val speed = meter.update(completed + done)
                    com.winlator.cmod.stores.DownloadNotifications.update(this, 311, "gog-downloads", "GOG", game.title, completed + done, GogLibrary.mutable.value.total, getString(R.string.gog_downloading))
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 250) {
                        GogLibrary.mutable.update { it.copy(done = completed + done, speed = speed,
                            total = completed + actualSize.coerceAtLeast(done) + (i + 1 until parts.length()).sumOf { index -> parts.getJSONObject(index).optLong("size").coerceAtLeast(0) }) }
                        lastUpdate = now
                    }
                    if (now - lastSaved >= 1000) {
                        val current = GogLibrary.mutable.value
                        GogTransfers.save(this, game, "downloading", current.done, current.total, containerId, installer.toString())
                        lastSaved = now
                    }
                })
            check(temporary.renameTo(target)) { "Cannot finalize GOG installer" }
            files.add(target); completed += target.length()
            GogLibrary.mutable.update { it.copy(done = completed) }
        }
        val setup = files.firstOrNull { it.extension.equals("exe", true) } ?: error("No Windows installer found")
        check(!GogLibrary.cancelled) { "Download paused" }
        check(ContainerSessionGate.edit(containerId)) { "Exit the container before installing this game" }
        val root = File(container.rootDir, ".wine/drive_c/GOG Games").apply { mkdirs() }
        val destination = File(root, "Ludashi-$id")
        val staging = File(root, ".ludashi-$id-installing-${java.util.UUID.randomUUID()}")
        var moved = false
        var committed = false
        try {
            check(!destination.exists()) { "A GOG installation already exists at this location" }
            if (staging.exists()) staging.deleteRecursively()
            GogLibrary.mutable.update { it.copy(stage = getString(R.string.gog_extracting), speed = 0, cancellable = false) }
            GogTransfers.save(this, game, "installing", completed, completed, containerId, installer.toString())
            val result = Innoextract.extract(this, setup, staging, {}, {}, { com.winlator.cmod.stores.StoreSessions.trackProcess("gog", it) })
            check(result.exitCode == 0) { "The GOG installer could not be extracted (code ${result.exitCode}). Its files were kept for retry." }
            check(!GogLibrary.cancelled) { "Installation cancelled" }
            val metadata = staging.walkTopDown().maxDepth(5).firstOrNull { it.name == "goggame-$id.info" && it.isFile }
            val payload = metadata?.parentFile ?: File(staging, "app").takeIf { it.isDirectory } ?: staging
            val executables = payload.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && it.extension.equals("exe", true) && !it.name.startsWith("unins", true) }
                .take(256).map { it.relativeTo(payload).invariantSeparatorsPath }.toList()
            check(executables.isNotEmpty()) { "The installer did not contain a Windows executable" }
            val info = metadata?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val tasks = info?.optJSONArray("playTasks") ?: JSONArray()
            val task = (0 until tasks.length()).map { tasks.getJSONObject(it) }
                .firstOrNull { it.optBoolean("isPrimary") && it.optString("path").replace('\\', '/') in executables }
            val selected = task?.optString("path")?.replace('\\', '/') ?: com.winlator.cmod.stores.StoreExecutables.default(executables, game.title)
            check(payload.renameTo(destination)) { "Cannot finalize GOG game files" }
            moved = true
            val record = game.json().put("language", installer.optString("language")).put("path", destination.canonicalPath).put("container", containerId)
                .put("executables", JSONArray(executables)).put("executable", selected)
                .put("arguments", task?.optString("arguments").orEmpty()).put("shortcut", File(container.desktopDir, "GOG-$id.desktop").path)
            record.put("installedSize", payloadSize(destination))
            val information = File(filesDir, "store-information/gog-${com.winlator.cmod.stores.storeHash(id)}.json")
            if (information.isFile) runCatching { record.put("information", JSONObject(information.readText())) }
            if (selected.isNotBlank()) writeShortcut(this, id, record)
            ensureIcon(this, id, record)
            GogLibrary.atomic(File(GogLibrary.records(this), "$id.json"), record.toString())
            committed = true
            com.winlator.cmod.stores.StoreSessions.discard(downloads)
            GogLibrary.updateInstalled(this)
            GogTransfers.save(this, game, "completed", completed, completed, containerId, installer.toString())
        } finally {
            if (moved && !committed) {
                staging.mkdirs()
                val incomplete = File(staging, ".incomplete-payload")
                if (!destination.renameTo(incomplete) && !com.winlator.cmod.stores.StoreSessions.paused) destination.deleteRecursively()
                File(container.desktopDir, "GOG-$id.desktop").delete()
            }
            com.winlator.cmod.stores.StoreSessions.discard(staging)
            ContainerSessionGate.edited(containerId)
        }
    }
    companion object {
        private fun payloadSize(root: File): Long = root.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.onEach { check(!GogLibrary.cancelled && !com.winlator.cmod.stores.StoreSessions.paused) { "Installation cancelled" } }.filter { it.isFile }.sumOf { it.length() }
        internal fun ensureIcon(context: Context, id: String, record: JSONObject) {
            runCatching {
                val container = ContainerManager(context).getContainerById(record.getInt("container")) ?: return
                val iconFile = File(container.getIconsDir(64), "gog-$id.png")
                val art = File(context.cacheDir, "gog-art/$id.jpg")
                val bitmap = if (art.isFile) android.graphics.BitmapFactory.decodeFile(art.path) else null
                val icon = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(icon)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, 64, 64), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)); bitmap.recycle()
                } else context.getDrawable(com.winlator.cmod.R.drawable.ic_gog)?.apply { setBounds(0, 0, 64, 64); draw(canvas) }
                iconFile.parentFile?.mkdirs()
                iconFile.outputStream().use { icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; icon.recycle()
                val shortcut = File(record.getString("shortcut")).canonicalFile
                require(shortcut.parentFile == container.desktopDir.canonicalFile)
                if (shortcut.isFile) {
                    val text = shortcut.readText()
                    if (text.substringBefore("[Extra Data]").lineSequence().none { it.trim().startsWith("Icon=") })
                        GogLibrary.atomic(shortcut, text.replaceFirst("[Desktop Entry]", "[Desktop Entry]\nIcon=gog-$id"))
                }
            }.onFailure { android.util.Log.w("GOG", "Could not update installed game icon", it) }
        }
        internal fun writeShortcut(context: Context, id: String, record: JSONObject) {
            val executable = record.getString("executable")
            val candidates = record.getJSONArray("executables")
            require((0 until candidates.length()).any { candidates.getString(it) == executable }) { "Invalid GOG executable" }
            val container = ContainerManager(context).getContainerById(record.getInt("container")) ?: error("Container no longer exists")
            val root = File(record.getString("path")).canonicalFile
            val target = File(root, executable).canonicalFile
            require(target.toPath().startsWith(root.toPath()) && target.isFile) { "GOG executable is missing" }
            require(File(record.getString("shortcut")).canonicalFile.parentFile == container.desktopDir.canonicalFile)
            val drive = File(container.rootDir, ".wine/drive_c").canonicalFile
            require(target.toPath().startsWith(drive.toPath()))
            val wine = "C:\\" + target.relativeTo(drive).path.replace('/', '\\')
            val safeTitle = record.getString("title").replace('\n', ' ').replace('\r', ' ')
            val args = record.optString("arguments").replace('\n', ' ').replace('\r', ' ')
            val desktop = File(record.getString("shortcut"))
            val previous = if (desktop.isFile) desktop.readText() else ""
            val entry = com.winlator.cmod.stores.updateStoreDesktop(previous, "Desktop Entry", linkedMapOf("Name" to safeTitle, "Exec" to "wine ${com.winlator.cmod.steam.SteamCatalog.desktopExecPath(wine)}", "Type" to "Application", "Icon" to "gog-$id"))
            GogLibrary.atomic(desktop, com.winlator.cmod.stores.updateStoreDesktop(entry, "Extra Data", if (previous.isBlank()) mapOf("gogGameId" to id, "execArgs" to args) else mapOf("gogGameId" to id)))
        }
    }
}
