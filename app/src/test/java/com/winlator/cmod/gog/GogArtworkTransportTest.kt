package com.winlator.cmod.gog

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GogArtworkTransportTest {
    @Test fun artworkAcceptsChunkedImagesButRejectsTruncatedTransfers() {
        val png = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        val directory = Files.createTempDirectory("gog-artwork").toFile()
        val target = directory.resolve("cover.pending")
        try {
            GogApi.receiveArtwork(png.inputStream(), target, -1) { true }
            assertArrayEquals(png, target.readBytes())
            assertThrows(IllegalStateException::class.java) { GogApi.receiveArtwork(png.inputStream(), target, png.size.toLong() + 1) { true } }
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancelledArtworkNeverWritesTheBlockThatArrivedAfterCancellation() {
        var active = true
        val input = object : java.io.ByteArrayInputStream(ByteArray(100)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length); active = false; return count
            }
        }
        val target = Files.createTempFile("gog-artwork-cancel", ".pending").toFile()
        try {
            assertThrows(IllegalStateException::class.java) { GogApi.receiveArtwork(input, target, 100) { active } }
            assertEquals(0L, target.length())
        } finally { target.delete() }
    }
}
