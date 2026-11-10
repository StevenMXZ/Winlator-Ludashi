package com.winlator.cmod.steam

import java.util.concurrent.CompletableFuture

class SteamGuardPrompt {
    @Volatile var label = ""
        private set
    private var pending: CompletableFuture<String>? = null
    @Synchronized fun request(label: String): CompletableFuture<String> {
        cancel()
        this.label = label
        return CompletableFuture<String>().also { pending = it }
    }
    @Synchronized fun submit(code: String): Boolean {
        if (code.isBlank()) return false
        val future = pending ?: return false
        pending = null; label = ""
        return future.complete(code.trim())
    }
    @Synchronized fun cancel() { pending?.complete(""); pending = null; label = "" }
}
