package com.winlator.cmod.steam

import android.content.Context
import com.winlator.cmod.core.EnvVars
import com.winlator.cmod.feature.stores.steam.ludashisteam.LudashiSteamSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One account connection: native Android outside games, resident client during Client games. */
object SteamSocial {
    data class Friend(val id: Long, val name: String, val state: Int, val app: Int, val avatarHash: String = "")
    data class Message(val key: String, val text: String, val self: Boolean, val time: Long)
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends = _friends.asStateFlow()
    private val _messages = MutableStateFlow<Map<Long, List<Message>>>(emptyMap())
    val messages = _messages.asStateFlow()
    val status = MutableStateFlow("")
    private val queued = AtomicBoolean(false)
    private var requestedPersonas: LudashiSteamSession? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "SteamSocialIO").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    private val pending = SteamPendingMessages()
    fun enabled(context: Context) = SteamSettings.enabled(context) && context.getSharedPreferences("steam-settings", 0).getBoolean("social", false)
    fun appEvent(context: Context) {
        if (!enabled(context) || SteamSession.gameActive || !SteamSession.signedIn || !queued.compareAndSet(false, true)) return
        SteamSession.work {
            try {
                val s = SteamSession.session ?: return@work
                if (SteamSession.gameActive || !SteamSession.signedIn) return@work
                val ids = s.getFriendsList()
                if (requestedPersonas !== s && ids.isNotEmpty()) { requestedPersonas = s; s.requestFriendPersonas(ids) }
                val a = JSONArray(s.getFriendPersonas())
                _friends.value = roster(a, ids.toSet())
                val incoming = JSONArray(s.drainFriendMessages())
                for (i in 0 until incoming.length()) incoming.optJSONObject(i)?.let { m ->
                    if (m.optInt("type", 1) == 1) append(m.optLong("friendId"), m.optString("message"), m.optBoolean("fromSelf"), m.optLong("timestamp"), m.optString("ordinal"))
                }
                status.value = ""
            } catch (e: Exception) { status.value = e.message.orEmpty() }
            finally { queued.set(false) }
        }
    }
    internal fun roster(personas: JSONArray, ids: Set<Long>): List<Friend> =
        (0 until personas.length()).mapNotNull { friend(personas.optJSONObject(it)) }.filter { it.id in ids }.sortedBy { it.name.lowercase() }
    internal fun friend(o: JSONObject?): Friend? = o?.optString("sid")?.toLongOrNull()?.takeIf { it > 0 }?.let {
        Friend(it, o.optString("name").ifBlank { "Steam" }, o.optInt("state"), o.optInt("app"), o.optString("avatarHash"))
    }
    @Synchronized private fun append(id: Long, text: String, self: Boolean, time: Long, ordinal: String = "") {
        if (id <= 0 || text.isBlank()) return
        val key = "$time:$ordinal:$self:$text"
        val old = _messages.value[id].orEmpty()
        if (old.any { it.key == key }) return
        val all = _messages.value.toMutableMap()
        all[id] = (old + Message(key, text.take(4096), self, time)).takeLast(200)
        if (all.size > 50) all.remove(all.keys.first { it != id })
        _messages.value = all
    }
    fun history(context: Context, id: Long) {
        if (!enabled(context) || SteamSession.gameActive) return
        SteamSession.work { runCatching {
            if (SteamSession.gameActive) return@work
            val a = JSONArray(SteamSession.session?.getRecentMessages(id) ?: "[]")
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { append(id, it.optString("message"), it.optBoolean("fromSelf"), it.optLong("timestamp"), it.optString("ordinal")) }
        }.onFailure { status.value = it.message.orEmpty() } }
    }
    fun send(context: Context, id: Long, text: String): Boolean {
        if (!enabled(context) || text.isBlank() || text.length > 2048) return false
        if (SteamSession.gameActive) {
            val peer = socket ?: run { status.value = "Steam chat unavailable in this session"; return false }
            if (!pending.add(id, text)) { status.value = "Wait for pending messages"; return false }
            io.execute { runCatching { command(peer, JSONObject().put("cmd", "chat_send").put("sid", id.toString()).put("text", text)) }.onFailure { status.value = "Message not sent" } }
        } else {
            if (!SteamSession.signedIn) return false
            val transport = SteamSession.session ?: return false
            SteamSession.work {
            runCatching {
                check(!SteamSession.gameActive && SteamSession.session === transport)
                val raw = transport.sendFriendMessage(id, text) ?: error("Message not sent")
                val result = JSONObject(raw)
                check(result.has("serverTimestamp")) { "Message not sent" }
                append(id, text, true, result.optLong("serverTimestamp", System.currentTimeMillis()/1000), result.optString("ordinal"))
            }.onFailure { status.value = it.message.orEmpty() }
            }
        }
        return true
    }
    @Synchronized private fun command(peer: Socket, o: JSONObject) { val out = peer.getOutputStream(); out.write((o.toString()+"\n").toByteArray()); out.flush() }
    fun beginGuest(context: Context, env: EnvVars, appId: Int) {
        closeGuest()
        val nonce = UUID.randomUUID().toString()
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 120000 }
        server = listener
        env.put("LUDASHI_AGENT_PORT", listener.localPort); env.put("LUDASHI_AGENT_NONCE", nonce)
        env.put("LUDASHI_AGENT_FRIENDS", if (enabled(context)) "1" else "0")
        status.value = "Connecting to Steam client…"
        Thread({
            try {
                while (server === listener) {
                    val peer = listener.accept().apply { soTimeout = 3000 }
                    val input = peer.getInputStream().bufferedReader()
                    val hello = runCatching { JSONObject(readBounded(input) ?: "{}") }.getOrDefault(JSONObject())
                    if (hello.optString("ev") != "hello" || hello.optString("nonce") != nonce) { peer.close(); continue }
                    socket = peer; peer.soTimeout = 0; listener.close()
                    while (!peer.isClosed) { val line = readBounded(input) ?: break; if (server !== listener) break; runCatching { event(context, appId, JSONObject(line)) } }
                    break
                }
            } catch (_: Exception) { } finally {
                runCatching { listener.close() }
                if (server === listener) { runCatching { socket?.close() }; socket = null; status.value = "Steam relay disconnected" }
            }
        }, "SteamGuestEvents").apply { isDaemon = true }.start()
    }
    private fun readBounded(input: java.io.Reader): String? { val out = StringBuilder(); while (out.length < 262144) { val c = input.read(); if (c < 0) return null; if (c == 10) return out.toString(); out.append(c.toChar()) }; error("Oversized relay event") }
    private fun event(context: Context, appId: Int, o: JSONObject) {
        when (o.optString("ev")) {
            "friends" -> { val a = o.optJSONArray("list") ?: JSONArray(); val hashes = _friends.value.associate { it.id to it.avatarHash }; _friends.value = (0 until a.length()).mapNotNull { friend(a.optJSONObject(it)) }.map { it.copy(avatarHash = it.avatarHash.ifBlank { hashes[it.id].orEmpty() }) }; status.value = "" }
            "persona" -> friend(o.optJSONObject("friend"))?.let { f -> _friends.value = (_friends.value.filter { it.id != f.id } + f.copy(avatarHash = f.avatarHash.ifBlank { _friends.value.firstOrNull { it.id == f.id }?.avatarHash.orEmpty() })).sortedBy { it.name.lowercase() } }
            "friends_relay" -> status.value = if (o.optString("state") == "live") "" else o.optString("reason")
            "chat_in" -> append(o.optLong("sid"), o.optString("text"), false, o.optLong("ts"))
            "chat_sent" -> { val id = o.optLong("sid"); val text = pending.acknowledge(id); if (o.optBoolean("ok") && text != null) append(id, text, true, System.currentTimeMillis()/1000) else status.value = "Message not sent" }
            "achievement" -> SteamAchievements.surfaceUnlockPill(context, appId, o.optString("api"))
        }
    }
    fun reset() { closeGuest(); _friends.value = emptyList(); _messages.value = emptyMap(); requestedPersonas = null; status.value = "" }
    @JvmStatic fun closeGuest() { val old = server; server = null; runCatching { socket?.close(); old?.close() }; socket = null; pending.clear() }
}
