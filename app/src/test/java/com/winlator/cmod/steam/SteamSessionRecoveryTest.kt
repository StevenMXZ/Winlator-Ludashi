package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test

class SteamSessionRecoveryTest {
    @Test fun connectedAfterLogoffTriggersTokenRecoveryInsteadOfCredentialLogin() {
        val policy = SteamSessionRecovery()
        policy.began(0)
        policy.observe(3, 1000)
        assertFalse(policy.shouldRetry(40000))
        policy.observe(2, 40001)
        assertFalse(policy.shouldRetry(42000))
        assertTrue(policy.shouldRetry(42001))
    }
    @Test fun stalledReconnectsAreLimitedAndHaveLogonDeadline() {
        val policy = SteamSessionRecovery()
        policy.began(0)
        assertFalse(policy.shouldRetry(29999))
        assertTrue(policy.shouldRetry(30000))
        policy.began(30000)
        assertTrue(policy.shouldRetry(60000))
        policy.began(60000)
        assertFalse(policy.shouldRetry(90000))
        assertTrue(policy.exhausted(90000))
    }
    @Test fun briefSuccessfulConnectionsDoNotCauseUnboundedRetries() {
        val policy = SteamSessionRecovery()
        repeat(3) { i ->
            val now = i * 5000L
            policy.began(now); policy.observe(3, now + 100); policy.observe(2, now + 200)
        }
        assertTrue(policy.exhausted(20000))
        assertFalse(policy.shouldRetry(20000))
    }
    @Test fun stableConnectionResetsRetryBudget() {
        val policy = SteamSessionRecovery()
        repeat(3) { policy.began(it * 30000L) }
        policy.observe(3, 90000)
        policy.observe(3, 150000)
        policy.observe(0, 150001)
        assertTrue(policy.shouldRetry(152001))
        assertFalse(policy.exhausted(152001))
    }
    @Test fun readsSteamLogoffReasonAndDistinguishesThrottleFromInvalidToken() {
        assertEquals(84, SteamLogonResult.code(byteArrayOf(18, 1, 0, 8, 84), -1))
        assertEquals(6, SteamLogonResult.code(byteArrayOf(8, 6), -1))
        assertNull(SteamLogonResult.code(byteArrayOf(8, -128), -1))
        assertFalse(SteamLogonResult.requiresNewLogin(84))
        assertTrue(SteamLogonResult.requiresNewLogin(5))
    }
}
