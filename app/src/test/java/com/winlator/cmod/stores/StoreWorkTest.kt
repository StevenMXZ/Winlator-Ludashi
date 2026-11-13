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
        val store = LudashiStore.AMAZON
        store.setEnabled(context, false)
        try {
            assertFalse(store.enabled(context))
            assertThrows(IllegalStateException::class.java) { StoreNetwork.check(context, store) }
        } finally { context.getSharedPreferences("ludashi-stores", 0).edit().clear().commit(); StoreSessions.fresh(store) }
    }
}
