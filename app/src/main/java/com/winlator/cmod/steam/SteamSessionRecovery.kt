package com.winlator.cmod.steam

/** Bounded reconnects; a brief successful logon must not reset an endless retry loop. */
class SteamSessionRecovery {
    private var attempts = 0
    val attemptNumber get() = attempts
    private var deadline = Long.MAX_VALUE
    private var stableSince: Long? = null
    private var wasLoggedOn = false
    fun reset() { attempts = 0; deadline = Long.MAX_VALUE; stableSince = null; wasLoggedOn = false }
    fun began(now: Long) { attempts++; deadline = now + 30_000; stableSince = null; wasLoggedOn = false }
    fun observe(state: Int, now: Long) {
        if (state == 3) {
            val since = stableSince ?: now.also { stableSince = it }
            if (now - since >= 60_000) attempts = 0
            wasLoggedOn = true
        } else {
            stableSince = null
            if (wasLoggedOn) { deadline = now + 2_000; wasLoggedOn = false }
        }
    }
    fun shouldRetry(now: Long) = !wasLoggedOn && attempts < 3 && now >= deadline
    fun exhausted(now: Long) = !wasLoggedOn && attempts >= 3 && now >= deadline
}

object SteamLogonResult {
    fun code(body: ByteArray, fallback: Int): Int? = runCatching {
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(offset < body.size)
                val byte = body[offset++].toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            error("Invalid varint")
        }
        while (offset < body.size) {
            val tag = varint().toInt()
            if (tag == 8) return@runCatching varint().toInt()
            when (tag and 7) {
                0 -> varint()
                1 -> offset += 8
                2 -> { val size = varint(); require(size in 0..body.size.toLong()); offset += size.toInt() }
                5 -> offset += 4
                else -> error("Invalid wire type")
            }
            require(offset <= body.size)
        }
        fallback.takeIf { it > 0 }
    }.getOrNull()
    fun requiresNewLogin(code: Int?) = code in setOf(5, 15, 18, 65)
}

/** Native connect starts a transport; token logon requires its Connected callback. */
internal class SteamConnectionGate {
    private val signal = java.util.concurrent.CountDownLatch(1)
    @Volatile private var state = 1
    fun observe(value: Int) { state = value; if (value == 0 || value >= 2) signal.countDown() }
    fun awaitConnected(timeoutMs: Long): Boolean = signal.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && state >= 2
}
