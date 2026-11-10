package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamRestoreWatchTest {
    // Simulate a blocked transport operation: no CM event or worker poll arrives.
    @Test fun deadlineEndsRestorationEvenWithoutAnyTransportCallback() {
        fun field(name: String) = SteamSession::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("closing").setBoolean(SteamSession, false)
        field("restoring").setBoolean(SteamSession, true)
        field("savedAccount").setBoolean(SteamSession, true)
        field("restoreDeadline").setLong(SteamSession, -1)
        try {
            (field("restoreWatch").get(SteamSession) as Runnable).run()
            assertFalse(SteamSession.restoring)
            assertTrue(field("recoveryBlocked").getBoolean(SteamSession))
            assertTrue(SteamSession.savedAccount)
            assertTrue(SteamSession.status.contains("timed out"))
        } finally { SteamSession.close() }
    }
    @Test fun titlesOnlyHideTheMatchingSteamIdSuffix() {
        assertEquals("Game", SteamGames.cleanTitle("Game [42]", "42"))
        assertEquals("Game [99]", SteamGames.cleanTitle("Game [99]", "42"))
        assertEquals("Game 2026", SteamGames.cleanTitle("Game 2026", "42"))
        assertEquals("Game [42]", SteamGames.cleanTitle("Game [42]", ""))
    }
}
