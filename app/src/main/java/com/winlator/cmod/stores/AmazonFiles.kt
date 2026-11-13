package com.winlator.cmod.stores

import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object AmazonFiles {
    data class Entry(val path: String, val size: Long, val algorithm: Int, val hash: ByteArray) {
        val hex get() = hash.joinToString("") { "%02x".format(it) }
    }
    private class Reader(val bytes: ByteArray) {
        var offset = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                check(offset < bytes.size) { "Truncated Amazon manifest" }
                val byte = bytes[offset++].toInt() and 255
                check(shift < 63 || byte <= 1) { "Invalid Amazon manifest integer" }
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            error("Amazon manifest integer overflow")
        }
        fun block(): ByteArray {
            val size = varint()
            require(size >= 0 && size <= bytes.size - offset) { "Invalid Amazon manifest field length" }
            return bytes.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
        }
        fun skip(type: Int) {
            when (type) {
                0 -> varint()
                2 -> block()
                1, 5 -> { val size = if (type == 1) 8 else 4; check(offset <= bytes.size - size); offset += size }
                else -> error("Unsupported Amazon manifest wire type")
            }
        }
        fun fields(action: (Int, Int, Reader) -> Unit) {
            while (offset < bytes.size) {
                val tag = varint(); require(tag > 0)
                action((tag ushr 3).toInt(), (tag and 7).toInt(), this)
            }
        }
    }
    fun parse(content: ByteArray): List<Entry> {
        require(content.size >= 4)
        val size = ByteBuffer.wrap(content, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        require(size >= 0 && size <= content.size - 4)
        var compression = 0
        Reader(content.copyOfRange(4, 4 + size)).fields { field, wire, reader ->
            if (field == 1 && wire == 2) Reader(reader.block()).fields { f, w, r -> if (f == 1 && w == 0) compression = r.varint().toInt() else r.skip(w) }
            else reader.skip(wire)
        }
        val body = content.copyOfRange(4 + size, content.size)
        val raw = when (compression) {
            0 -> body
            1 -> {
                val source = ByteArrayInputStream(body)
                val input = if (body.size >= 6 && body[0] == 0xfd.toByte() && body[1] == '7'.code.toByte()) XZInputStream(source, 65536) else LZMAInputStream(source, 65536)
                input.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = stream.read(buffer); if (n < 0) break
                        require(output.size().toLong() + n <= 128L * 1024 * 1024) { "Amazon manifest is too large" }
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
            }
            else -> error("Unsupported Amazon manifest compression")
        }
        val files = linkedMapOf<String, Entry>()
        Reader(raw).fields { field, wire, manifest ->
            if (field != 1 || wire != 2) manifest.skip(wire)
            else Reader(manifest.block()).fields { f, w, pkg ->
                if (f != 2 || w != 2) pkg.skip(w)
                else {
                    var path = ""; var length = 0L; var algorithm = 0; var hash = byteArrayOf()
                    Reader(pkg.block()).fields { ff, ww, file -> when {
                        ff == 1 && ww == 2 -> path = String(file.block(), Charsets.UTF_8).replace('\\', '/')
                        ff == 3 && ww == 0 -> length = file.varint()
                        ff == 5 && ww == 2 -> Reader(file.block()).fields { hf, hw, h -> when {
                            hf == 1 && hw == 0 -> algorithm = h.varint().toInt()
                            hf == 2 && hw == 2 -> hash = h.block()
                            else -> h.skip(hw)
                        } }
                        else -> file.skip(ww)
                    } }
                    require(length >= 0 && path.isNotBlank() && algorithm in 0..1)
                    require(if (algorithm == 0) hash.size == 32 else hash.size in 16..64) { "Invalid Amazon manifest hash" }
                    safeStorePath(java.io.File("/ludashi-manifest"), path)
                    val entry = Entry(path, length, algorithm, hash)
                    val previous = files[path.lowercase()]
                    require(previous == null || previous.size == length && previous.algorithm == algorithm && previous.hash.contentEquals(hash)) { "Conflicting Amazon manifest files" }
                    files[path.lowercase()] = entry
                }
            }
        }
        require(files.isNotEmpty()) { "Amazon manifest contained no files" }
        return files.values.toList()
    }
}
