package com.winlator.cmod.gog

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GogApiTest {
    @Test fun disabledIntegrationRejectsApiWorkBeforeAuthentication() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("gog-settings", 0)
        preferences.edit().putBoolean("enabled", false).commit()
        try {
            val error = assertThrows(IllegalStateException::class.java) { GogApi.get(context, "https://api.gog.com/products/1") }
            assertEquals("GOG integration is disabled", error.message)
        } finally { preferences.edit().clear().commit() }
    }
    @Test fun libraryLoadsEveryPageAndDeduplicatesProducts() {
        val pages = mutableListOf<Int>()
        val result = GogApi.libraryPages({ page ->
            pages.add(page)
            JSONObject(if (page == 1) """{"totalPages":2,"products":[{"id":12345678901,"title":"First","image":"//images.gog-statics.com/a.jpg"}]}"""
                else """{"totalPages":2,"products":[{"id":"12345678901","title":"First","image":""},{"id":2,"title":"Second","image":""}]}""")
        }, {})
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf("12345678901", "2"), result.map { it.id })
    }
    @Test fun partialOrMalformedLibraryIsNotReturnedAsComplete() {
        assertThrows(IllegalStateException::class.java) {
            GogApi.libraryPages({ page ->
                if (page == 2) error("network failed")
                JSONObject("""{"totalPages":2,"products":[{"id":1,"title":"First"}]}""")
            }, {})
        }
        assertThrows(Exception::class.java) { GogApi.libraryPages({ JSONObject("""{"totalPages":1}""") }, {}) }
    }
    @Test fun authenticatedRequestsOnlyTrustGogHosts() {
        assertTrue(GogApi.trusted("https://api.gog.com/products/1"))
        for (url in listOf("http://api.gog.com", "https://gog.com.evil.test/", "https://evilgog.com", "file:///a", "https://gog.com@evil.test/")) assertFalse(url, GogApi.trusted(url))
    }
    @Test fun callbackRequiresExactHostAndMatchingState() {
        assertEquals("accepted", GogApi.callback("https://embed.gog.com/on_login_success?code=accepted&state=expected", "expected"))
        assertNull(GogApi.callback("https://other.gog.com/on_login_success?code=no&state=expected", "expected"))
        assertThrows(IllegalStateException::class.java) { GogApi.callback("https://embed.gog.com/on_login_success?code=no&state=other", "expected") }
        assertThrows(IllegalStateException::class.java) { GogApi.callback("https://embed.gog.com/on_login_success?code=no", "expected") }
    }
    @Test fun onlyWindowsInstallersAreOffered() {
        val choices = GogApi.installers(JSONObject("""{"downloads":{"installers":[
          {"os":"windows","language":"en","files":[{"size":3}]},
          {"os":"linux","files":[{}]}, {"os":"windows","files":[]}, {"os":"osx","files":[{}]}
        ]}}"""))
        assertEquals(1, choices.size)
        assertEquals("en", choices.single().getString("language"))
    }
    @Test fun rejectsUnsafeFilenamesAndGameIds() {
        for (name in listOf("", ".", "..", "../x.exe", "a/b.bin", "a\\b.exe", "a\nexe"))
            assertThrows(IllegalArgumentException::class.java) { GogApi.safeFile(name) }
        assertEquals("setup_game-1.bin", GogApi.safeFile("setup_game-1.bin"))
        assertThrows(IllegalArgumentException::class.java) { GogGame.parse(JSONObject("""{"id":"../1","title":"Game"}""")) }
    }
    @Test fun filenamesSurviveSignedQueryAndPathDownloadLinks() {
        assertEquals("setup_game.exe", GogApi.fileName("https://cdn.gog.com/path/setup_game.exe?token=example"))
        assertEquals("setup_game-1.bin", GogApi.fileName("https://cdn.gog.com/download?path=%2Foffline%2Fsetup_game-1.bin&token=example"))
        assertThrows(IllegalArgumentException::class.java) { GogApi.fileName("https://cdn.gog.com/download?path=%2F") }
    }
    @Test fun normalizesGogImageHashesAndFormatterTemplates() {
        assertEquals("https://images.gog-statics.com/hash.jpg", GogGame.imageUrl("//images.gog-statics.com/hash"))
        assertEquals("https://images.gog-statics.com/hash.jpg?x=1", GogGame.imageUrl("//images.gog.com/hash?x=1"))
        assertEquals("https://images.gog-statics.com/hash_product_card_v2_mobile_slider_639.jpg", GogGame.imageUrl("https://images.gog-statics.com/hash_{formatter}.jpg"))
        assertEquals("https://images-2.gog-statics.com/hash.png", GogGame.imageUrl("//images-2.gog-statics.com/hash.png"))
        assertEquals("", GogGame.imageUrl("http://images.gog-statics.com/hash"))
    }
    @Test fun transportAndChecksumSizesAreExactWhileCatalogSizesAreEstimates() {
        val catalogEstimate = 1048576L
        val realLength = 865432L
        assertNotEquals(catalogEstimate, GogApi.verifiedLength(realLength, GogApi.Integrity()))
        assertEquals(realLength, GogApi.verifiedLength(realLength, GogApi.Integrity(realLength, "900150983cd24fb0d6963f7d28e17f72")))
        assertThrows(IllegalStateException::class.java) { GogApi.verifiedLength(realLength, GogApi.Integrity(realLength + 1)) }
        assertThrows(IllegalStateException::class.java) { GogApi.verifiedLength(-1, GogApi.Integrity()) }
    }
    @Test fun checksumXmlSuppliesExactFileSizeAndDigest() {
        val info = GogApi.checksum("""<?xml version="1.0"?><file name="setup_game.exe" total_size="865432" md5="900150983cd24fb0d6963f7d28e17f72"><chunk from="0" to="865431" /></file>""")
        assertEquals(865432L, info.size)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", info.md5)
        assertThrows(IllegalArgumentException::class.java) { GogApi.checksum("""<file total_size="0" md5="invalid"/>""") }
    }
    @Test fun downloadRequiresCompleteLengthAndMatchingChecksum() {
        val directory = Files.createTempDirectory("gog-download-test").toFile()
        val file = directory.resolve("installer.pending")
        try {
            val data = "abc".toByteArray()
            GogApi.receive(data.inputStream(), file, 3, "900150983cd24fb0d6963f7d28e17f72", { false }, {})
            assertArrayEquals(data, file.readBytes())
            assertThrows(IllegalStateException::class.java) { GogApi.receive(data.inputStream(), file, 4, null, { false }, {}) }
            assertFalse(file.exists())
            assertThrows(IllegalStateException::class.java) { GogApi.receive(data.inputStream(), file, 3, "00000000000000000000000000000000", { false }, {}) }
            assertFalse(file.exists())
            assertThrows(IllegalStateException::class.java) { GogApi.receive(data.inputStream(), file, 3, null, { true }, {}) }
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun pausedBytesAreRetainedAndResumedWithFullChecksumValidation() {
        val directory = Files.createTempDirectory("gog-pause").toFile()
        val file = directory.resolve("installer.pending")
        try {
            var paused = false
            assertThrows(IllegalStateException::class.java) {
                GogApi.receive("a".byteInputStream(), file, 3, null, { paused }, { paused = true })
            }
            assertEquals("a", file.readText())
            GogApi.receive("bc".byteInputStream(), file, 3, "900150983cd24fb0d6963f7d28e17f72", { false }, {}, 1)
            assertEquals("abc", file.readText())
            file.writeText("z")
            assertThrows(IllegalStateException::class.java) {
                GogApi.receive("bc".byteInputStream(), file, 3, "900150983cd24fb0d6963f7d28e17f72", { false }, {}, 1)
            }
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}
