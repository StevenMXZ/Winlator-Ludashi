package com.winlator.cmod.core

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rebuilds the new APK from the installed one plus a BLDP v1 patch (small in-app updates).
 * The format and the maker live in scripts/apk_delta.py; release.yml checks every patch it
 * publishes by applying it back. COPY ops take raw bytes from the installed APK, LITERAL ops
 * take the next bytes of the patch's XZ stream. Nothing is recompressed, so the result is the
 * release APK byte for byte, signature included. Streams end to end: memory use does not grow
 * with the APK.
 */
object ApkPatcher {
    private const val HEADER_SIZE = 92
    private const val OP_SIZE = 17
    private const val COPY = 1
    private const val LITERAL = 2
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte())

    class Header(val oldSize: Long, val oldSha256: String, val newSize: Long, val newSha256: String, val opCount: Int)

    fun readHeader(input: InputStream): Header {
        val b = ByteBuffer.wrap(readFully(input, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        if (!magic.contentEquals(MAGIC)) throw IOException("not a BLDP patch")
        val version = b.short.toInt()
        if (version != 1) throw IOException("BLDP version $version is not supported")
        b.short  // flags
        val oldSize = b.long
        val oldSha = ByteArray(32).also { b.get(it) }
        val newSize = b.long
        val newSha = ByteArray(32).also { b.get(it) }
        val ops = b.int
        if (oldSize < 0 || newSize <= 0) throw IOException("bad APK size")
        if (ops < 0 || ops > 1_000_000) throw IOException("bad op count")
        return Header(oldSize, hex(oldSha), newSize, hex(newSha), ops)
    }

    /**
     * Writes the rebuilt APK to [out] and returns true only when its sha256 is the one the patch
     * names. [onProgress] gets bytes written so far and the final size. Any failure or a set
     * [interrupt] deletes [out]; the caller then falls back to the full download. [onError] gets
     * the reason (this file stays free of Android classes so CI can run it on the JVM).
     */
    fun apply(
        old: File, patch: File, out: File, interrupt: AtomicBoolean,
        onError: (String) -> Unit = {}, onProgress: (Long, Long) -> Unit,
    ): Boolean {
        var ok = false
        try {
            BufferedInputStream(FileInputStream(patch), 1 shl 16).use { p ->
                val h = readHeader(p)
                if (old.length() != h.oldSize) throw IOException("installed APK is not the one this patch was made from")
                if (sha256(old) != h.oldSha256) throw IOException("installed APK checksum does not match patch")
                val ops = ByteBuffer.wrap(readFully(p, h.opCount * OP_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
                val lit = XZInputStream(p, 64 * 1024)
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 20)
                var written = 0L
                var lastReport = 0L
                RandomAccessFile(old, "r").use { src ->
                    FileOutputStream(out).use { dst ->
                        repeat(h.opCount) {
                            val kind = ops.get().toInt()
                            val a = ops.long
                            val b = ops.long
                            var left = when (kind) {
                                COPY -> { src.seek(a); b }
                                LITERAL -> a
                                else -> throw IOException("unknown op $kind")
                            }
                            if (left < 0 || written > h.newSize - left) throw IOException("patch operation exceeds output")
                            if (kind == COPY && (a < 0 || a > h.oldSize - b)) throw IOException("copy exceeds installed APK")
                            while (left > 0) {
                                if (interrupt.get()) throw IOException("cancelled")
                                val want = minOf(left, buf.size.toLong()).toInt()
                                if (kind == COPY) src.readFully(buf, 0, want) else readFully(lit, buf, want)
                                dst.write(buf, 0, want)
                                digest.update(buf, 0, want)
                                left -= want
                                written += want
                                if (written - lastReport >= (8 shl 20)) {
                                    lastReport = written
                                    onProgress(written, h.newSize)
                                }
                            }
                        }
                    }
                }
                if (lit.read() != -1) throw IOException("unused literal bytes")
                val sha = hex(digest.digest())
                ok = written == h.newSize && sha == h.newSha256
                if (ok) onProgress(written, h.newSize)
                else onError("rebuilt $written bytes, sha256 $sha; patch names ${h.newSize} bytes, ${h.newSha256}")
            }
        } catch (e: Exception) {
            ok = false
            onError(e.toString())
        }
        if (!ok) out.delete()
        return ok
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun readFully(input: InputStream, n: Int): ByteArray = ByteArray(n).also { readFully(input, it, n) }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("patch is truncated")
            off += r
        }
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }
}
