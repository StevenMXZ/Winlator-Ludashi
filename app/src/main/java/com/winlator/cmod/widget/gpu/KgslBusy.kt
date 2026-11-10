// SPDX-License-Identifier: GPL-3.0-only

package com.winlator.cmod.widget.gpu

import android.util.Log


object KgslBusy {
    private const val TAG = "HudGpu"

    @JvmStatic private external fun nativeOpen(error: IntArray): Long
    @JvmStatic private external fun nativeMode(handle: Long): Int
    @JvmStatic private external fun nativeRun(handle: Long, on: Boolean)
    @JvmStatic private external fun nativeRead(handle: Long, out: LongArray): Boolean
    @JvmStatic private external fun nativeClose(handle: Long)

    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("gpuusage") }.onFailure { Log.w(TAG, "hud: gpuusage library: $it") }.isSuccess
    }

    class Reader internal constructor(private var handle: Long) : AutoCloseable {
        /** Samples once a millisecond while on. */
        @Synchronized fun run(on: Boolean) {
            if (handle != 0L) nativeRun(handle, on)
        }

        /** Which memstore entries are watched: [MODE_RINGBUFFER] or [MODE_CONTEXTS] (hardware-scheduled kernels). */
        val mode: Int = if (handle != 0L) runCatching { nativeMode(handle) }.getOrDefault(0) else 0

        /** Running totals: busy samples, samples, samples in which a ringbuffer or context started new work. */
        @Synchronized fun counts(): LongArray? {
            if (handle == 0L) return null
            val out = LongArray(3)
            return if (nativeRead(handle, out)) out else null
        }

        @Synchronized override fun close() {
            if (handle != 0L) nativeClose(handle)
            handle = 0L
        }
    }

    const val MODE_RINGBUFFER = 1
    const val MODE_CONTEXTS = 2

    /** What the last [open] found, for the HUD diagnostics export. */
    @Volatile var status = "not tried"

    fun open(): Reader? {
        if (!loaded) {
            status = "gpuusage library did not load"
            return null
        }
        val error = IntArray(1)
        val handle = runCatching { nativeOpen(error) }.getOrDefault(0L)
        if (handle == 0L) {
            status = "open failed: ${KgslErrors.describe(error[0])}"
            Log.i(TAG, "hud: no GPU load from the kgsl memstore (open error ${error[0]})")
            return null
        }
        val reader = Reader(handle)
        status = if (reader.mode == MODE_CONTEXTS) "per-context stamps (hardware-scheduled kernel)" else "ringbuffer stamps"
        Log.i(TAG, "hud: kgsl memstore reader uses $status")
        return reader
    }
}

