package com.winlator.cmod.steam

/** Matches ordered relay acknowledgments without overwriting rapid sends to the same friend. */
internal class SteamPendingMessages {
    private val messages = mutableMapOf<Long, java.util.ArrayDeque<String>>()
    @Synchronized fun add(id: Long, text: String): Boolean {
        if (messages.values.sumOf { it.size } >= 64) return false
        messages.getOrPut(id) { java.util.ArrayDeque() }.addLast(text)
        return true
    }
    @Synchronized fun acknowledge(id: Long): String? {
        val queue = messages[id] ?: return null
        val text = queue.pollFirst()
        if (queue.isEmpty()) messages.remove(id)
        return text
    }
    @Synchronized fun clear() { messages.clear() }
}
