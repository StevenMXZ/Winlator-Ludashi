package com.winlator.cmod.stores

import android.app.*
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class StoreDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var store: LudashiStore? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.channel(this, "store-downloads")
        startForeground(312, DownloadNotifications.build(this, "store-downloads", "itch.io", getString(com.winlator.cmod.R.string.gog_preparing)))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (com.winlator.cmod.stores.StoreSessions.paused || com.winlator.cmod.core.ContainerSessionGate.hasActiveSessions()) {
            stopSelf(startId); return START_NOT_STICKY
        }
        val selected = LudashiStore.find(intent?.getStringExtra("store"))
        val gameJson = intent?.getStringExtra("game")
        if (selected == null || gameJson == null) { stopSelf(); return START_NOT_STICKY }
        if (job?.isCompleted == false) {
            StoreSessions.state(selected).update { it.copy(error = "Another store download is active. Finish or cancel it first.") }
            return START_NOT_STICKY
        }
        store = selected
        startForeground(312, DownloadNotifications.build(this, "store-downloads", selected.title, getString(com.winlator.cmod.R.string.gog_preparing)))
        StoreSessions.cancel(selected); StoreSessions.fresh(selected)
        val container = intent.getIntExtra("container", 0)
        val upload = intent.getStringExtra("upload")?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val game = runCatching { StoreGame.parse(JSONObject(gameJson)) }.getOrNull()
        if (game == null || game.store != selected || container <= 0) { stopSelf(startId); return START_NOT_STICKY }
        val state = StoreSessions.state(selected)
        val preparing = getString(com.winlator.cmod.R.string.gog_preparing)
        job = scope.launch(StoreSessions.operation(selected)) { StoreTransfers.downloadLock.withLock {
            val folder = File(filesDir, "store-transfers/${selected.id}-${game.key}-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val staging = File(folder, "payload")
            val uploadJson = upload?.toString().orEmpty()
            val previous = state.value.transfers[game.key]
            val previousUpload = previous?.optString("upload")?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            val sameUpload = upload != null && previousUpload != null && previousUpload.optString("id") == upload.optString("id") &&
                previousUpload.optString("name") == upload.optString("name")
            val root = StoreTransfers.partialRoot(this@StoreDownloadService, game, previous.takeIf { sameUpload })
            fun persist(status: String, done: Long, total: Long, error: String = "") =
                StoreTransfers.save(this@StoreDownloadService, game, status, done, total, container, uploadJson, root, error)
            state.update { it.copy(activeGame = game, loading = false, stage = preparing, done = 0, total = 0, speed = 0, error = "") }
            try {
                StoreNetwork.check(this@StoreDownloadService, selected)
                val source = upload ?: error("Choose a Windows download")
                if (!sameUpload && previousUpload != null && previous?.has("partialRoot") == true) {
                    StoreTransfers.removePartial(StoreTransfers.partialRoot(this@StoreDownloadService, game, previous), game, previousUpload)
                }
                val downloadFolder = StoreTransfers.partialFolder(root, game, source)
                check((staging.mkdirs() || staging.isDirectory) && (downloadFolder.mkdirs() || downloadFolder.isDirectory)) {
                    "Could not create game download directories"
                }
                DownloadNotifications.update(this@StoreDownloadService, 312, "store-downloads", selected.title, game.title, 0, 0, preparing, true)
                persist("preparing", 0, 0)
                var meter = TransferMeter()
                var base = 0L
                var last = -200L
                var lastSaved = 0L
                fun progress(done: Long) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last < 200) return
                    val speed = meter.update(done - base)
                    last = now
                    state.update { it.copy(done = done, speed = speed) }
                    val current = state.value
                    DownloadNotifications.update(this@StoreDownloadService, 312, "store-downloads", selected.title, game.title, done, current.total, current.stage, speed = speed)
                    if (now - lastSaved >= 1000) {
                        persist("downloading", done, current.total)
                        lastSaved = now
                    }
                }
                val selectedUpload = StoreUpload(source.getString("id"), source.getString("name"), source.optLong("size"))
                val url = ItchConnector.file(this@StoreDownloadService, game, selectedUpload)
                val archive = StoreTransfers.partialArchive(downloadFolder, source)
                val downloading = getString(com.winlator.cmod.R.string.gog_downloading)
                val bytes = StoreNetwork.transfer(this@StoreDownloadService, selected, url, archive,
                    archiveValidated = !archive.extension.equals("exe", true), onLength = { length, offset ->
                        meter = TransferMeter()
                        base = offset
                        last = -200L
                        state.update { it.copy(done = offset, total = length.takeIf { it >= 0 } ?: selectedUpload.size, speed = 0, stage = downloading) }
                    }, progress = ::progress)
                ensureActive(); StoreNetwork.check(this@StoreDownloadService, selected)
                state.update { it.copy(stage = getString(com.winlator.cmod.R.string.gog_extracting), done = bytes, total = bytes, speed = 0) }
                DownloadNotifications.update(this@StoreDownloadService, 312, "store-downloads", selected.title, game.title, 0, 0, state.value.stage, true)
                StoreInstalls.extract(this@StoreDownloadService, selected, archive, staging)
                ensureActive(); StoreNetwork.check(this@StoreDownloadService, selected)
                persist("installing", bytes, bytes)
                StoreInstalls.publish(this@StoreDownloadService, game, staging, container)
                persist("completed", bytes, bytes)
                DownloadNotifications.completed(this@StoreDownloadService, 312, "store-downloads", selected.title, game.title)
                runCatching { StoreTransfers.removePartial(root, game, source) }
            } catch (error: Exception) {
                val stopped = StoreSessions.paused || !StoreSessions.live(selected)
                val message = if (stopped) "" else error.message ?: "Store download failed"
                val retained = upload?.let { candidate -> runCatching {
                    StoreTransfers.partialArchive(StoreTransfers.partialFolder(root, game, candidate), candidate).length()
                }.getOrDefault(0L) } ?: 0L
                persist(if (stopped) "paused" else "failed", retained, state.value.total, message)
                state.update { it.copy(error = message) }
            } finally {
                StoreSessions.discard(folder)
                state.update { it.copy(activeGame = null, stage = "", speed = 0) }
                StoreNetwork.stopIfIdle(selected)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        } }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        store?.let { if (StoreSessions.state(it).value.busy) StoreSessions.cancel(it) else StoreNetwork.stopIfIdle(it) }
        job?.cancel(); scope.cancel(); super.onDestroy()
    }
}
