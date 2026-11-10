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
            SteamSession.close()
            context.stopService(Intent(context, SteamService::class.java))
        }
        revision.intValue++
    }
    fun mode(context: Context, id: Int): String = context.getSharedPreferences("steam-settings", 0).getString("mode-$id", "RealSteam") ?: "RealSteam"
    fun setMode(context: Context, id: Int, mode: String) {
        require(mode in listOf("RealSteam", "Goldberg", "Raw"))
        context.getSharedPreferences("steam-settings", 0).edit().putString("mode-$id", mode).apply()
    }
}
