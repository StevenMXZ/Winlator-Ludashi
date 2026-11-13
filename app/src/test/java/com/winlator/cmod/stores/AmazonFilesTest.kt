package com.winlator.cmod.stores

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.tukaani.xz.XZOutputStream
import org.tukaani.xz.LZMA2Options

class AmazonFilesTest {
    private fun integer(value: Long): ByteArray {
        var n = value; val bytes = ByteArrayOutputStream()
        do { var b = (n and 127).toInt(); n = n ushr 7; if (n != 0L) b = b or 128; bytes.write(b) } while (n != 0L)
        return bytes.toByteArray()
    }
    private fun field(id: Int, bytes: ByteArray) = integer((id * 8 + 2).toLong()) + integer(bytes.size.toLong()) + bytes
    private fun number(id: Int, n: Long) = integer((id * 8).toLong()) + integer(n)
    private fun raw(path: String = "Game/game.exe", algorithm: Int = 0, size: Long? = 123): ByteArray {
        val digest = field(2, ByteArray(if (algorithm == 0) 32 else 20) { it.toByte() }) + if (algorithm == 0) byteArrayOf() else number(1, algorithm.toLong())
        val file = field(1, path.toByteArray()) + (size?.let { number(3, it) } ?: byteArrayOf()) + field(5, digest)
        return field(1, field(1, "game".toByteArray()) + field(2, file))
    }
    private fun manifest(bytes: ByteArray, compressed: Boolean = false): ByteArray {
        val header = if (compressed) field(1, number(1, 1)) else byteArrayOf()
        val body = if (compressed) ByteArrayOutputStream().also { out -> XZOutputStream(out, LZMA2Options()).use { it.write(bytes) } }.toByteArray() else bytes
        return ByteBuffer.allocate(4).putInt(header.size).array() + header + body
    }
    @Test fun parsesUncompressedAndCompressedManifestsWithDefaultHashEnum() {
        for (compressed in listOf(false, true)) {
            val files = AmazonFiles.parse(manifest(raw(), compressed))
            assertEquals("Game/game.exe", files.single().path)
            assertEquals(123L, files.single().size)
            assertEquals(32, files.single().hash.size)
            assertEquals(0, files.single().algorithm)
        }
    }
    @Test fun omittedZeroSizeFollowsProtobufDefaults() {
        assertEquals(0L, AmazonFiles.parse(manifest(raw(size = null))).single().size)
    }
    @Test fun acceptsShake128HashesAndWindowsSeparators() {
        val file = AmazonFiles.parse(manifest(raw("Game\\game.exe", 1))).single()
        assertEquals("Game/game.exe", file.path)
        assertEquals(1, file.algorithm)
        assertEquals(20, file.hash.size)
    }
    @Test fun rejectsTruncatedAndUnsafeManifests() {
        val complete = manifest(raw())
        for (length in listOf(0, 3, 5, complete.size - 1)) assertThrows(Exception::class.java) { AmazonFiles.parse(complete.copyOf(length)) }
        for (path in listOf("../game.exe", "/game.exe", "C:\\game.exe", "a/../../game.exe")) assertThrows(IllegalArgumentException::class.java) { AmazonFiles.parse(manifest(raw(path))) }
        assertThrows(Exception::class.java) { AmazonFiles.parse(byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f)) }
    }
    @Test fun signedCdnQueryIsPreservedWhenAddressingManifestObjects() {
        assertEquals("https://cdn.test/game/files/abc?signature=example", AmazonConnector.append("https://cdn.test/game?signature=example", "files/abc"))
    }
    @Test fun windowsPathsStayInsideTheStagingDirectory() {
        val root = java.io.File("/tmp/store-path-test")
        assertEquals(java.io.File(root, "Data/game.exe"), safeStorePath(root, "Data\\game.exe"))
        for (name in listOf("../x", "/x", "C:\\x", "Data/../../x")) assertThrows(IllegalArgumentException::class.java) { safeStorePath(root, name) }
    }
}
