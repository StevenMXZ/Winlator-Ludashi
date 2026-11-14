package com.winlator.cmod.gog

import android.os.Looper
import com.winlator.cmod.core.ContainerSessionGate
import com.winlator.cmod.stores.StoreSessions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GogBackgroundWorkTest {
    @Test fun oldBackgroundWorkStaysCancelledAfterReturningFromAGame() {
        val context = RuntimeEnvironment.getApplication()
        StoreSessions.resumeForLibrary()
        val old = GogLibrary.backgroundTicket()
        assertTrue(GogLibrary.backgroundAllowed(context, old))
        assertTrue(ContainerSessionGate.start(9131))
        try {
            StoreSessions.pauseForGame()
            assertFalse(GogLibrary.backgroundAllowed(context, old))
            assertThrows(IllegalStateException::class.java) { GogApi.product("1234") }
        } finally { ContainerSessionGate.stop(9131); StoreSessions.resumeForLibrary() }
        assertFalse(GogLibrary.backgroundAllowed(context, old))
        assertTrue(GogLibrary.backgroundAllowed(context, GogLibrary.backgroundTicket()))
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun gameEntryCancelsQueuedArtworkBeforeItCanStart() {
        val context = RuntimeEnvironment.getApplication()
        StoreSessions.resumeForLibrary()
        val pool = GogLibrary::class.java.getDeclaredField("artwork").apply { isAccessible = true }.get(GogLibrary) as ExecutorService
        val jobs = GogLibrary::class.java.getDeclaredField("artworkJobs").apply { isAccessible = true }.get(GogLibrary) as ConcurrentHashMap<String, FutureTask<Unit>>
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        repeat(2) { pool.execute { entered.countDown(); release.await(10, TimeUnit.SECONDS) } }
        val old = GogLibrary.mutable.value
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            GogLibrary.mutable.value = old.copy(games = listOf(GogGame("91001", "Game", "https://images.gog-statics.com/example.jpg")))
            GogLibrary.requestArt(context, "gog://91001")
            val pending = jobs.getValue("91001")
            assertFalse(pending.isDone)
            StoreSessions.pauseForGame()
            assertTrue(pending.isCancelled)
            assertFalse(jobs.containsKey("91001"))
        } finally { release.countDown(); GogLibrary.mutable.value = old; StoreSessions.resumeForLibrary() }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun gameEntryDisconnectsGogSocketsOffTheMainThread() {
        RuntimeEnvironment.getApplication()
        val closed = CountDownLatch(1); val onMain = AtomicBoolean()
        val connection = object : HttpURLConnection(URL("https://api.gog.com/")) {
            override fun connect() {}
            override fun usingProxy() = false
            override fun disconnect() { onMain.set(Thread.currentThread() == Looper.getMainLooper().thread); closed.countDown() }
        }
        val active = GogApi::class.java.getDeclaredField("connections").apply { isAccessible = true }.get(GogApi) as MutableSet<HttpURLConnection>
        active.add(connection)
        try {
            StoreSessions.pauseForGame()
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertFalse(onMain.get())
        } finally { active.remove(connection); StoreSessions.resumeForLibrary() }
    }
    @Test fun disablingGogInvalidatesBackgroundWork() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("gog-settings", 0)
        val original = prefs.getBoolean("enabled", true)
        StoreSessions.resumeForLibrary()
        val ticket = GogLibrary.backgroundTicket()
        try {
            GogSettings.setEnabled(context, false)
            assertFalse(GogLibrary.backgroundAllowed(context, ticket))
            assertFalse(GogLibrary.backgroundAllowed(context, GogLibrary.backgroundTicket()))
        } finally { prefs.edit().putBoolean("enabled", original).commit() }
    }
    @Test fun delayedInstallIsBlockedBeforeInspectingFilesWhenAGameStarts() {
        RuntimeEnvironment.getApplication()
        val service = GogDownloadService() // Deliberately unattached: no context/files may be accessed.
        val install = GogDownloadService::class.java.getDeclaredMethod("install", String::class.java, Int::class.javaPrimitiveType, org.json.JSONObject::class.java).apply { isAccessible = true }
        StoreSessions.pauseForGame()
        try {
            val failure = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { install.invoke(service, "1234", 1, org.json.JSONObject()) }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals("Download paused", failure.cause?.message)
        } finally { StoreSessions.resumeForLibrary() }
    }
}
