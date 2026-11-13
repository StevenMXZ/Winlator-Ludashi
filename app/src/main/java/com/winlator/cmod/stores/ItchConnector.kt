package com.winlator.cmod.stores

import android.content.Context
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

internal object ItchConnector {
    private val store = LudashiStore.ITCH
    fun gameUrl(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host?.endsWith(".itch.io") == true && uri.userInfo == null) { "Enter an HTTPS itch.io game URL" }
        val slug = uri.path.trim('/').split('/')
        require(slug.size == 1 && slug[0].matches(Regex("[A-Za-z0-9_.-]+"))) { "Enter the game's main page URL" }
        return "https://${uri.host}/${slug[0]}"
    }
    private fun html(context: Context, url: String, fields: Map<String, String>? = null, cookie: String? = null): String {
        require(StoreNetwork.trustedItch(url))
        val credentials = cookie ?: StoreVault.load(context, store)?.optString("cookie") ?: error("Sign in to itch.io first")
        val builder = Request.Builder().url(url).header("Cookie", credentials).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0.0.0 Safari/537.36")
            .header("Referer", url).header("Origin", "https://${URI(url).host}")
        if (fields != null) builder.post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
        return StoreNetwork.request(context, store, builder.build()) { it.body?.string() ?: error("Empty itch.io response") }
    }
    fun csrf(html: String): String = Jsoup.parse(html).selectFirst("meta[name=csrf_token]")?.let { it.attr("value").ifBlank { it.attr("content") } }
        ?.takeIf { it.isNotBlank() } ?: error("itch.io did not provide a session token. Sign in again.")
    fun signedIn(html: String) = Regex("I\\.current_user\\s*=\\s*\\{").containsMatchIn(html)
    fun capture(context: Context, cookie: String): Boolean {
        require(cookie.split(';').any { it.trim().startsWith("itchio=") && it.substringAfter('=').isNotBlank() })
        val previous = StoreVault.load(context, store)
        var accepted = false
        try {
            StoreVault.save(context, store, JSONObject().put("cookie", cookie).put("account", storeHash(cookie)))
            val page = html(context, "https://itch.io/my-purchases", cookie = cookie)
            if (!signedIn(page)) return false
            val document = Jsoup.parse(page)
            val profile = document.select(".user_panel_widget a[href]").firstOrNull { anchor -> runCatching {
                val uri = URI(anchor.attr("href")); uri.host?.endsWith(".itch.io") == true && uri.path.trim('/').isEmpty()
            }.getOrDefault(false) }?.attr("href")
            val account = StoreVault.load(context, store) ?: error("itch.io session was cancelled")
            account.put("account", profile ?: storeHash(cookie))
            StoreVault.save(context, store, account)
            accepted = true
            return true
        } finally {
            if (!accepted) { if (previous != null) StoreVault.save(context, store, previous) else StoreVault.clear(context, store) }
        }
    }

    data class Page(val games: List<StoreGame>, val next: String?)
    fun parseOwned(html: String, url: String): Page {
        val document = Jsoup.parse(html, url)
        val games = linkedMapOf<String, StoreGame>()
        val rows = document.select(".game_cell, .game_row[data-game_id], .purchase_row[data-game_id]")
        rows.forEach { row ->
            val anchor = row.select(".game_title a[href], .title[href], a[href]").firstOrNull { runCatching { gameUrl(it.absUrl("href")) }.isSuccess }
                ?: return@forEach
            val address = gameUrl(anchor.absUrl("href"))
            val title = row.selectFirst(".game_title, .title")?.text()?.takeIf { it.isNotBlank() } ?: anchor.text()
            if (title.isBlank()) return@forEach
            val image = row.selectFirst("img")?.let { it.attr("data-lazy_src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") } }.orEmpty()
            games[address] = StoreGame(store, address, title, normalizeImage(image))
        }
        val currentPage = android.net.Uri.parse(url).getQueryParameter("page")?.toIntOrNull() ?: 1
        val next = document.select("a[href]").mapNotNull { anchor ->
            val address = anchor.absUrl("href")
            if (!StoreNetwork.trustedItch(address)) null else {
                val nextPage = android.net.Uri.parse(address).getQueryParameter("page")?.toIntOrNull()
                address.takeIf { URI(it).path == URI(url).path && nextPage == currentPage + 1 }
            }
        }.firstOrNull()
        return Page(games.values.toList(), next)
    }
    fun library(context: Context): List<StoreGame> {
        if (ItchApi.key(context).isNotBlank()) return ItchApi.library(context)
        val games = linkedMapOf<String, StoreGame>()
        fun readPages(first: String) {
            var next: String? = first
            val seen = mutableSetOf<String>()
            while (next != null) {
                check(seen.add(next) && seen.size <= 10000) { "itch.io library repeated a page" }
                val body = html(context, next)
                check(signedIn(body)) { "Your itch.io session expired. Sign in again." }
                val page = parseOwned(body, next)
                page.games.forEach { games[it.id] = it }
                next = page.next
            }
        }
        readPages("https://itch.io/my-purchases")
        val collections = Jsoup.parse(html(context, "https://itch.io/my-collections"), "https://itch.io")
        collections.select("a[href]").filter { it.text().equals("Ludashi", true) && it.absUrl("href").startsWith("https://itch.io/c/") }
            .map { it.absUrl("href") }.distinct().forEach(::readPages)
        return games.values.toList()
    }
    fun lookup(context: Context, url: String): StoreGame {
        if (ItchApi.key(context).isNotBlank()) return ItchApi.lookup(context, url)
        val normalized = gameUrl(url)
        val page = Jsoup.parse(html(context, normalized), normalized)
        val title = page.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" by ")
            ?: page.selectFirst(".game_title")?.text() ?: error("Could not identify the itch.io game")
        val image = page.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        return StoreGame(store, normalized, title, normalizeImage(image), JSONObject().put("custom", true))
    }
    fun information(context: Context, url: String): JSONObject {
        val page = Jsoup.parse(html(context, gameUrl(url)))
        return JSONObject().put("description", page.selectFirst(".formatted_description")?.text().orEmpty())
            .put("developer", URI(url).host.substringBefore(".itch.io"))
    }
    fun uploads(context: Context, game: StoreGame): List<StoreUpload> {
        if (ItchApi.key(context).isNotBlank()) return ItchApi.uploads(context, game)
        val download = downloadPage(context, game.id)
        return parseUploads(html(context, download))
    }
    fun parseUploads(html: String): List<StoreUpload> {
        val document = Jsoup.parse(html)
        return document.select("[data-upload_id]").mapNotNull { link ->
            val id = link.attr("data-upload_id").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: return@mapNotNull null
            val row = link.closest(".upload") ?: link.parent() ?: return@mapNotNull null
            val name = sequenceOf(".upload_name strong.name", ".upload_name strong", ".upload_name .name", ".upload_name")
                .mapNotNull { row.selectFirst(it)?.text()?.trim()?.takeIf(String::isNotBlank) }.firstOrNull().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val windows = row.select(".icon-windows8, .icon-windows").isNotEmpty() || row.select("[title]").any { it.attr("title").contains("Windows", true) }
            val other = row.select(".icon-tux, .icon-apple, .icon-android").isNotEmpty()
            if (!windows && other) return@mapNotNull null
            val file = name.lowercase()
            if (listOf(".zip", ".7z", ".rar", ".exe", ".tar.gz", ".tar.xz").none { file.endsWith(it) }) return@mapNotNull null
            val label = row.selectFirst(".file_size")?.text().orEmpty()
            StoreUpload(id, name, size(label), JSONObject().put("label", label))
        }.distinctBy { it.id }
    }
    private fun size(text: String): Long {
        val match = Regex("([\\d.]+)\\s*(B|KB|MB|GB|TB)", RegexOption.IGNORE_CASE).find(text) ?: return 0
        val unit = listOf("B", "KB", "MB", "GB", "TB").indexOf(match.groupValues[2].uppercase())
        return (match.groupValues[1].toDouble() * Math.pow(1024.0, unit.toDouble())).toLong()
    }
    private fun downloadPage(context: Context, game: String): String {
        val normalized = gameUrl(game)
        val page = html(context, normalized)
        val result = JSONObject(html(context, "$normalized/download_url", mapOf("csrf_token" to csrf(page))))
        return result.optString("url").takeIf { StoreNetwork.trustedItch(it) } ?: error("itch.io did not grant access to this download. Check ownership and sign in again.")
    }
    fun file(context: Context, game: StoreGame, upload: StoreUpload): String {
        if (ItchApi.key(context).isNotBlank()) return ItchApi.download(game, upload)
        val page = downloadPage(context, game.id)
        val token = csrf(html(context, page))
        val response = JSONObject(html(context, "${gameUrl(game.id)}/file/${upload.id}?source=game_download&after_download_lightbox=1&as_props=1", mapOf("csrf_token" to token)))
        return response.optString("url").takeIf { it.startsWith("https://") } ?: error("itch.io did not provide a download URL")
    }
    private fun normalizeImage(url: String) = (if (url.startsWith("//")) "https:$url" else url).takeIf { it.startsWith("https://") }.orEmpty()
}
