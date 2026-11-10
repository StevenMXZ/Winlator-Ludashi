package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test

class SteamGuardPromptTest {
    @Test fun completesRequestedCodeWithoutRetainingIt() {
        val prompt = SteamGuardPrompt()
        val future = prompt.request("Email code")
        assertFalse(prompt.submit("  "))
        assertFalse(future.isDone)
        assertTrue(prompt.submit(" ABC12 "))
        assertEquals("ABC12", future.get())
        assertEquals("", prompt.label)
        assertFalse(prompt.submit("OTHER"))
    }
    @Test fun newChallengeCompletesOldRequestAndCancelUnblocksNativeCaller() {
        val prompt = SteamGuardPrompt()
        val old = prompt.request("First")
        val current = prompt.request("Try again")
        assertEquals("", old.get())
        assertFalse(current.isDone)
        prompt.cancel()
        assertEquals("", current.get())
        assertEquals("", prompt.label)
    }
}
