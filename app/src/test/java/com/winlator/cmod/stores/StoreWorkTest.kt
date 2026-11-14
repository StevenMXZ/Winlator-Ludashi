package com.winlator.cmod.stores

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StoreWorkTest {
    @Test fun cancelledWorkCannotResumeWhenANewOperationStarts() = runBlocking {
        val store = LudashiStore.ITCH
        StoreSessions.fresh(store)
        withContext(StoreSessions.operation(store)) {
            assertTrue(StoreSessions.live(store))
            StoreSessions.cancel(store)
            StoreSessions.fresh(store)
            assertFalse(StoreSessions.live(store))
        }
        withContext(StoreSessions.operation(store)) { assertTrue(StoreSessions.live(store)) }
    }
    @Test fun gameEntrySuspendsStoreWorkUntilTheWineSessionEnds() {
        assertTrue(com.winlator.cmod.core.ContainerSessionGate.start(9876))
        try {
            StoreSessions.pauseForGame()
            assertTrue(StoreSessions.paused)
            StoreSessions.resumeForLibrary()
            assertTrue(StoreSessions.paused)
        } finally { com.winlator.cmod.core.ContainerSessionGate.stop(9876); StoreSessions.resumeForLibrary() }
        assertFalse(StoreSessions.paused)
    }
    @Test fun cancelledTransferFilesAreNotDeletedWhileAGameRuns() {
        val directory = java.nio.file.Files.createTempDirectory("store-pending-cleanup").toFile()
        directory.resolve("partial.bin").writeText("partial")
        assertTrue(com.winlator.cmod.core.ContainerSessionGate.start(9877))
        try {
            StoreSessions.pauseForGame()
            StoreSessions.discard(directory)
            assertTrue(directory.resolve("partial.bin").isFile)
        } finally { com.winlator.cmod.core.ContainerSessionGate.stop(9877); StoreSessions.resumeForLibrary() }
    }
    @Test fun disablingAStoreBlocksRequestsWithoutDeletingItsCredentialsOrGameFiles() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val store = LudashiStore.ITCH
        store.setEnabled(context, false)
        try {
            assertFalse(store.enabled(context))
            assertThrows(IllegalStateException::class.java) { StoreNetwork.check(context, store) }
        } finally { context.getSharedPreferences("ludashi-stores", 0).edit().clear().commit(); StoreSessions.fresh(store) }
    }
    @Test fun startingAGameStopsExecutableCopyBeforeTheNextDiskWrite() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val store = LudashiStore.ITCH
        StoreSessions.resumeForLibrary(); StoreSessions.fresh(store)
        val output = java.io.ByteArrayOutputStream()
        val input = object : java.io.ByteArrayInputStream(ByteArray(1024 * 1024)) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                val result = super.read(bytes, offset, count)
                StoreSessions.pauseForGame()
                return result
            }
        }
        try {
            assertThrows(IllegalStateException::class.java) { StoreInstalls.copyPayload(context, store, input, output) }
            assertEquals(0, output.size())
        } finally { StoreSessions.resumeForLibrary() }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun cancellingQueuedArtworkReleasesItsPendingMarker() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val store = LudashiStore.ITCH
        StoreSessions.resumeForLibrary(); StoreSessions.fresh(store)
        val state = StoreSessions.state(store); val old = state.value
        val slots = StoreSessions::class.java.getDeclaredField("artworkSlots").apply { isAccessible = true }.get(StoreSessions) as kotlinx.coroutines.sync.Semaphore
        val jobs = StoreSessions::class.java.getDeclaredField("artworkTasks").apply { isAccessible = true }.get(StoreSessions) as java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>
        val markers = StoreSessions::class.java.getDeclaredField("artwork").apply { isAccessible = true }.get(StoreSessions) as MutableSet<String>
        slots.acquire(); slots.acquire()
        val game = StoreGame(store, "https://test.itch.io/queued-artwork", "Game", "https://img.itch.zone/queued.jpg")
        try {
            state.value = old.copy(games = listOf(game))
            StoreSessions.requestArt(context, game.uri)
            val job = jobs.getValue(game.uri)
            StoreSessions.cancel(store)
            kotlinx.coroutines.withTimeout(3000) { job.join() }
            assertTrue(job.isCancelled)
            assertFalse(markers.contains(game.uri))
            assertFalse(jobs.containsKey(game.uri))
            assertEquals(0, slots.availablePermits)
        } finally { slots.release(); slots.release(); state.value = old; StoreSessions.fresh(store) }
    }
}
