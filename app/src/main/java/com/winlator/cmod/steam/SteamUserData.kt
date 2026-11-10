package com.winlator.cmod.steam

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only account information. Cached per Steam account; never changes achievements. */
object SteamUserData {
    val revision = mutableIntStateOf(0)
    data class Achievement(val key: String, val title: String, val description: String, val unlocked: Boolean, val unlockedAt: Long, val icon: String)
    private fun root(context: Context): File? = SteamCredentials.load(context)?.optLong("id")?.takeIf { it > 0 }?.let { File(context.filesDir, "steam-user/$it") }
    private fun file(context: Context, name: String) = root(context)?.let { File(it, name) }
    fun profile(context: Context): JSONObject {
        revision.intValue
        return runCatching { JSONObject(file(context, "profile.json")!!.readText()) }.getOrDefault(JSONObject())
    }
    fun owned(context: Context): JSONArray {
        revision.intValue
        return runCatching { JSONArray(file(context, "owned.json")!!.readText()) }.getOrDefault(JSONArray())
    }
    fun game(context: Context, id: Int): JSONObject? {
        val games = owned(context)
        return (0 until games.length()).map { games.getJSONObject(it) }.firstOrNull { it.optInt("appId") == id }
    }
    fun achievementData(context: Context, id: Int): JSONObject? {
        revision.intValue
        return runCatching { JSONObject(file(context, "achievements-$id.json")!!.readText()) }.getOrNull()
    }
    fun cachedAchievements(context: Context): Map<Int, List<Achievement>> {
        revision.intValue
        return root(context)?.listFiles().orEmpty().filter { it.name.startsWith("achievements-") }.mapNotNull { f ->
            runCatching { val id = f.nameWithoutExtension.removePrefix("achievements-").toInt(); id to parseAchievements(JSONObject(f.readText()), id) }.getOrNull()
        }.toMap()
    }
    private fun save(file: File, text: String) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        try { temporary.writeText(text); check(temporary.renameTo(file)) { "Could not save Steam information" } }
        finally { temporary.delete() }
    }
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var error = ""
        private set
    fun busy(key: String? = null): Boolean { revision.intValue; return if (key == null) pending.isNotEmpty() else key in pending }
    fun refreshProfile(context: Context, force: Boolean = false) {
        if (!force && file(context, "owned.json")?.isFile == true) return
        request(context, "profile") { session, destination ->
            val id = SteamCredentials.load(context)?.getLong("id") ?: error("Sign in first")
            session.requestUserPersona()
            val games = session.getOwnedGames(id) ?: error("Steam playtime request timed out")
            JSONArray(games)
            save(destination.resolve("owned.json"), games)
            session.getSelfPersona()?.let { save(destination.resolve("profile.json"), it) }
        }
    }
    fun refreshAchievements(context: Context, id: Int, force: Boolean = false) {
        if (!force && file(context, "achievements-$id.json")?.isFile == true) return
        request(context, "achievements-$id") { session, destination ->
            val raw = session.getUserStatsJson(id) ?: error("Steam achievements request timed out")
            val json = JSONObject(raw)
            check(json.optInt("eresult") == 1 && json.optJSONObject("schema") != null) { "Steam achievements are unavailable for this game" }
            parseAchievements(json, id)
            save(destination.resolve("achievements-$id.json"), raw)
        }
    }
    @Synchronized private fun request(context: Context, key: String, action: (com.winlator.cmod.feature.stores.steam.ludashisteam.LudashiSteamSession, File) -> Unit) {
        if (!SteamSession.signedIn || SteamSession.gameActive || pending.isNotEmpty() || !pending.add(key)) return
        val destination = root(context) ?: run { pending.remove(key); return }
        val transport = SteamSession.session
        revision.intValue++; error = ""
        SteamSession.work {
            try {
                check(!SteamSession.gameActive && SteamSession.signedIn && SteamSession.session === transport && transport != null) { "Connect Steam to load game information" }
                destination.mkdirs(); action(transport, destination)
            } catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { pending.remove(key); android.os.Handler(android.os.Looper.getMainLooper()).post { revision.intValue++ } }
        }
    }
    fun parseAchievements(json: JSONObject, id: Int): List<Achievement> {
        fun findStats(node: JSONObject, depth: Int = 0): JSONObject? {
            node.optJSONObject("stats")?.let { return it }
            if (depth >= 5) return null
            for (key in node.keys()) node.optJSONObject(key)?.let { findStats(it, depth + 1)?.let { found -> return found } }
            return null
        }
        fun localized(value: Any?): String = when (value) {
            is JSONObject -> value.optString("english").ifBlank { value.keys().asSequence().map { value.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty() }
            is String -> value
            else -> ""
        }
        val schema = json.optJSONObject("schema") ?: return emptyList()
        val statsSchema = findStats(schema) ?: return emptyList()
        val stats = json.optJSONArray("stats") ?: JSONArray()
        val live = (0 until stats.length()).map { stats.getJSONObject(it) }.associate { it.optInt("id") to it.optLong("value") }
        val blocks = json.optJSONArray("achievementBlocks") ?: JSONArray()
        val times = (0 until blocks.length()).map { blocks.getJSONObject(it) }.associate { it.optInt("achievementId") to it.optJSONArray("unlockTimes") }
        return buildList {
            for (blockKey in statsSchema.keys()) {
                val blockId = blockKey.toIntOrNull() ?: continue
                val bits = statsSchema.optJSONObject(blockKey)?.optJSONObject("bits") ?: continue
                for (bitKey in bits.keys()) {
                    val bit = bitKey.toIntOrNull()?.takeIf { it in 0..31 } ?: continue
                    val entry = bits.optJSONObject(bitKey) ?: continue
                    val display = entry.optJSONObject("display") ?: JSONObject()
                    val unlocked = ((live[blockId] ?: 0L) and (1L shl bit)) != 0L
                    val hidden = display.optString("hidden") == "1"
                    val key = entry.optString("name", "$blockKey-$bitKey")
                    val icon = display.optString(if (unlocked) "icon" else "icon_gray").ifBlank { display.optString("icon") }
                    add(Achievement(key, if (hidden && !unlocked) "Hidden achievement" else localized(display.opt("name")).ifBlank { key },
                        if (hidden && !unlocked) "Unlock to reveal" else localized(display.opt("desc")), unlocked,
                        if (unlocked) times[blockId]?.optLong(bit) ?: 0L else 0L,
                        if (icon.isBlank()) "" else "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/$id/$icon"))
                }
            }
        }.sortedWith(compareByDescending<Achievement> { it.unlocked }.thenBy { it.title })
    }
}
