package com.winlator.cmod.steam

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Event-driven notifications; no network or timers query achievement state while a game runs. */
object SteamAchievements {
    private var watcher: AchievementWatcher? = null
    private var overlay: ComposeView? = null
    private var job: Job? = null
    private var account = 0L
    private var definitions: Map<String, SteamUserData.Achievement> = emptyMap()
    private var events = Channel<String>(16, BufferOverflow.DROP_OLDEST)
    private val syncing = AtomicBoolean(false)
    @Volatile private var nextSyncAt = 0L
    private fun pendingKey(user: Long) = "achievement-pending-$user"
    fun enabled(context: Context) = context.getSharedPreferences("steam-settings", 0).getBoolean("achievement-notifications", true)
    internal fun stats(schema: JSONObject, depth: Int = 0): JSONObject? {
        schema.optJSONObject("stats")?.let { return it }
        if (depth >= 5) return null
        for (key in schema.keys()) schema.optJSONObject(key)?.let { stats(it, depth+1)?.let { found -> return found } }
        return null
    }
    /** Called before installing GSE: merge earned states and supply the schema SetAchievement needs. */
    fun seed(context: Context, shortcut: Shortcut, game: File) {
        val id = shortcut.getExtra("steamAppId").toInt()
        val json = SteamUserData.achievementData(context, id) ?: return
        val list = SteamUserData.parseAchievements(json, id)
        val file = AchievementWatcher.gseAchievementsFile(shortcut.container.rootDir, id)
        val merged = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        mergeEarned(merged, list)
        file.parentFile?.mkdirs(); atomic(file, merged.toString())
        val schema = stats(json.optJSONObject("schema") ?: JSONObject()) ?: return
        val entries = JSONArray()
        for (block in schema.keys()) {
            val bits = schema.optJSONObject(block)?.optJSONObject("bits") ?: continue
            for (bit in bits.keys()) bits.optJSONObject(bit)?.let { e ->
                val display = e.optJSONObject("display") ?: JSONObject()
                fun text(key: String): String = display.optJSONObject(key)?.optString("english") ?: display.optString(key)
                entries.put(JSONObject().put("name", e.optString("name")).put("displayName", text("name")).put("description", text("desc")).put("hidden", display.optString("hidden") == "1"))
            }
        }
        SteamGoldberg.settingsDirectories(game).forEach { settings ->
            settings.mkdirs()
            atomic(File(settings, "achievements.json"), entries.toString())
        }
    }
    internal fun mergeEarned(local: JSONObject, real: List<SteamUserData.Achievement>) {
        real.filter { it.unlocked }.forEach { a -> if (local.optJSONObject(a.key)?.let { it.optBoolean("earned") || it.optInt("earned") == 1 } != true)
            local.put(a.key, JSONObject().put("earned", true).put("earned_time", a.unlockedAt)) }
    }
    private fun atomic(file: File, text: String) { if (file.isFile && file.readText() == text) return; val tmp = File(file.parentFile, file.name+".ludashi-tmp"); tmp.writeText(text); check(tmp.renameTo(file)) }
    fun begin(context: Context, shortcut: Shortcut) {
        stop()
        val id = shortcut.getExtra("steamAppId").toInt()
        account = SteamCredentials.load(context)?.optLong("id") ?: 0L
        definitions = SteamUserData.achievementData(context, id)?.let { SteamUserData.parseAchievements(it, id).associateBy { a -> a.key } }.orEmpty()
        // Hidden metadata stays concealed in the library, but the earned toast reveals its real name.
        val names = mutableMapOf<String, String>()
        SteamUserData.achievementData(context, id)?.optJSONObject("schema")?.let { raw -> stats(raw) }?.let { schema ->
            for (block in schema.keys()) schema.optJSONObject(block)?.optJSONObject("bits")?.let { bits ->
                for (bit in bits.keys()) bits.optJSONObject(bit)?.let { entry ->
                    val display = entry.optJSONObject("display")
                    val title = display?.optJSONObject("name")?.optString("english") ?: display?.optString("name").orEmpty()
                    if (title.isNotBlank()) names[entry.optString("name")] = title
                }
            }
        }
        definitions = definitions.mapValues { (key, value) -> value.copy(title = names[key] ?: value.title) }
        if (shortcut.getExtra("steamLaunchMode") == "Goldberg") {
            seed(context, shortcut, SteamGames.directory(context, id))
            watcher = AchievementWatcher().apply { start(context, id, shortcut.container.rootDir) }
        }
        if (!enabled(context)) return
        val activity = context as? ComponentActivity ?: return
        activity.runOnUiThread {
            val root = activity.findViewById<FrameLayout>(android.R.id.content)
            val view = ComposeView(activity)
            overlay = view
            view.setContent { WinZTheme { var text by remember { mutableStateOf("") }
                LaunchedEffect(Unit) { for (message in events) { text = message; delay(4000); text = "" } }
                if (text.isNotBlank()) Surface(tonalElevation = 8.dp, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(12.dp)) { Text("🏆 $text", Modifier.padding(14.dp), style = MaterialTheme.typography.titleSmall) }
            } }
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
    }
    fun surfaceUnlockPill(context: Context, id: Int, api: String): String {
        val title = definitions[api]?.title?.takeUnless { it == "Hidden achievement" } ?: api
        if (api.isNotBlank() && enabled(context)) events.trySend(title)
        return title
    }
    @Synchronized fun queueUnlock(context: Context, id: Int, api: String) {
        if (account <= 0 || api.isBlank()) return
        val prefs = context.getSharedPreferences("steam-settings", 0)
        val key = pendingKey(account)
        prefs.edit().putStringSet(key, prefs.getStringSet(key, emptySet()).orEmpty().toSet() + "$id:$api").commit()
    }
    /** Default-off writeback. One bounded app batch per existing idle session tick; never in-game. */
    fun syncPending(context: Context) {
        val prefs = context.getSharedPreferences("steam-settings", 0)
        if (!prefs.getBoolean("achievement-sync", false) || SteamSession.gameActive || !SteamSession.signedIn || SteamSession.libraryLoading || android.os.SystemClock.elapsedRealtime() < nextSyncAt || !syncing.compareAndSet(false, true)) return
        nextSyncAt = android.os.SystemClock.elapsedRealtime() + 60000
        SteamSession.work { try {
            val user = SteamCredentials.load(context)?.optLong("id") ?: return@work
            val queued = prefs.getStringSet(pendingKey(user), emptySet()).orEmpty().toSet()
            val id = queued.firstOrNull()?.substringBefore(':')?.toIntOrNull() ?: return@work
            val selected = queued.filter { it.substringBefore(':') == id.toString() }.take(100).toSet()
            if (SteamSession.gameActive) return@work
            val s = SteamSession.session ?: return@work
            val response = JSONObject(s.getUserStatsJson(id) ?: return@work)
            if (response.optInt("eresult") != 1) return@work
            val mask = linkedMapOf<Int, Int>()
            response.optJSONArray("stats")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { mask[it.getInt("id")] = it.getLong("value").toInt() } }
            val schema = stats(response.optJSONObject("schema") ?: return@work) ?: return@work
            val changes = linkedMapOf<Int, Int>(); val matched = mutableSetOf<String>()
            for (block in schema.keys()) {
                val blockId = block.toIntOrNull() ?: continue
                val bits = schema.optJSONObject(block)?.optJSONObject("bits") ?: continue
                for (bit in bits.keys()) {
                    val index = bit.toIntOrNull()?.takeIf { it in 0..31 } ?: continue
                    val api = bits.optJSONObject(bit)?.optString("name") ?: continue
                    if ("$id:$api" in selected) { changes[blockId] = (changes[blockId] ?: mask[blockId] ?: 0) or (1 shl index); matched.add("$id:$api") }
                }
            }
            if (changes.isEmpty() || SteamSession.gameActive) return@work
            if (s.storeUserStatsBlocking(id, user, response.getInt("crcStats"), changes.keys.toIntArray(), changes.values.toIntArray()) == 1) synchronized(this) {
                val current = prefs.getStringSet(pendingKey(user), emptySet()).orEmpty().toSet()
                prefs.edit().putStringSet(pendingKey(user), current - matched).commit()
            }
        } catch (e: Exception) { android.util.Log.w("SteamAchievements", "Sync pending; will retry", e) } finally { syncing.set(false) } }
    }
    @JvmStatic fun stop() { watcher?.stop(); watcher = null; job?.cancel(); job = null; overlay?.let { view -> view.post { view.disposeComposition(); (view.parent as? ViewGroup)?.removeView(view) } }; overlay = null; events.close(); events = Channel(16, BufferOverflow.DROP_OLDEST) }
}
