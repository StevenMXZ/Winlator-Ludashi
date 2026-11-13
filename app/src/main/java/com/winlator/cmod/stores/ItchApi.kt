package com.winlator.cmod.stores

import android.content.Context
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal object ItchApi {
    const val KEYS = "https://itch.io/user/settings/api-keys"
    private const val BASE = "https://api.itch.io"
    fun key(context: Context) = StoreVault.load(context, LudashiStore.ITCH)?.optString("apiKey").orEmpty()
    internal fun get(context: Context, path: String, token: String = key(context), tag: String? = null): JSONObject {
        require(token.matches(Regex("[A-Za-z0-9]{40,128}"))) { "Enter a valid itch.io API key" }
        return StoreNetwork.request(context, LudashiStore.ITCH, Request.Builder().url(BASE + path).header("Authorization", "Bearer $token").tag(tag).build()) { response ->
            JSONObject(response.body?.string() ?: error("Empty itch.io response")).also {
                check((it.optJSONArray("errors")?.length() ?: 0) == 0 && !it.has("error")) { "itch.io rejected this request. Check the API key and game access." }
            }
        }
    }
    fun signIn(context: Context, token: String) {
        val clean = token.trim()
        val user = get(context, "/profile", clean).getJSONObject("user")
        require(user.optLong("id") > 0) { "itch.io did not identify your account" }
        StoreVault.save(context, LudashiStore.ITCH, JSONObject().put("apiKey", clean).put("account", user.getLong("id").toString()).put("username", user.optString("username")))
    }
    fun games(rows: JSONArray): List<StoreGame> = (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val game = row.optJSONObject("game") ?: return@mapNotNull null
        val url = game.optString("url")
        val canonical = runCatching { ItchConnector.gameUrl(url) }.getOrNull() ?: return@mapNotNull null
        if (game.optLong("id") <= 0 || game.optString("title").isBlank()) return@mapNotNull null
        StoreGame(LudashiStore.ITCH, canonical, game.getString("title"), game.optString("coverUrl").ifBlank { game.optString("cover_url") }.ifBlank { game.optString("stillCoverUrl") }.ifBlank { game.optString("still_cover_url") }, JSONObject()
            .put("itchId", game.getLong("id").toString()).put("downloadKey", row.optString("id")).put("owned", row.optString("id").isNotBlank()).put("free", game.optLong("minPrice", game.optLong("min_price", 0)) == 0L)
            .put("platforms", game.optJSONObject("platforms")).put("traits", game.optJSONArray("traits")).put("tags", game.optJSONArray("tags"))
            .put("minPrice", game.optLong("minPrice", game.optLong("min_price", 0))).put("sale", game.optJSONObject("sale"))
            .put("information", JSONObject().put("description", game.optString("shortText").ifBlank { game.optString("short_text") }).put("developer", game.optJSONObject("user")?.let { it.optString("displayName").ifBlank { it.optString("display_name") }.ifBlank { it.optString("username") } }.orEmpty()) ))
    }
    fun library(context: Context): List<StoreGame> {
        val all = linkedMapOf<String, StoreGame>()
        val pages = mutableSetOf<String>()
        var received = 0L
        for (page in 1..10000) {
            val body = get(context, "/profile/owned-keys?page=$page&per_page=500")
            val rows = ownedRows(body)
            if (rows.length() == 0) return all.values.toList()
            check(pages.add(rows.toString())) { "itch.io repeated a library page" }
            received += rows.length()
            games(rows).forEach { all[it.id] = it }
            val total = body.optLong("total", -1)
            if (total >= 0 && received >= total) return all.values.toList()
        }
        error("itch.io library pagination did not finish")
    }
    fun ownedRows(body: JSONObject): JSONArray {
        body.optJSONArray("ownedKeys")?.let { return it }
        body.optJSONArray("owned_keys")?.let { return it }
        val empty = (body.has("ownedKeys") && body.isNull("ownedKeys")) || (body.has("owned_keys") && body.isNull("owned_keys")) ||
            body.keys().asSequence().all { it in setOf("page", "perPage", "per_page", "total") }
        check(empty) { "itch.io returned an unsupported library response" }
        return JSONArray()
    }
    fun search(context: Context, query: String, page: Int = 1, tag: String? = null): List<StoreGame> {
        val result = get(context, "/search/games?query=${android.net.Uri.encode(query)}&page=$page", tag = tag)
        val rows = result.optJSONArray("games") ?: JSONArray()
        return games(JSONArray().apply { for (i in 0 until rows.length()) put(JSONObject().put("game", rows.getJSONObject(i))) }).map { it.copy(metadata = it.metadata.put("catalog", true)) }
    }
    fun resolve(context: Context, game: StoreGame): StoreGame {
        if (game.metadata.optString("itchId").toLongOrNull()?.let { it > 0 } == true) return game
        for (page in 1..2) {
            val rows = search(context, game.title, page)
            rows.find { it.id == game.id }?.let { found -> return found.copy(metadata = JSONObject(game.metadata.toString()).put("itchId", found.metadata.getString("itchId"))) }
            if (rows.isEmpty()) break
        }
        val body = StoreNetwork.request(context, LudashiStore.ITCH, Request.Builder().url(ItchConnector.gameUrl(game.id)).header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36").build()) { it.body?.string().orEmpty() }
        val id = pageId(body) ?: error("itch.io did not identify this game. Open its store page or try again.")
        return game.copy(metadata = JSONObject(game.metadata.toString()).put("itchId", id))
    }
    fun pageId(html: String): String? = org.jsoup.Jsoup.parse(html).selectFirst("meta[name=itch:path]")?.attr("content")
        ?.let { Regex("^games/(\\d+)$").matchEntire(it)?.groupValues?.get(1) }
    private fun query(game: StoreGame) = game.metadata.optString("downloadKey").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
        ?.let { "?download_key_id=$it" }.orEmpty()
    fun uploads(context: Context, game: StoreGame): List<StoreUpload> {
        val id = game.metadata.optString("itchId")
        require(id.toLongOrNull()?.let { it > 0 } == true) { "Refresh your itch.io library to load this game" }
        return uploads(get(context, "/games/$id/uploads${query(game)}").optJSONArray("uploads") ?: JSONArray())
    }
    fun uploads(rows: JSONArray): List<StoreUpload> = (0 until rows.length()).mapNotNull { i ->
        val file = rows.getJSONObject(i)
        if (file.optString("storage") == "external") return@mapNotNull null
        val platforms = file.optJSONObject("platforms")
        val traits = file.optJSONArray("traits")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
        val windows = platforms?.optBoolean("windows") == true || "p_windows" in traits
        val other = listOf("linux", "osx", "android").any { platforms?.optBoolean(it) == true || "p_$it" in traits }
        if (!windows && other) return@mapNotNull null
        val name = file.optString("filename")
        if (listOf(".zip", ".7z", ".rar", ".exe", ".tar.gz", ".tar.xz").none { name.endsWith(it, true) }) return@mapNotNull null
        val id = file.optString("id").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: return@mapNotNull null
        StoreUpload(id, name, file.optLong("size", file.optLong("size_bytes")), file)
    }
    fun download(game: StoreGame, upload: StoreUpload) = "$BASE/uploads/${upload.id}/download${query(game)}"
}
