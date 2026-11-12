package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE, sdk = [28])
class SteamCloudTest {
    @Test fun cloudCapabilityUsesProductMetadataWithoutTreatingUnknownAsUnsupported() {
        assertEquals(false, SteamCloud.supportsCloud(org.json.JSONObject("""{"common":{"name":"No Cloud"},"depots":{}}""")))
        assertEquals(false, SteamCloud.supportsCloud(org.json.JSONObject("""{"common":{},"depots":{},"ufs":{"quota":"0","maxnumfiles":"0"}}""")))
        assertEquals(true, SteamCloud.supportsCloud(org.json.JSONObject("""{"common":{},"depots":{},"ufs":{"quota":"1000"}}""")))
        assertEquals(true, SteamCloud.supportsCloud(org.json.JSONObject("""{"common":{"category":{"category_23":"1"}},"depots":{}}""")))
        assertNull(SteamCloud.supportsCloud(org.json.JSONObject("{}")))
    }

    @Test fun onlyConfirmedStateMeansSynchronizedIncludingAnEmptyTransfer() {
        for (state in -1..8) {
            val report = SteamCloud.parse("appid=764790\nphase=exit\nstate=$state\ntransfers=0\n")!!
            assertEquals(state == 2, report.synchronized)
        }
    }
    @Test fun rejectsTruncatedMalformedAndUnscopedReports() {
        listOf("", "appid=1\nstate=2", "appid=0\nphase=exit\nstate=2\ntransfers=0", "appid=1\nphase=unknown\nstate=2\ntransfers=0", "appid=1\nphase=exit\nstate=2\ntransfers=-1")
            .forEach { assertNull(it, SteamCloud.parse(it)) }
    }
    @Test fun pendingLaunchIsNotSuccessfulExitUpload() {
        val report = SteamCloud.parse("appid=764790\nphase=launch\nstate=5\ntransfers=3\n")!!
        assertEquals(764790, report.id)
        assertEquals("launch", report.phase)
        assertFalse(report.synchronized)
    }
}
