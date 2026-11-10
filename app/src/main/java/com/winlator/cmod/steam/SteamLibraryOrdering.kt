package com.winlator.cmod.steam

import com.winlator.cmod.ui.library.LibraryItem
import org.json.JSONObject

/** Keep timestamp/playtime keys Long even when Steam omitted a game's history. */
internal fun sortSteamLibrary(items: List<LibraryItem>, owned: Map<Int, JSONObject>, sort: String): List<LibraryItem> {
    fun value(item: LibraryItem, key: String): Long = owned[SteamLibrary.appId(item.shortcutPath)]?.optLong(key) ?: 0L
    return when (sort) {
        "Recent" -> items.sortedWith(compareByDescending<LibraryItem> { value(it, "rtimeLastPlayed") }.thenBy { it.name })
        "Playtime" -> items.sortedWith(compareByDescending<LibraryItem> { value(it, "playtimeForever") }.thenBy { it.name })
        else -> items.sortedBy { it.name }
    }
}
