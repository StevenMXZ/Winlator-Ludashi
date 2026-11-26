package com.winlator.cmod.core

import com.github.luben.zstd.ZstdInputStream
import org.tukaani.xz.XZInputStream
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.InflaterInputStream

/** LDU1 raw-byte content chunks, independent of APK ZIP layout. */
internal object ContentApkDelta {
    private const val LIMIT = 2L * 1024 * 1024 * 1024
    private const val CHUNK = 262144
    private val magic = byteArrayOf(76, 68, 85, 49)

    fun matches(patch: File): Boolean = patch.inputStream().use { it.readNBytesCompat(4).contentEquals(magic) }

    fun apply(old: File, patch: File, out: File, cancelled: AtomicBoolean,
              progress: (Long, Long) -> Unit): Boolean {
        var success = false
        try {
            DataInputStream(BufferedInputStream(patch.inputStream())).use { header ->
                check(header.readNBytesCompat(4).contentEquals(magic))
                val codec = header.readUnsignedByte()
                val oldSize = header.readLong()
                val targetSize = header.readLong()
                val oldHash = header.readNBytesCompat(32)
                val targetHash = header.readNBytesCompat(32)
                check(oldSize in 1..LIMIT && targetSize in 1..LIMIT && old.length() == oldSize)
                check(ApkPatcher.sha256(old).equals(oldHash.joinToString("") { "%02x".format(it.toInt() and 255) }, true))
                check((out.parentFile?.usableSpace ?: 0L) >= targetSize)
                val stream = when (codec) {
                    1 -> InflaterInputStream(header)
                    2 -> XZInputStream(header, 64 * 1024)
                    3 -> ZstdInputStream(header)
                    else -> error("Unsupported delta codec")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(CHUNK)
                var written = 0L
                var operations = 0
                DataInputStream(BufferedInputStream(stream)).use { commands ->
                    RandomAccessFile(old, "r").use { base ->
                        out.outputStream().buffered().use { target ->
                            while (true) {
                                check(!cancelled.get())
                                val kind = commands.readUnsignedByte()
                                if (kind == 0) break
                                check(++operations <= 1_000_000)
                                val offset = if (kind == 1) commands.readLong() else 0L
                                check(kind == 1 || kind == 2)
                                val size = commands.readInt()
                                check(size in 1..CHUNK && written <= targetSize - size)
                                if (kind == 1) {
                                    check(offset >= 0 && offset <= oldSize - size)
                                    base.seek(offset)
                                    base.readFully(buffer, 0, size)
                                } else commands.readFully(buffer, 0, size)
                                target.write(buffer, 0, size)
                                digest.update(buffer, 0, size)
                                written += size
                                progress(written, targetSize)
                            }
                            check(commands.read() == -1 && written == targetSize)
                        }
                    }
                }
                check(!cancelled.get() && digest.digest().contentEquals(targetHash))
                success = true
            }
        } catch (e: Exception) {
            android.util.Log.w("ContentApkDelta", "Incremental update rejected", e)
        } finally {
            if (!success) out.delete()
        }
        return success
    }

    private fun InputStream.readNBytesCompat(size: Int): ByteArray =
        ByteArray(size).also { DataInputStream(this).readFully(it) }
}
