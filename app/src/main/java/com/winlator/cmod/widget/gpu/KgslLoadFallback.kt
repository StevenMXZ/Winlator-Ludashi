// SPDX-License-Identifier: GPL-3.0-only
// Load-only adaptation of The412Banner/Ludashi 0ab0473, from DroidDeck #1969/#1981.
package com.winlator.cmod.widget.gpu

import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Read-only Adreno fallback. No GPU commands, frequency probing, or clock changes. */
object KgslLoadFallback {
    private const val RETRY_MS = 30_000L
    private const val IDLE_CLOSE_MS = 5_000L
    private val eligible by lazy { File("/dev/kgsl-3d0").exists() }
    private var reader: KgslBusy.Reader? = null
    private var load: MemstoreLoad? = null
    private var retryAt = 0L
    private var lastAsk = 0L
    private var worker: ScheduledExecutorService? = null
    private var idleCheck: ScheduledFuture<*>? = null

    /** Called on the HUD stats thread only after its sysfs candidates failed. */
    @JvmStatic @Synchronized fun busyPercent(): Int? {
        if (!eligible) return null
        val now = SystemClock.elapsedRealtime()
        lastAsk = now
        if (reader == null) {
            if (now < retryAt) return null
            val opened = KgslBusy.open()
            if (opened == null) {
                retryAt = now + RETRY_MS
                return null
            }
            reader = opened
            load = MemstoreLoad(1_000L)
        }
        if (idleCheck == null) {
            val executor = worker ?: Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "hud-gpu-idle").apply { isDaemon = true }
            }.also { worker = it }
            idleCheck = executor.scheduleWithFixedDelay(::closeWhenIdle, 1, 1, TimeUnit.SECONDS)
        }
        val current = reader ?: return null
        val window = load ?: return null
        current.run(true)
        val value = window.percent(now, current.counts())
        if (window.failed()) {
            KgslBusy.status = "timestamps did not move; retry in 30 seconds"
            stop()
            retryAt = now + RETRY_MS
            return null
        }
        return value
    }

    /** Stop on HUD/metric hide; the watchdog also handles callers disappearing unexpectedly. */
    @JvmStatic @Synchronized fun stop() {
        reader?.close()
        reader = null
        load = null
        idleCheck?.cancel(false)
        idleCheck = null
        worker?.shutdown()
        worker = null
    }

    @Synchronized private fun closeWhenIdle() {
        if (SystemClock.elapsedRealtime() - lastAsk >= IDLE_CLOSE_MS) stop()
    }
}
