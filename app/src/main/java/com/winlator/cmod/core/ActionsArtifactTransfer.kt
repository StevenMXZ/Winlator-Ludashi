package com.winlator.cmod.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object ActionsArtifactTransfer {
    private class RangeUnsupported : IOException("Server did not honor the requested range")
    internal data class Span(val start: Long, val end: Long) { val length get() = end - start + 1 }
    internal fun spans(size: Long, count: Int): List<Span> {
        require(size > 0 && count > 0)
        val n = minOf(size, count.toLong()).toInt()
        return (0 until n).map { Span(size * it / n, size * (it + 1) / n - 1) }
    }
    internal fun rangeMatches(header: String?, start: Long, end: Long, total: Long): Boolean =
        header == "bytes $start-$end/$total"
    private fun request(url: String, range: String? = null): Request =
        Request.Builder().url(url).header("User-Agent", "Winlator-Ludashi-Updater")
            .header("Accept-Encoding", "identity").apply { if (range != null) header("Range", range) }.build()
    private fun active(cancelled: AtomicBoolean) = check(!cancelled.get()) { "Cancelled" }

    fun download(client: OkHttpClient, url: String, size: Long, work: File, zip: File,
                 cancelled: AtomicBoolean, progress: (Long, Long) -> Unit) {
        active(cancelled)
        if (zip.length() == size) { progress(size, size); return }
        val resolved = client.newCall(request(url, "bytes=0-0")).execute().use { response ->
            if (response.code == 206 && rangeMatches(response.header("Content-Range"), 0, 0, size))
                response.request.url.toString() else null
        }
        if (resolved == null) {
            work.listFiles()?.filter { it.name.startsWith("part-") }?.forEach { it.delete() }
            zip.delete()
            full(client, url, size, zip, cancelled, progress)
            return
        }
        zip.delete()
        val ranges = spans(size, if (size >= 8 * 1024 * 1024) 4 else 1)
        val files = ranges.indices.map { File(work, "part-$it") }
        val lengths = LongArray(ranges.size) { i ->
            if (files[i].length() > ranges[i].length) files[i].delete()
            files[i].length()
        }
        val lock = Any()
        fun report(i: Int, length: Long) = synchronized(lock) {
            lengths[i] = length
            progress(lengths.sum(), size)
        }
        progress(lengths.sum(), size)
        val pool = Executors.newFixedThreadPool(ranges.size)
        val stop = AtomicBoolean(false)
        try {
            val jobs = ranges.indices.map { i -> Callable<Exception?> {
                try {
                    val span = ranges[i]
                    val file = files[i]
                    var failures = 0
                    while (file.length() < span.length) {
                        active(cancelled)
                        check(!stop.get()) { "Transfer stopped" }
                        val offset = file.length()
                        val start = span.start + offset
                        try {
                            client.newCall(request(resolved, "bytes=" + start + "-" + span.end)).execute().use { response ->
                                if (response.code == 200 || (response.code == 206 &&
                                        !rangeMatches(response.header("Content-Range"), start, span.end, size)))
                                    throw RangeUnsupported()
                                if (response.code != 206) throw IOException("Artifact range failed (HTTP " + response.code + ")")
                                val body = response.body ?: throw IOException("Empty range")
                                val expected = span.length - offset
                                if (body.contentLength() >= 0 && body.contentLength() != expected) throw RangeUnsupported()
                                body.byteStream().use { input ->
                                    FileOutputStream(file, true).use { output ->
                                        val buffer = ByteArray(128 * 1024)
                                        var copied = offset
                                        while (true) {
                                            active(cancelled)
                                            check(!stop.get()) { "Transfer stopped" }
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            if (copied + n > span.length) throw RangeUnsupported()
                                            output.write(buffer, 0, n)
                                            copied += n
                                            report(i, copied)
                                        }
                                        if (copied != span.length) throw IOException("Incomplete range")
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            if (e is RangeUnsupported || ++failures >= 3) throw e
                        }
                    }
                    null
                } catch (e: Exception) {
                    stop.set(true)
                    e
                }
            } }
            val failures = pool.invokeAll(jobs).mapNotNull { it.get() }
            active(cancelled)
            if (failures.any { it is RangeUnsupported }) {
                files.forEach { it.delete() }
                full(client, url, size, zip, cancelled, progress)
                return
            }
            if (failures.isNotEmpty()) throw failures.firstOrNull { it is IOException } ?: failures.first()
            zip.outputStream().use { output ->
                files.forEach { file ->
                    active(cancelled)
                    file.inputStream().use { it.copyTo(output, 128 * 1024) }
                }
            }
            check(zip.length() == size) { "Incomplete artifact" }
            files.forEach { it.delete() }
        } finally { pool.shutdownNow() }
    }

    private fun full(client: OkHttpClient, url: String, size: Long, zip: File,
                     cancelled: AtomicBoolean, progress: (Long, Long) -> Unit) {
        progress(0, size)
        client.newCall(request(url)).execute().use { response ->
            check(response.code == 200) { "Artifact download failed (HTTP " + response.code + ")" }
            val body = response.body ?: error("Empty artifact")
            check(body.contentLength() < 0 || body.contentLength() == size) { "Artifact size changed" }
            body.byteStream().use { input ->
                zip.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    while (true) {
                        active(cancelled)
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(copied + n <= size) { "Artifact exceeds expected size" }
                        output.write(buffer, 0, n)
                        copied += n
                        progress(copied, size)
                    }
                    check(copied == size) { "Incomplete artifact" }
                }
            }
        }
    }
}
