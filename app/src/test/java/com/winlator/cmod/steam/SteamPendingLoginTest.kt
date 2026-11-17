package com.winlator.cmod.steam

import org.junit.Assert.*
import org.junit.Test

class SteamPendingLoginTest {
    @Test fun qrResultWithoutSteamIdPersistsOnlyAfterServerConfirmation() {
        val pending = SteamPendingLogin("account", "refresh-token", 0)
        var savedId = 0L
        val persist: (String, String, Long) -> Unit = { account, token, id ->
            assertEquals("account", account)
            assertEquals("refresh-token", token)
            savedId = id
        }
        assertEquals(0L, savedId)
        assertThrows(IllegalStateException::class.java) { pending.confirm(0, persist) }
        assertEquals(0L, savedId)
        pending.confirm(76561198000000001L, persist)
        assertEquals(76561198000000001L, savedId)
    }
    @Test fun credentialsIdentityMustMatchTheConfirmedAccount() {
        val id = 76561198000000001L
        val pending = SteamPendingLogin("account", "token", id)
        var saves = 0
        assertThrows(IllegalStateException::class.java) { pending.confirm(id + 1) { _, _, _ -> saves++ } }
        assertEquals(0, saves)
        pending.confirm(id) { _, _, _ -> saves++ }
        assertEquals(1, saves)
    }
    @Test fun incompleteAccountAndFailedPersistenceCannotReportSuccess() {
        for (pending in listOf(SteamPendingLogin("", "token", 0), SteamPendingLogin("account", "", 0))) {
            assertThrows(IllegalStateException::class.java) { pending.confirm(76561198000000001L) { _, _, _ -> fail("Must not persist") } }
        }
        val pending = SteamPendingLogin("account", "token", 0)
        assertThrows(java.io.IOException::class.java) {
            pending.confirm(76561198000000001L) { _, _, _ -> throw java.io.IOException("Cannot save login") }
        }
    }
}
