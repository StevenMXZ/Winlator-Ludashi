package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SteamCustomGamePauseTest {
    private fun drainWorker() {
        val done = CountDownLatch(1)
        SteamSession.work { done.countDown() }
        assertTrue("Steam worker must remain responsive", done.await(10, TimeUnit.SECONDS))
    }
    @Test fun customLaunchPausesAnOpenedOptionalServiceWithoutRequiringTheNativeLibrary() {
        val context = RuntimeEnvironment.getApplication()
        SteamSettings.setEnabled(context, true)
        context.getSharedPreferences("steam-settings", 0).edit().putBoolean("social", false).commit()
        SteamSession.open(context)
        drainWorker()
        try {
            assertTrue(SteamSession.pauseForCustomGame(context))
            assertTrue(SteamSession.gameActive)
            assertNull(SteamSession.session)
        } finally { SteamSession.endGame(context); SteamSession.close(); drainWorker() }
        assertFalse(SteamSession.gameActive)
    }
    @Test fun ordinaryContainerAlsoPausesWhenSocialWasEnabled() {
        val context = RuntimeEnvironment.getApplication()
        SteamSettings.setEnabled(context, true)
        context.getSharedPreferences("steam-settings", 0).edit().putBoolean("social", true).commit()
        SteamSession.open(context)
        drainWorker()
        try {
            assertTrue(SteamSession.pauseForCustomGame(context))
            assertTrue(SteamSession.gameActive)
        } finally { SteamSession.endGame(context); SteamSession.close(); drainWorker() }
    }
}
