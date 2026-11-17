package com.winlator.cmod.steam

internal class SteamPendingLogin(val account: String, private val token: String, private val expectedId: Long) {
    fun confirm(steamId: Long, persist: (String, String, Long) -> Unit) {
        check(steamId > 0 && (expectedId == 0L || expectedId == steamId)) { "Steam confirmed an unexpected account" }
        check(account.isNotBlank() && token.isNotBlank()) { "Steam returned incomplete account details" }
        persist(account, token, steamId)
    }
}
