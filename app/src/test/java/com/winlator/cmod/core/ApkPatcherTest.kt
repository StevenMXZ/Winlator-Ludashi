package com.winlator.cmod.core

import org.junit.Assert.*
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class ApkPatcherTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun patch(old: ByteArray, fresh: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(92 + 34).order(ByteOrder.LITTLE_ENDIAN)
        header.put("BLDP".toByteArray()).putShort(1).putShort(0)
        header.putLong(old.size.toLong()).put(sha(old)).putLong(fresh.size.toLong()).put(sha(fresh)).putInt(2)
        header.put(1).putLong(0).putLong(old.size.toLong())
        header.put(2).putLong(1).putLong(0)
        val stream = ByteArrayOutputStream()
        stream.write(header.array())
        XZOutputStream(stream, LZMA2Options()).use { it.write(fresh.last().toInt()) }
        return stream.toByteArray()
    }
    @Test fun rebuildAndRejectTruncationWrongBaseAndCancellation() {
        val root = Files.createTempDirectory("apk-patch").toFile()
        val old = File(root, "old.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fresh = byteArrayOf(1, 2, 3, 4)
        val patch = File(root, "update.bldp").apply { writeBytes(patch(old.readBytes(), fresh)) }
        val out = File(root, "new.apk")
        assertTrue(ApkPatcher.apply(old, patch, out, AtomicBoolean(false), onProgress = { _, _ -> }))
        assertArrayEquals(fresh, out.readBytes())
        assertFalse(ApkPatcher.apply(old, patch, out, AtomicBoolean(true), onProgress = { _, _ -> }))
        assertFalse(out.exists())
        old.writeBytes(byteArrayOf(3, 2, 1))
        assertFalse(ApkPatcher.apply(old, patch, out, AtomicBoolean(false), onProgress = { _, _ -> }))
        assertFalse(out.exists())
        old.writeBytes(byteArrayOf(1, 2, 3))
        patch.writeBytes(patch.readBytes().dropLast(5).toByteArray())
        assertFalse(ApkPatcher.apply(old, patch, out, AtomicBoolean(false), onProgress = { _, _ -> }))
        assertFalse(out.exists())
        root.deleteRecursively()
    }
}
