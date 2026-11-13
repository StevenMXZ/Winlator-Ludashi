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
        manager.createNotificationChannel(NotificationChannel("gog-downloads", "GOG downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, GogActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(311, Notification.Builder(this, "gog-downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GOG · Ludashi").setContentText(getString(R.string.gog_preparing)).setContentIntent(open).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gameId = intent?.getStringExtra("game")
        val containerId = intent?.getIntExtra("container", 0) ?: 0
        val installer = intent?.getStringExtra("installer")
        if (gameId == null || installer == null || containerId <= 0) { stopSelf(); return START_NOT_STICKY }
        if (!stopped.compareAndSet(false, true)) return START_NOT_STICKY
        GogLibrary.cancelled = false
        GogLibrary.mutable.update { it.copy(busy = true, cancellable = true, activeId = gameId, stage = getString(R.string.gog_preparing), error = "", done = 0, total = 0) }
        GogLibrary.worker.execute {
            try { install(gameId, containerId, JSONObject(installer)) }
            catch (error: Exception) { GogLibrary.mutable.update { it.copy(error = error.message ?: "GOG installation failed") } }
            finally {
                GogLibrary.mutable.update { it.copy(busy = false, cancellable = true, stage = "") }
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
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
        val game = GogLibrary.mutable.value.games.find { it.id == id } ?: error("GOG game is missing from the library")
        val container = ContainerManager(this).getContainerById(containerId) ?: error("Container no longer exists")
        val downloads = File(filesDir, "gog-downloads/$id").apply { mkdirs() }
        val parts = installer.getJSONArray("files")
        val total = (0 until parts.length()).sumOf { parts.getJSONObject(it).optLong("size").coerceAtLeast(0) }
        check(downloads.usableSpace > total + 64L * 1024 * 1024) { "Not enough space for the GOG installer" }
        GogLibrary.mutable.update { it.copy(total = total) }
        var completed = 0L
        var lastUpdate = 0L
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
            GogApi.download(address, temporary, integrity,
                { GogLibrary.cancelled }, { done, actualSize ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate >= 250) {
                        GogLibrary.mutable.update { it.copy(done = completed + done,
                            total = completed + actualSize.coerceAtLeast(done) + (i + 1 until parts.length()).sumOf { index -> parts.getJSONObject(index).optLong("size").coerceAtLeast(0) }) }
                        lastUpdate = now
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
        val staging = File(root, ".ludashi-$id-installing")
        var moved = false
        var committed = false
        try {
            check(!destination.exists()) { "A GOG installation already exists at this location" }
            if (staging.exists()) staging.deleteRecursively()
            GogLibrary.mutable.update { it.copy(stage = getString(R.string.gog_extracting), cancellable = false) }
            val result = Innoextract.extract(this, setup, staging, {}, {})
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
            val selected = task?.optString("path")?.replace('\\', '/') ?: executables.singleOrNull().orEmpty()
            check(payload.renameTo(destination)) { "Cannot finalize GOG game files" }
            moved = true
            val record = game.json().put("path", destination.canonicalPath).put("container", containerId)
                .put("executables", JSONArray(executables)).put("executable", selected)
                .put("arguments", task?.optString("arguments").orEmpty()).put("shortcut", File(container.desktopDir, "GOG-$id.desktop").path)
            if (selected.isNotBlank()) writeShortcut(this, id, record)
            GogLibrary.atomic(File(GogLibrary.records(this), "$id.json"), record.toString())
            committed = true
            downloads.deleteRecursively()
            GogLibrary.updateInstalled(this)
        } finally {
            staging.deleteRecursively()
            if (moved && !committed) {
                destination.deleteRecursively()
                File(container.desktopDir, "GOG-$id.desktop").delete()
            }
            ContainerSessionGate.edited(containerId)
        }
    }
    companion object {
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
            GogLibrary.atomic(File(record.getString("shortcut")), "[Desktop Entry]\nName=$safeTitle\nExec=wine ${com.winlator.cmod.steam.SteamCatalog.desktopExecPath(wine)}\nType=Application\n\n[Extra Data]\ngogGameId=$id\nexecArgs=$args\n")
        }
    }
}
