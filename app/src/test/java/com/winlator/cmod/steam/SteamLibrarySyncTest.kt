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
        override fun packages(ids: List<Int>, tokens: List<Long>): Boolean {
            packageCalls++
            assertEquals(listOf(10), ids)
            assertEquals(listOf(-1L), tokens)
            if (fail) return false
            json = """{"packages":[{"id":10}],"owned_apps":[{"id":42,"type":"","access_token":"0"}]}"""
            return true
        }
        override fun tokens(ids: List<Int>) = mapOf(42 to -1L)
        override fun apps(ids: List<Int>, tokens: List<Long>): Boolean {
            appCalls++
            assertEquals(listOf(42), ids)
            assertEquals(listOf(-1L), tokens)
            json = """{"packages":[{"id":10}],"owned_apps":[{"id":42,"name":"Owned game","type":"game"}]}"""
            return true
        }
    }
    @Test fun populatesPackageThenAppMetadataBeforeCompletingLibrary() {
        val gateway = FakeGateway()
        val sync = SteamLibrarySync()
        sync.step(gateway, true, 0)
        assertTrue(sync.loading)
        assertEquals(1, gateway.packageCalls)
        sync.step(gateway, true, 3000)
        assertTrue(sync.loading)
        assertEquals(1, gateway.appCalls)
        assertEquals("game", JSONObject(gateway.json).getJSONArray("owned_apps").getJSONObject(0).getString("type"))
        sync.step(gateway, true, 6000)
        assertFalse(sync.loading)
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
}
