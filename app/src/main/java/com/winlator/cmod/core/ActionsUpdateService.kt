package com.winlator.cmod.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A user-started development transfer survives dismissal of the updater dialog. */
class ActionsUpdateService : Service() {
    internal data class State(
        val artifact: ActionsUpdates.Artifact? = null,
        val active: Boolean = false,
        val message: String = "",
        val done: Long = 0,
        val total: Long = 0,
        val speed: Long = 0,
        val ready: File? = null
    ) {
        val fraction get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        val timing: String get() = if (total <= 0) "" else buildString {
            append(String.format(Locale.US, "%.1f / %.1f MB", done / 1e6, total / 1e6))
            if (speed > 0 && done < total) {
                append(String.format(Locale.US, " · %.1f MB/s", speed / 1e6))
                val seconds = (total - done) / speed
                append(if (seconds >= 60) " · " + seconds / 60 + "m " + seconds % 60 + "s left" else " · " + seconds + "s left")
            }
        }
    }

    companion object {
        private const val CHANNEL = "app-updates"
        private const val PROGRESS = 621
        private const val COMPLETE = 622
        private const val CANCEL = "com.winlator.cmod.CANCEL_APP_UPDATE"
        private val mutable = MutableStateFlow(State())
        internal val state = mutable.asStateFlow()
        private var cancellation = AtomicBoolean(false)

        @Synchronized internal fun begin(context: Context, artifact: ActionsUpdates.Artifact) {
            if (mutable.value.active) return
            cancellation = AtomicBoolean(false)
            mutable.value = State(artifact = artifact, active = true, message = "Preparing download…", total = artifact.downloadSize)
            try {
                ContextCompat.startForegroundService(context, Intent(context, ActionsUpdateService::class.java))
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(active = false, message = e.message ?: "Could not start download")
            }
        }

        internal fun cancel() {
            if (mutable.value.active) {
                cancellation.set(true)
                mutable.value = mutable.value.copy(message = "Cancelling…")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var token: AtomicBoolean? = null
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null

    private fun openUpdater(): PendingIntent = PendingIntent.getActivity(this, PROGRESS,
        Intent(this, ActionsUpdateActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(value: State, complete: Boolean = false): Notification {
        val cancelIntent = PendingIntent.getService(this, PROGRESS,
            Intent(this, ActionsUpdateService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val detail = listOf(value.message, value.timing).filter { it.isNotBlank() }.joinToString("\n")
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (complete) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download)
            .setContentTitle(if (complete) "Update ready to install" else "Winlator Ludashi · App Updates")
            .setContentText(detail).setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(openUpdater()).setOnlyAlertOnce(true)
            .setOngoing(!complete).setAutoCancel(complete)
            .apply {
                if (complete) addAction(Notification.Action.Builder(android.R.drawable.stat_sys_download_done, "Install update", openUpdater()).build())
                else {
                    setProgress(100, (value.fraction * 100).toInt(), value.total <= 0 || value.done >= value.total)
                    addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent).build())
                }
            }.build()
    }

    private fun publish(value: State) {
        mutable.value = value
        runCatching { manager.notify(PROGRESS, notification(value)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            cancel()
            if (job == null) stopSelf()
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val artifact = mutable.value.artifact
        if (artifact == null || !mutable.value.active) { stopSelf(); return START_NOT_STICKY }
        val cancelled = cancellation
        token = cancelled
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App Updates", NotificationManager.IMPORTANCE_LOW))
        manager.cancel(COMPLETE)
        startForeground(PROGRESS, notification(mutable.value))
        job = scope.launch {
            var last = 0L
            var sampleTime = 0L
            var sampleBytes = 0L
            var speed = 0L
            val result = try {
                val apk = ActionsUpdates.download(this@ActionsUpdateService, artifact, cancelled,
                    { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (sampleTime == 0L || done < sampleBytes) {
                            sampleTime = now; sampleBytes = done; speed = 0
                        }
                        if (now - last >= 1000 || done == total) {
                            if (now > sampleTime) {
                                val measured = ((done - sampleBytes) * 1000 / (now - sampleTime)).coerceAtLeast(0)
                                speed = if (speed == 0L) measured else (speed * 2 + measured) / 3
                                sampleTime = now; sampleBytes = done
                            }
                            last = now
                            publish(mutable.value.copy(done = done, total = total, speed = speed))
                        }
                    },
                    { message -> publish(mutable.value.copy(message = message, speed = 0)) })
                mutable.value.copy(active = false, message = "Download and verification completed.",
                    ready = apk, done = mutable.value.total, speed = 0)
            } catch (e: Exception) {
                mutable.value.copy(active = false, speed = 0,
                    message = if (cancelled.get()) "Download cancelled. Tap to resume." else e.message ?: "Update failed")
            }
            withContext(NonCancellable + Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (result.ready != null) runCatching { manager.notify(COMPLETE, notification(result, true)) }
                job = null
                token = null
                stopSelf()
                mutable.value = result
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        token?.set(true)
        scope.cancel()
        super.onDestroy()
    }
}

/** Notification entry point; installation remains an explicit action in the updater. */
class ActionsUpdateActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        UpstreamUpdates.show(this, onDismiss = { finish() }, forceDevelopment = true)
    }
}
