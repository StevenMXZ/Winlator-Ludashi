package com.winlator.cmod.steam

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamAppMetadataTest {
    @Test fun retriesIncompletePublicMetadataWithUnsignedAccountToken() {
        val calls = mutableListOf<Long>()
        val result = SteamAppMetadata.fetch(204360, { id, token ->
            assertEquals(204360, id)
            calls.add(token)
            if (token == 0L) """{"appinfo":{"common":{"name":"Castle Crashers"}}}"""
            else """{"appinfo":{"config":{"installdir":"Castle Crashers"},"depots":{"204361":{}}}}"""
        }, { """{"appTokens":{"204360":"18446744073709551615"}}""" })
        assertEquals(listOf(0L, -1L), calls)
        assertEquals("Castle Crashers", result.getJSONObject("config").getString("installdir"))
        assertTrue(result.has("depots"))
    }
    @Test fun completePublicMetadataDoesNotRequestToken() {
        SteamAppMetadata.fetch(1, { _, _ -> """{"appinfo":{"config":{},"depots":{}}}""" }, { error("Unexpected token request") })
    }
    @Test fun cacheIsIsolatedBySessionAndReturnsIndependentObjects() {
        val cache = SteamAppMetadata()
        val session = Any()
        cache.get(session, 1) { JSONObject().put("name", "original") }.put("name", "changed")
        assertEquals("original", cache.get(session, 1) { error("Unexpected refetch") }.getString("name"))
        assertEquals("other account", cache.get(Any(), 1) { JSONObject().put("name", "other account") }.getString("name"))
    }
    @Test fun failureCanBeRetried() {
        val cache = SteamAppMetadata()
        val session = Any()
        assertThrows(IllegalStateException::class.java) { cache.get(session, 1) { error("timeout") } }
        assertTrue(cache.get(session, 1) { JSONObject().put("ready", true) }.getBoolean("ready"))
    }
    @Test fun concurrentReadersShareOneRequestWithoutBlockingOtherGames() {
        val cache = SteamAppMetadata()
        val session = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val fetch = {
            calls.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            JSONObject().put("ready", true)
        }
        val first = CompletableFuture.supplyAsync { cache.get(session, 1, fetch) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = CompletableFuture.supplyAsync { cache.get(session, 1, fetch) }
            assertTrue(cache.get(session, 2) { JSONObject().put("other", true) }.getBoolean("other"))
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS).getBoolean("ready"))
            assertTrue(second.get(5, TimeUnit.SECONDS).getBoolean("ready"))
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }
}
