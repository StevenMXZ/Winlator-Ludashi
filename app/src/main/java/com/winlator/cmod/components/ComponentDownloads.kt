package com.winlator.cmod.components

import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Downloads for the component catalog; keep its fraction progress separate from the old percent API. */
object ComponentDownloads {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    fun downloadString(url: String): String? = runCatching {
        require(url.startsWith("https://"))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            response.body?.string()
        }
    }.getOrNull()
    fun downloadFile(url: String, target: File, progress: (Float) -> Unit): Boolean = try {
        require(url.startsWith("https://"))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Empty response")
            target.parentFile?.mkdirs()
            body.byteStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var count: Int; var copied = 0L
                while (input.read(buffer).also { count = it } != -1) {
                    output.write(buffer, 0, count); copied += count
                    if (body.contentLength() > 0) progress(copied.toFloat() / body.contentLength())
                }
                check(body.contentLength() < 0 || copied == body.contentLength()) { "Incomplete download" }
            } }
        }
        progress(1f); true
    } catch (e: Exception) {
        target.delete(); android.util.Log.w("ComponentDownloads", "Download failed", e); false
    }
}
