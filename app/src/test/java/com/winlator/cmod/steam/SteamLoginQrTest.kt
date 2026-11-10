package com.winlator.cmod.steam

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamLoginQrTest {
    @Test fun renderedQrDecodesToExactSteamChallengeIncludingRotation() {
        for (url in listOf("https://s.team/q/1/1234567890", "https://s.team/q/1/9876543210")) {
            val bitmap = encodeSteamQr(url)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
            assertEquals(url, decoded.text)
        }
    }
    @Test fun rejectsNonSteamChallenge() {
        assertThrows(IllegalArgumentException::class.java) { encodeSteamQr("https://example.com/login") }
    }
}
