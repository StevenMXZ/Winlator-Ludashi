package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test

class SteamConnectionGateTest {
    @Test fun connectRequestDoesNotPermitTokenLogonUntilConnectedCallback() {
        val gate = SteamConnectionGate()
        gate.observe(1)
        assertFalse(gate.awaitConnected(0))
        gate.observe(2)
        assertTrue(gate.awaitConnected(0))
    }
    @Test fun disconnectUnblocksWaitWithoutPermittingLogon() {
        val gate = SteamConnectionGate()
        gate.observe(1); gate.observe(0)
        assertFalse(gate.awaitConnected(0))
    }
}
