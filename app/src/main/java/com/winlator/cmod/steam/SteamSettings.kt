package com.winlator.cmod.steam

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf

object SteamSettings {
    private val revision = mutableIntStateOf(0)
    @JvmStatic fun enabled(context: Context): Boolean {
        revision.intValue
        return context.getSharedPreferences("steam-settings", 0).getBoolean("enabled", true)
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        check(!SteamSession.gameActive) { "Exit the running Steam game before changing this setting" }
        context.getSharedPreferences("steam-settings", 0).edit().putBoolean("enabled", enabled).commit()
        if (!enabled) {
            SteamService.shutdown(context)
        }
        revision.intValue++
    }
    fun mode(context: Context, id: Int): String = context.getSharedPreferences("steam-settings", 0).getString("mode-$id", "RealSteam") ?: "RealSteam"
    fun setMode(context: Context, id: Int, mode: String) {
        require(mode in listOf("RealSteam", "Goldberg", "Raw"))
        context.getSharedPreferences("steam-settings", 0).edit().putString("mode-$id", mode).apply()
    }
}

/** Same adaptive network ceilings and CPU ratios as Ludashi's DownloadSpeedConfig. */
object SteamDownloadSettings {
    val tiers = listOf(0, 2, 3)
    fun tier(context: Context): Int {
        val preferences = context.getSharedPreferences("steam-settings", 0)
        val saved = preferences.getInt("download-tier", 2)
        val current = if (saved in tiers) saved else 2
        if (saved != current) preferences.edit().putInt("download-tier", current).apply()
        return current
    }
    fun setTier(context: Context, tier: Int) { require(tier in tiers); context.getSharedPreferences("steam-settings", 0).edit().putInt("download-tier", tier).apply() }
    fun network(tier: Int) = when (tier) { 0 -> 6; 3 -> 96; else -> 32 }
    fun processing(tier: Int, cores: Int = Runtime.getRuntime().availableProcessors()) = (cores * when (tier) { 0 -> .2; 3 -> .8; else -> .5 }).toInt().coerceAtLeast(1)
}
