package com.winlator.cmod.steam

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr

private val statuses = mapOf(
    "Scan the QR code with the Steam mobile app" to R.string.steam_qr_help,
    "Connected — sign in to Steam" to R.string.steam_ui_connected_login,
    "Connecting…" to R.string.steam_ui_connecting,
    "Disconnected" to R.string.steam_ui_disconnected,
    "Signing in…" to R.string.steam_ui_signing_in,
    "Signed in — loading library…" to R.string.steam_ui_signed_in,
    "Steam library is up to date" to R.string.steam_ui_library_ready,
    "Steam app session paused while the game runs" to R.string.steam_ui_suspended,
    "Steam restoration timed out. Retry connection." to R.string.steam_ui_restore_timeout,
    "Finding a Steam server" to R.string.steam_ui_finding_server,
    "Closing previous Steam connection" to R.string.steam_ui_closing_connection,
    "Connecting to Steam server" to R.string.steam_ui_connecting_server,
    "Authenticating saved Steam account" to R.string.steam_ui_auth_saved,
    "Authenticating Steam account" to R.string.steam_ui_auth_account,
    "Approve this sign-in in your Steam mobile app" to R.string.steam_ui_approve_login,
    "Enter your Steam Guard code" to R.string.steam_ui_guard_enter,
    "Incorrect code. Enter a new Steam Guard code" to R.string.steam_ui_guard_incorrect,
    "Exit the running Steam game before changing this setting" to R.string.steam_ui_exit_setting
)
internal fun steamStatusText(value: String): String {
    statuses[value]?.let { return uiStr(it) }
    val timer = Regex("(.+) • ([0-9]+)s remaining").matchEntire(value)
    if (timer != null) return uiStr(R.string.steam_ui_remaining, steamStatusText(timer.groupValues[1]), timer.groupValues[2])
    val progress = Regex("Loading Steam library… ([0-9]+)%").matchEntire(value)
    if (progress != null) return uiStr(R.string.steam_ui_loading_library, progress.groupValues[1].toInt())
    return value
}
internal fun steamSortLabel(key: String): String = uiStr(when (key) {
    "Recent" -> R.string.steam_ui_recent
    "Playtime" -> R.string.steam_ui_sort_playtime
    else -> R.string.steam_ui_name
})
