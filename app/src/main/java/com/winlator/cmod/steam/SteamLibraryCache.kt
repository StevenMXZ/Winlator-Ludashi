package com.winlator.cmod.steam

import org.json.JSONObject
import java.io.File

internal class SteamLibraryCache(private val root: File) {
    data class Entry(val snapshot: String, val metadata: String, val savedAt: Long) {
        fun fresh(now: Long) = now >= savedAt && now - savedAt < 6 * 60 * 60 * 1000L
    }
    fun read(account: Long): Entry? = runCatching {
        require(account > 0)
        val json = JSONObject(File(root, "$account.json").readText())
        require(json.getInt("version") == 1 && json.getLong("account") == account)
        Entry(json.getJSONObject("snapshot").toString(), json.getString("metadata"), json.getLong("savedAt"))
    }.getOrNull()

    fun write(account: Long, snapshot: String, metadata: String, now: Long) {
        require(account > 0)
        val json = JSONObject().put("version", 1).put("account", account).put("savedAt", now)
            .put("snapshot", JSONObject(snapshot)).put("metadata", metadata)
        check(root.isDirectory || root.mkdirs())
        val temp = File(root, "$account.tmp")
        temp.writeText(json.toString())
        check(temp.renameTo(File(root, "$account.json")))
    }
}
