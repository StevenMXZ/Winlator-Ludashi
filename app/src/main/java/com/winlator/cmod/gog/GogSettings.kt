package com.winlator.cmod.gog

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

object GogSettings {
    private val revision = mutableIntStateOf(0)
    fun enabled(context: Context): Boolean {
        revision.intValue
        return context.getSharedPreferences("gog-settings", 0).getBoolean("enabled", true)
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        check(!GogLibrary.mutable.value.busy) { "Finish or cancel the GOG download before changing this setting" }
        context.getSharedPreferences("gog-settings", 0).edit().putBoolean("enabled", enabled).apply()
        revision.intValue++
        if (enabled) GogLibrary.open(context)
    }
}
