package com.winlator.cmod.steam

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamLibraryCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun persistedCacheIsScopedToAccountAndPreservesUnsignedTokens() {
        val root = temporary.newFolder()
        val cache = SteamLibraryCache(root)
        val metadata = """{"access_token":18446744073709551615}"""
        cache.write(100, """{"owned_apps":[{"id":42}]}""", metadata, 1000)
        val restored = SteamLibraryCache(root).read(100)!!
        assertEquals(metadata, restored.metadata)
        assertTrue(restored.snapshot.contains("42"))
        assertNull(cache.read(101))
        assertNull(cache.read(0))
        File(root, "101.json").writeText(File(root, "100.json").readText())
        assertNull(cache.read(101))
    }
    @Test fun malformedCacheFallsBackAndMetadataExpiresWithoutHidingTheList() {
        val root = temporary.newFolder()
        File(root, "100.json").writeText("truncated {")
        val cache = SteamLibraryCache(root)
        assertNull(cache.read(100))
        cache.write(100, """{"owned_apps":[]}""", "{}", 1000)
        val restored = cache.read(100)!!
        assertTrue(restored.fresh(1000))
        assertFalse(restored.fresh(999))
        assertFalse(restored.fresh(1000 + 6 * 60 * 60 * 1000L))
    }
}
