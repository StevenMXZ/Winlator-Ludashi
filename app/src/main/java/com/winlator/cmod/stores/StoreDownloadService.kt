package com.winlator.cmod.stores

import android.app.*
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

class StoreDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var store: LudashiStore? = null
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
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
        job = scope.launch(StoreSessions.operation(selected)) {
            val state = StoreSessions.state(selected)
            val folder = File(filesDir, "store-transfers/${selected.id}-${game.key}-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val staging = File(folder, "payload")
            val pending = File(StoreDownloadPreferences.folder(this@StoreDownloadService, selected.id), folder.name)
            staging.deleteRecursively(); staging.mkdirs(); pending.mkdirs()
            state.update { it.copy(busy = true, loading = false, active = game.id, stage = getString(com.winlator.cmod.R.string.gog_preparing), done = 0, total = 0, speed = 0, error = "") }
            DownloadNotifications.update(this@StoreDownloadService, 312, "store-downloads", selected.title, game.title, 0, 0, state.value.stage, true)
            StoreTransfers.save(this@StoreDownloadService, game, "preparing", 0, 0, container, upload?.toString().orEmpty())
            try {
                StoreNetwork.check(this@StoreDownloadService, selected)
                val meter = TransferMeter()
                var last = 0L
                var lastSaved = 0L
                fun progress(done: Long) {
                    val speed = meter.update(done)
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last >= 200) { state.update { it.copy(done = done, speed = speed) }; last = now }
                    DownloadNotifications.update(this@StoreDownloadService, 312, "store-downloads", selected.title, game.title, done, state.value.total, state.value.stage)
                    if (now - lastSaved >= 1000) {
                        StoreTransfers.save(this@StoreDownloadService, game, "downloading", done, state.value.total, container, upload?.toString().orEmpty())
                        lastSaved = now
                    }
                }
                val source = upload ?: error("Choose a Windows download")
                val selectedUpload = StoreUpload(source.getString("id"), source.getString("name"), source.optLong("size"))
                val url = ItchConnector.file(this@StoreDownloadService, game, selectedUpload)
                val filename = selectedUpload.name
                require(filename.isNotBlank() && filename.none { it in "/\\\r\n\u0000" } && filename !in listOf(".", ".."))
                val archive = File(pending, filename)
                state.update { it.copy(total = selectedUpload.size, stage = getString(com.winlator.cmod.R.string.gog_downloading)) }
                StoreNetwork.transfer(this@StoreDownloadService, selected, url, archive, archiveValidated = !archive.extension.equals("exe", true)) { progress(it) }
                ensureActive(); StoreNetwork.check(this@StoreDownloadService, selected)
                state.update { it.copy(stage = getString(com.winlator.cmod.R.string.gog_extracting), speed = 0) }
                DownloadNotifications.update(this@StoreDownloadService, 312, "store-downloads", selected.title, game.title, 0, 0, state.value.stage, true)
                StoreInstalls.extract(this@StoreDownloadService, selected, archive, staging)
                ensureActive(); StoreNetwork.check(this@StoreDownloadService, selected)
                state.update { it.copy(stage = getString(com.winlator.cmod.R.string.gog_extracting), speed = 0) }
                StoreTransfers.save(this@StoreDownloadService, game, "installing", state.value.done, state.value.total, container, upload?.toString().orEmpty())
                StoreInstalls.publish(this@StoreDownloadService, game, staging, container)
                StoreTransfers.save(this@StoreDownloadService, game, "completed", state.value.total, state.value.total, container, upload?.toString().orEmpty())
            } catch (error: Exception) {
                val stopped = StoreSessions.paused || !StoreSessions.live(selected)
                val message = if (stopped) "" else error.message ?: "Store download failed"
                StoreTransfers.save(this@StoreDownloadService, game, if (stopped) "paused" else "failed", state.value.done, state.value.total, container, upload?.toString().orEmpty(), message)
                state.update { it.copy(error = message) }
            } finally {
                StoreSessions.discard(folder)
                StoreSessions.discard(pending)
                state.update { it.copy(busy = false, stage = "") }
                StoreNetwork.stopIfIdle(selected)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        store?.let { if (StoreSessions.state(it).value.busy) StoreSessions.cancel(it) else StoreNetwork.stopIfIdle(it) }
        job?.cancel(); scope.cancel(); super.onDestroy()
    }
}
