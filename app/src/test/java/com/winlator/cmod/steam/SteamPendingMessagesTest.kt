package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test

class SteamPendingMessagesTest {
    @Test fun rapidSendsRetainTheirTextWhileOtherConversationsAcknowledgeIndependently() {
        val pending = SteamPendingMessages()
        assertTrue(pending.add(1, "first")); assertTrue(pending.add(1, "second")); assertTrue(pending.add(2, "another friend"))
        assertEquals("another friend", pending.acknowledge(2))
        assertEquals("first", pending.acknowledge(1))
        assertEquals("second", pending.acknowledge(1))
        assertNull(pending.acknowledge(1))
    }
    @Test fun stalledRelayHasBoundedPendingMemoryAndResetCannotLeakPriorAccountMessages() {
        val pending = SteamPendingMessages()
        repeat(64) { assertTrue(pending.add(it.toLong(), "message")) }
        assertFalse(pending.add(100, "overflow"))
        pending.clear()
        assertNull(pending.acknowledge(1))
        assertTrue(pending.add(1, "new account"))
        assertEquals("new account", pending.acknowledge(1))
    }
}
