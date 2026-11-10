package com.winlator.cmod.steam

/** Callback-only payload throughput. Verified/reused bytes advance progress but never inflate speed. */
internal class SteamDownloadMeter(private val order: List<Int>, initialSizes: Map<Int, Long>) {
    data class Reading(val done: Long, val total: Long, val bytesPerSecond: Long, val etaSeconds: Long?, val verifying: Boolean)
    private val sizes = initialSizes.toMutableMap()
    private val done = mutableMapOf<Int, Long>()
    private var depot = -1
    private var lastDone = 0L
    private var windowStart = -1L
    private var networkBytes = 0L
    private var rate = 0.0
    private var lastTransfer = -1L
    private var verifying = false
    @Synchronized fun update(id: Int, bytes: Long, total: Long, completed: Int, verifying: Boolean, now: Long) {
        if (windowStart < 0) windowStart = now
        sizes[id] = total.coerceAtLeast(0)
        for (index in 0 until completed.coerceIn(0, order.size)) { val previous = order[index]; done[previous] = sizes[previous] ?: 0 }
        done[id] = bytes.coerceIn(0, total.coerceAtLeast(0))
        if (depot == id && !verifying && bytes > lastDone) { networkBytes += bytes-lastDone; lastTransfer = now }
        depot = id; lastDone = bytes; this.verifying = verifying
        val elapsed = now-windowStart
        if (elapsed >= 500) {
            val sampled = networkBytes.toDouble()*1000/elapsed
            rate = if (rate == 0.0) sampled else rate*.65+sampled*.35
            networkBytes = 0; windowStart = now
        }
    }
    @Synchronized fun read(now: Long): Reading {
        val known = order.isNotEmpty() && order.all { (sizes[it] ?: 0) > 0 }
        val total = if (known) order.sumOf { sizes.getValue(it) } else sizes[depot] ?: 0
        val current = if (known) done.values.sum().coerceAtMost(total) else done[depot] ?: 0
        val speed = if (lastTransfer < 0 || now-lastTransfer > 4000 || verifying) 0 else rate.toLong().coerceAtLeast(0)
        val eta = if (known && speed > 0) kotlin.math.ceil((total-current).toDouble()/speed).toLong().coerceAtLeast(0) else null
        return Reading(current, total, speed, eta, verifying)
    }
}
