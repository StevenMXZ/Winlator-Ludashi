package com.winlator.cmod.steam

import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

internal class SteamAppMetadata {
    private data class Key(val session: Any, val id: Int)
    private val entries = LinkedHashMap<Key, CompletableFuture<String>>(16, .75f, true)

    fun get(session: Any, id: Int, fetch: () -> JSONObject): JSONObject {
        val key = Key(session, id)
        val created = CompletableFuture<String>()
        val request = synchronized(entries) {
            entries[key] ?: created.also { entries[key] = it }
        }
        if (request === created) {
            try {
                request.complete(fetch().toString())
            } catch (error: Throwable) {
                synchronized(entries) { entries.remove(key) }
                request.completeExceptionally(error)
            } finally {
                synchronized(entries) {
                    val iterator = entries.iterator()
                    while (entries.size > 128 && iterator.hasNext()) {
                        if (iterator.next().value.isDone) iterator.remove()
                    }
                }
            }
        }
        return try { JSONObject(request.get()) }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        fun fetch(id: Int, app: (Int, Long) -> String?, tokens: (Int) -> String?): JSONObject {
            fun read(token: Long): JSONObject = JSONObject(app(id, token) ?: error("Steam metadata request timed out for $id. Try again.")).getJSONObject("appinfo")
            val public = read(0)
            if (public.has("config") && public.has("depots")) return public
            val access = tokens(id) ?: error("Steam access-token request timed out for $id. Try again.")
            val token = JSONObject(access).optJSONObject("appTokens")
                ?.optString(id.toString())?.toULongOrNull()?.toLong() ?: 0L
            return if (token != 0L) read(token) else public
        }
    }
}
