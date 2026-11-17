package com.winlator.cmod.steam

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamLibrarySyncTest {
    private class FakeGateway : SteamLibrarySync.Gateway {
        var json = """{"packages":[{"id":10,"access_token":"18446744073709551615"}],"owned_apps":[]}"""
        var packageCalls = 0
        var appCalls = 0
        var fail = false
        override fun snapshot() = json
        override fun packages(ids: List<Int>, tokens: List<Long>): Set<Int>? {
            packageCalls++
            assertEquals(listOf(10), ids)
            assertEquals(listOf(-1L), tokens)
            if (fail) return null
            json = """{"packages":[{"id":10}],"owned_apps":[{"id":42,"type":"","access_token":"0"}]}"""
            return ids.toSet()
        }
        override fun tokens(ids: List<Int>) = mapOf(42 to -1L)
        override fun apps(ids: List<Int>, tokens: List<Long>): Set<Int>? {
            appCalls++
            assertEquals(listOf(42), ids)
            assertEquals(listOf(-1L), tokens)
            json = """{"packages":[{"id":10}],"owned_apps":[{"id":42,"name":"Owned game","type":"game"}]}"""
            return ids.toSet()
        }
    }
    @Test fun populatesPackageThenAppMetadataBeforeCompletingLibrary() {
        val gateway = FakeGateway()
        val sync = SteamLibrarySync()
        sync.step(gateway, true, 0)
        assertTrue(sync.loading)
        assertEquals(1, gateway.packageCalls)
        assertEquals(50, sync.progress)
        assertEquals("Loading Steam library… 50%", sync.message)
        sync.step(gateway, true, 3000)
        assertTrue(sync.loading)
        assertEquals(1, gateway.appCalls)
        assertEquals(99, sync.progress)
        assertEquals("game", JSONObject(gateway.json).getJSONArray("owned_apps").getJSONObject(0).getString("type"))
        sync.step(gateway, true, 6000)
        assertFalse(sync.loading)
        assertEquals(100, sync.progress)
        assertEquals("", sync.error)
        sync.step(gateway, true, 9000)
        assertEquals(1, gateway.packageCalls)
        assertEquals(1, gateway.appCalls)
    }
    @Test fun failedRequestStopsSpinnerAndExplicitRetryWorks() {
        val gateway = FakeGateway().apply { fail = true }
        val sync = SteamLibrarySync()
        sync.step(gateway, true, 0)
        assertFalse(sync.loading)
        assertTrue(sync.error.contains("package metadata"))
        assertEquals(0, sync.progress)
        sync.step(gateway, true, 3000)
        assertEquals(1, gateway.packageCalls)
        gateway.fail = false
        sync.reset()
        sync.step(gateway, true, 6000)
        assertTrue(sync.loading)
        assertEquals("", sync.error)
        assertEquals(2, gateway.packageCalls)
    }
    @Test fun emptyLicenseListCompletesAsEmptyRatherThanLoadingForever() {
        val gateway = FakeGateway().apply { json = """{"packages":[],"owned_apps":[]}""" }
        val sync = SteamLibrarySync()
        sync.step(gateway, true, 0)
        assertFalse(sync.loading)
        assertEquals("", sync.error)
        assertEquals(0, gateway.packageCalls)
    }
    @Test fun missingLicensePushTimesOutWithoutStartingMetadataRequests() {
        val gateway = FakeGateway()
        val sync = SteamLibrarySync()
        sync.step(gateway, false, 1000)
        assertTrue(sync.loading)
        sync.step(gateway, false, 46000)
        assertFalse(sync.loading)
        assertTrue(sync.error.contains("licenses"))
        assertEquals(0, gateway.packageCalls)
    }
    @Test fun cachedMetadataCompletesWithoutAnyPicsRequests() {
        val gateway = FakeGateway().apply {
            json = """{"packages":[{"id":10,"pics_fetched":true}],"owned_apps":[{"id":42,"pics_fetched":true,"name":"Cached game"}]}"""
        }
        val sync = SteamLibrarySync()
        sync.step(gateway, true, 0)
        assertFalse(sync.loading)
        assertEquals(0, gateway.packageCalls)
        assertEquals(0, gateway.appCalls)
        sync.reset(forceRefresh = true)
        gateway.json = """{"packages":[{"id":10,"pics_fetched":true,"access_token":"18446744073709551615"}],"owned_apps":[]}"""
        sync.step(gateway, true, 1)
        assertEquals(1, gateway.packageCalls)
    }
    @Test fun cachedPackagesStillFetchMissingGameMetadata() {
        val gateway = FakeGateway().apply {
            json = """{"packages":[{"id":10,"pics_fetched":true}],"owned_apps":[{"id":41,"pics_fetched":true},{"id":42,"pics_fetched":false}]}"""
        }
        val sync = SteamLibrarySync()
        sync.step(gateway, true, 0)
        assertEquals(0, gateway.packageCalls)
        assertEquals(1, gateway.appCalls)
    }
    @Test fun partialResponsesLoadAllFiveHundredGamesAndForcedReloadRetriesMissingIds() {
        val rows = org.json.JSONArray()
        for (id in 1..500) rows.put(JSONObject().put("id", id).put("pics_fetched", false))
        val snapshot = JSONObject().put("packages", org.json.JSONArray()).put("owned_apps", rows)
        var calls = 0
        val fetched = mutableSetOf<Int>()
        val gateway = object : SteamLibrarySync.Gateway {
            override fun snapshot() = snapshot.toString()
            override fun packages(ids: List<Int>, tokens: List<Long>) = ids.toSet()
            override fun tokens(ids: List<Int>) = emptyMap<Int, Long>()
            override fun apps(ids: List<Int>, tokens: List<Long>): Set<Int> {
                val returned = if (calls++ == 0) ids.take(36) else ids
                fetched.addAll(returned)
                for (i in 0 until rows.length()) {
                    val app = rows.getJSONObject(i)
                    if (app.getInt("id") in returned) app.put("pics_fetched", true)
                }
                return returned.toSet()
            }
        }
        val sync = SteamLibrarySync()
        repeat(20) { sync.step(gateway, true, it * 3000L) }
        assertEquals(500, fetched.size)
        assertFalse(sync.loading)
        assertEquals("", sync.error)
        calls = 0; fetched.clear()
        sync.reset(forceRefresh = true)
        repeat(20) { sync.step(gateway, true, it * 3000L) }
        assertEquals(500, fetched.size)
        assertFalse(sync.loading)
        assertEquals("", sync.error)
    }
    @Test fun omittedMetadataCannotBeSavedAsACompleteLibrary() {
        val gateway = object : SteamLibrarySync.Gateway {
            override fun snapshot() = """{"packages":[],"owned_apps":[{"id":42}]}"""
            override fun packages(ids: List<Int>, tokens: List<Long>) = emptySet<Int>()
            override fun apps(ids: List<Int>, tokens: List<Long>) = emptySet<Int>()
            override fun tokens(ids: List<Int>) = emptyMap<Int, Long>()
        }
        val sync = SteamLibrarySync()
        repeat(3) { sync.step(gateway, true, it * 3000L) }
        assertFalse(sync.loading)
        assertTrue(sync.error.contains("incomplete"))
        assertTrue(sync.progress < 100)
    }
}
