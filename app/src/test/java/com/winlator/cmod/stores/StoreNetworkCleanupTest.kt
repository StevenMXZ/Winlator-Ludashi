package com.winlator.cmod.stores

import android.os.Looper
import okhttp3.*
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StoreNetworkCleanupTest {
    private class ClosingCall(private val tag: String) : Call {
        val closed = CountDownLatch(1)
        val onMain = AtomicBoolean(false)
        override fun request() = Request.Builder().url("https://itch.io/").tag(tag).build()
        override fun cancel() { onMain.set(Thread.currentThread() == Looper.getMainLooper().thread); closed.countDown() }
        override fun isCanceled() = closed.count == 0L
        override fun isExecuted() = true
        override fun execute(): Response = error("Not executed in this test")
        override fun enqueue(responseCallback: Callback) = error("Not executed in this test")
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = this
    }
    @Suppress("UNCHECKED_CAST")
    private fun calls(): ConcurrentHashMap<LudashiStore, MutableSet<Call>> = StoreNetwork::class.java.getDeclaredField("calls").apply { isAccessible = true }.get(StoreNetwork) as ConcurrentHashMap<LudashiStore, MutableSet<Call>>
    @Test fun cancellingAStoreNeverClosesItsTlsSocketsOnTheUiThread() {
        val call = ClosingCall("download")
        val calls = calls(); calls[LudashiStore.ITCH] = ConcurrentHashMap.newKeySet<Call>().apply { add(call) }
        try {
            StoreNetwork.stop(LudashiStore.ITCH)
            assertTrue(call.closed.await(3, TimeUnit.SECONDS))
            assertFalse(call.onMain.get())
        } finally { calls.remove(LudashiStore.ITCH) }
    }
    @Test fun changingCatalogFiltersDoesNotCancelGameDownloads() {
        val catalog = ClosingCall(ItchCatalog.REQUEST_TAG)
        val download = ClosingCall("download")
        val calls = calls(); calls[LudashiStore.ITCH] = ConcurrentHashMap.newKeySet<Call>().apply { add(catalog); add(download) }
        try {
            StoreNetwork.stopTagged(LudashiStore.ITCH, ItchCatalog.REQUEST_TAG)
            assertTrue(catalog.closed.await(3, TimeUnit.SECONDS))
            assertFalse(catalog.onMain.get())
            assertFalse(download.isCanceled())
        } finally { calls.remove(LudashiStore.ITCH) }
    }
}
