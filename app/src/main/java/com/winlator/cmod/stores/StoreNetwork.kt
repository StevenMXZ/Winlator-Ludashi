package com.winlator.cmod.stores

import android.content.Context
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object StoreNetwork {
    private val clients = ConcurrentHashMap<LudashiStore, OkHttpClient>()
    private val calls = ConcurrentHashMap<LudashiStore, MutableSet<Call>>()
    private val closing = java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work, "ludashi-store-close").apply { isDaemon = true } }
    internal fun closeOffMain(work: () -> Unit) { closing.execute { runCatching(work).onFailure { android.util.Log.w("Stores", "Could not close a store connection", it) } } }
    fun stopIfIdle(store: LudashiStore) {
        if (calls[store].isNullOrEmpty()) clients[store]?.let { client -> closeOffMain { client.connectionPool.evictAll() } }
    }
    fun stop(store: LudashiStore) {
        val active = calls[store]?.toList().orEmpty()
        val client = clients.remove(store)
        if (active.isNotEmpty() || client != null) closeOffMain { active.forEach { runCatching { it.cancel() } }; client?.connectionPool?.evictAll() }
    }
    fun stopTagged(store: LudashiStore, tag: String) {
        val active = calls[store]?.filter { it.request().tag() == tag }.orEmpty()
        if (active.isNotEmpty()) closeOffMain { active.forEach { runCatching { it.cancel() } } }
    }
    fun check(context: Context, store: LudashiStore) {
        check(store.enabled(context) && !StoreSessions.paused && StoreSessions.live(store)) { "Store operation cancelled" }
        if (Thread.currentThread().isInterrupted) error("Store operation interrupted")
    }
    fun trustedItch(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && (uri.host == "itch.io" || uri.host?.endsWith(".itch.io") == true)
    }.getOrDefault(false)
    fun <T> request(context: Context, store: LudashiStore, request: Request, block: (Response) -> T): T {
        check(context, store)
        var next = request
        repeat(10) {
            require(next.url.isHttps) { "Store requests require HTTPS" }
            val client = clients.computeIfAbsent(store) { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build() }
            if (store == LudashiStore.ITCH && next.url.host != "api.itch.io" && trustedItch(next.url.toString())) {
                val cookies = ItchCookies.header(context, next.url)
                val builder = next.newBuilder().removeHeader("Cookie")
                if (cookies.isNotBlank()) builder.header("Cookie", cookies)
                next = builder.build()
            }
            if (store == LudashiStore.ITCH && next.url.host == "api.itch.io") {
                val key = ItchApi.key(context)
                if (key.isNotBlank() && next.header("Authorization") == null) next = next.newBuilder().header("Authorization", "Bearer $key").build()
            }
            val call = client.newCall(next)
            val active = calls.computeIfAbsent(store) { ConcurrentHashMap.newKeySet() }
            active.add(call)
            try {
                check(context, store)
                call.execute().use { response ->
                    if (store == LudashiStore.ITCH && next.url.host != "api.itch.io" && trustedItch(next.url.toString())) ItchCookies.accept(context, next.url, response.headers)
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location") ?: error("Store returned an invalid redirect")
                        val url = next.url.resolve(location) ?: error("Store returned an invalid redirect URL")
                        require(url.isHttps)
                        val builder = next.newBuilder().url(url)
                        if (url.host != next.url.host) builder.removeHeader("Authorization").removeHeader("Cookie").removeHeader("x-amzn-token")
                        if (response.code == 303 || response.code in listOf(301, 302) && next.method == "POST") builder.get()
                        next = builder.build()
                    } else {
                        check(response.isSuccessful) { "${store.title} request failed (HTTP ${response.code})" }
                        return block(response)
                    }
                }
            } finally { active.remove(call) }
            check(context, store)
        }
        error("Too many store redirects")
    }
    fun json(context: Context, store: LudashiStore, url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        val builder = Request.Builder().url(url).header("User-Agent", "Ludashi/5.0")
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return request(context, store, builder.build()) { JSONObject(it.body?.string() ?: error("Empty store response")) }
    }
    fun bytes(context: Context, store: LudashiStore, url: String, max: Int = 64 * 1024 * 1024): ByteArray =
        request(context, store, Request.Builder().url(url).build()) { response ->
            val bytes = java.io.ByteArrayOutputStream()
            response.body!!.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(context, store)
                    val n = input.read(buffer); if (n < 0) break
                    check(bytes.size().toLong() + n <= max) { "Store metadata is too large" }
                    bytes.write(buffer, 0, n)
                }
            }
            bytes.toByteArray()
        }
    fun transfer(context: Context, store: LudashiStore, url: String, destination: File, expected: Long = -1,
        archiveValidated: Boolean = false, progress: (Long) -> Unit) {
        val rate = StoreDownloadPreferences.limit(context, store.id)
        var failure: Exception? = null
        for (attempt in 0..2) {
            check(context, store)
            try {
                request(context, store, Request.Builder().url(url).header("Accept-Encoding", "identity").build()) { response ->
                    val transport = response.body?.contentLength() ?: -1L
                    if (expected >= 0 && transport >= 0) check(expected == transport) { "Store download length does not match its manifest" }
                    val required = if (expected >= 0) expected else transport
                    check(required >= 0 || archiveValidated) { "Download has no verifiable length" }
                    destination.parentFile?.mkdirs()
                    var done = 0L
                    val pacer = TransferPacer(rate)
                    response.body!!.byteStream().use { input -> destination.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            check(context, store)
                            val count = input.read(buffer); if (count < 0) break
                            output.write(buffer, 0, count)
                            done += count; pacer.pace(done) { check(context, store) }; progress(done)
                        }
                        output.fd.sync()
                    } }
                    check(required < 0 || done == required) { "Incomplete store download" }
                }
                return
            } catch (error: Exception) { destination.delete(); failure = error; check(context, store) }
        }
        throw failure ?: IllegalStateException("Store download failed")
    }
}
