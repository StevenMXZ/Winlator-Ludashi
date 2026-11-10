package com.winlator.cmod.feature.stores.steam.ludashisteam

// Native depot download callbacks; methods run on a worker thread.
interface LudashiDownloadListener {

    // Per-chunk progress for the current depot.
    fun onProgress(
        depotId: Int,
        depotDone: Long,
        depotTotal: Long,
        depotsDone: Int,
        depotsTotal: Int,
        verifying: Boolean,
    )

    // Fired once when the whole download succeeds or fails.
    fun onComplete(
        success: Boolean,
        error: String,
        bytesWritten: Long,
        depotsCompleted: Int,
        depotsSkipped: Int,
    )
}
