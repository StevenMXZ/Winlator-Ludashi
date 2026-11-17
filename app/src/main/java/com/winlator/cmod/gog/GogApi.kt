package com.winlator.cmod.gog

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object GogApi {
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    fun cancelWork() { val active = connections.toList(); if (active.isEmpty()) return; com.winlator.cmod.stores.StoreNetwork.closeOffMain { active.forEach { connection -> runCatching { connection.disconnect() } } } }
    private fun track(connection: HttpURLConnection) {
        check(!com.winlator.cmod.stores.StoreSessions.paused) { "Store requests are paused while a game runs" }
        connections.add(connection)
        if (com.winlator.cmod.stores.StoreSessions.paused) { connections.remove(connection); connection.disconnect(); error("Store request cancelled") }
    }
    const val CLIENT_ID = "46899977096215655"
    private const val CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
    const val REDIRECT = "https://embed.gog.com/on_login_success?origin=client"
    fun loginUrl(state: String) = Uri.parse("https://auth.gog.com/auth").buildUpon()
        .appendQueryParameter("client_id", CLIENT_ID).appendQueryParameter("redirect_uri", REDIRECT)
        .appendQueryParameter("response_type", "code").appendQueryParameter("layout", "client2")
        .appendQueryParameter("state", state).build().toString()

    fun trusted(url: String): Boolean = runCatching { URL(url).let { it.protocol == "https" && (it.host == "gog.com" || it.host.endsWith(".gog.com")) } }.getOrDefault(false)
    fun callback(url: String, state: String): String? {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "embed.gog.com" || uri.path != "/on_login_success") return null
        check(uri.getQueryParameter("state") == state) { "GOG sign-in state did not match. Please retry." }
        return uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
    }
    fun token(value: String, refresh: Boolean): JSONObject {
        val uri = Uri.parse("https://auth.gog.com/token").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID).appendQueryParameter("client_secret", CLIENT_SECRET)
            .appendQueryParameter("grant_type", if (refresh) "refresh_token" else "authorization_code")
            .appendQueryParameter(if (refresh) "refresh_token" else "code", value)
            .appendQueryParameter("redirect_uri", REDIRECT).build().toString()
        return json(uri).also { check(it.optString("access_token").isNotBlank() && it.optString("refresh_token").isNotBlank()) { "GOG returned incomplete sign-in details" } }
    }
    private class Unauthorized : Exception()
    private fun json(url: String, bearer: String? = null): JSONObject {
        require(trusted(url)) { "Invalid GOG API address" }
        val connection = URL(url).openConnection() as HttpURLConnection
        track(connection)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "Ludashi/5.0")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
        return try {
            if (connection.responseCode == 401) throw Unauthorized()
            check(connection.responseCode == 200) { "GOG request failed (HTTP ${connection.responseCode})" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connections.remove(connection); connection.disconnect() }
    }
    fun product(id: String, details: Boolean = false): JSONObject {
        require(id.toLongOrNull()?.let { it > 0 } == true)
        return json("https://api.gog.com/products/$id" + if (details) "?expand=description,screenshots,downloads" else "")
    }
    @Synchronized fun get(context: Context, url: String): JSONObject {
        check(GogSettings.enabled(context)) { "GOG integration is disabled" }
        val account = GogAccount.load(context) ?: error("Sign in to GOG first")
        return try { json(url, account.getString("access_token")) }
        catch (_: Unauthorized) {
            val renewed = token(account.getString("refresh_token"), true)
            if (!renewed.has("user_id")) renewed.put("user_id", account.getString("user_id"))
            GogAccount.save(context, renewed)
            json(url, renewed.getString("access_token"))
        }
    }
    fun library(context: Context, progress: (Int) -> Unit): List<GogGame> = libraryPages({ page ->
        get(context, "https://embed.gog.com/account/getFilteredProducts?mediaType=1&sortBy=title&system=windows&page=$page")
    }, progress)
    fun libraryPages(fetch: (Int) -> JSONObject, progress: (Int) -> Unit): List<GogGame> {
        val games = linkedMapOf<String, GogGame>()
        var page = 1
        do {
            val response = fetch(page)
            val products = response.getJSONArray("products")
            for (i in 0 until products.length()) {
                val game = GogGame.parse(products.getJSONObject(i))
                games[game.id] = game
            }
            progress(games.size)
            val pages = response.getInt("totalPages")
            require(pages in 0..10000) { "Invalid GOG library pagination" }
            page++
        } while (page <= pages)
        return games.values.toList()
    }
    fun installers(product: JSONObject): List<JSONObject> {
        val array = product.optJSONObject("downloads")?.optJSONArray("installers") ?: JSONArray()
        return (0 until array.length()).map { array.getJSONObject(it) }.filter {
            it.optString("os").equals("windows", true) && (it.optJSONArray("files")?.length() ?: 0) > 0
        }
    }
    fun safeFile(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it in "/\\\r\n\u0000" }) { "Invalid GOG download filename" }
        return name
    }
    fun fileName(url: String): String {
        val uri = Uri.parse(url)
        val path = uri.getQueryParameter("path") ?: uri.path ?: error("Missing GOG filename")
        return safeFile(path.substringAfterLast('/'))
    }
    data class Integrity(val size: Long = 0, val md5: String? = null)
    fun checksum(xml: String): Integrity {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(xml.reader())
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "file") {
                val size = parser.getAttributeValue(null, "total_size")?.toLongOrNull() ?: 0
                val md5 = parser.getAttributeValue(null, "md5")?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
                require(size > 0 && md5 != null) { "Invalid GOG checksum metadata" }
                return Integrity(size, md5)
            }
            parser.next()
        }
        error("Missing GOG checksum metadata")
    }
    fun integrity(context: Context, link: JSONObject): Integrity {
        val address = link.optString("checksum")
        if (address.isBlank()) return Integrity()
        var url = address
        repeat(9) {
            val target = URL(url)
            require(target.protocol == "https") { "GOG checksum requires HTTPS" }
            val connection = target.openConnection() as HttpURLConnection
            track(connection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 30000
            if (trusted(url)) GogAccount.load(context)?.optString("access_token")?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }
            try {
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    url = URL(target, connection.getHeaderField("Location") ?: error("Missing checksum redirect")).toString()
                } else {
                    check(connection.responseCode == 200) { "GOG checksum request failed (HTTP ${connection.responseCode})" }
                    return checksum(connection.inputStream.bufferedReader().use { it.readText() })
                }
            } finally { connections.remove(connection); connection.disconnect() }
        }
        error("Too many GOG checksum redirects")
    }
    fun verifiedLength(transport: Long, integrity: Integrity): Long {
        if (transport > 0 && integrity.size > 0) check(transport == integrity.size) { "GOG download size disagrees with its checksum" }
        val length = integrity.size.takeIf { it > 0 } ?: transport
        check(length > 0 || integrity.md5 != null) { "GOG did not provide a verifiable file size or checksum" }
        return length
    }
    // Artwork may use chunked HTTP; it is bounded and validated as an image,
    // rather than weakening the size/checksum requirements for game installers.
    fun artwork(url: String, file: File, allowed: () -> Boolean) {
        var address = url
        for (redirect in 0..8) {
            check(allowed()) { "GOG artwork cancelled" }
            val target = URL(address)
            require(target.protocol == "https")
            val connection = target.openConnection() as HttpURLConnection
            track(connection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            try {
                check(allowed()) { "GOG artwork cancelled" }
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    address = URL(target, connection.getHeaderField("Location") ?: error("Missing artwork redirect")).toString()
                    continue
                }
                check(connection.responseCode == 200) { "GOG artwork request failed" }
                connection.inputStream.use { receiveArtwork(it, file, connection.contentLengthLong, allowed) }
                return
            } finally { connections.remove(connection); connection.disconnect() }
        }
        error("Too many GOG artwork redirects")
    }
    internal fun receiveArtwork(input: java.io.InputStream, file: File, expected: Long, allowed: () -> Boolean) {
        check(expected <= 20 * 1024 * 1024) { "GOG artwork is too large" }
        var done = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(allowed()) { "GOG artwork cancelled" }
                val count = input.read(buffer); if (count < 0) break
                done += count
                check(done <= 20 * 1024 * 1024) { "GOG artwork is too large" }
                check(allowed()) { "GOG artwork cancelled" }
                output.write(buffer, 0, count)
            }
        }
        check(allowed() && done > 0 && (expected <= 0 || expected == done)) { "Incomplete GOG artwork" }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "GOG artwork is not an image" }
    }
    fun download(url: String, file: File, integrity: Integrity, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        var address = url
        val offset = file.length()
        var connection: HttpURLConnection? = null
        try {
            for (redirect in 0..8) {
                val target = URL(address)
                require(target.protocol == "https") { "GOG download requires HTTPS" }
                val current = target.openConnection() as HttpURLConnection
                connection = current
                track(current)
                current.instanceFollowRedirects = false
                current.connectTimeout = 15_000; current.readTimeout = 30_000
                current.setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) current.setRequestProperty("Range", "bytes=$offset-")
                if (current.responseCode in listOf(301, 302, 303, 307, 308)) {
                    address = URL(target, current.getHeaderField("Location") ?: error("Missing download redirect")).toString()
                    connections.remove(current); current.disconnect()
                    continue
                }
                val resume = offset > 0 && current.responseCode == 206
                check(current.responseCode == 200 || resume) { "GOG download failed (HTTP ${current.responseCode})" }
                val length = if (resume) {
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(current.getHeaderField("Content-Range").orEmpty())
                        ?: error("Invalid GOG download range")
                    check(range.groupValues[1].toLong() == offset) { "Invalid GOG download offset" }
                    verifiedLength(range.groupValues[3].toLong(), integrity)
                } else verifiedLength(current.contentLengthLong, integrity)
                current.inputStream.use { receive(it, file, length, integrity.md5, cancelled, { done -> progress(done, length) }, if (resume) offset else 0) }
                return
            }
            error("Too many GOG download redirects")
        } catch (error: Exception) { if (!cancelled() || file.length() == 0L) file.delete(); throw error }
        finally { connection?.let { connections.remove(it); it.disconnect() } }
    }
    fun verifyFile(file: File, integrity: Integrity): Boolean {
        if (integrity.size <= 0 || file.length() != integrity.size || integrity.md5.isNullOrBlank()) return false
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                check(!GogLibrary.cancelled) { "Download paused" }
                val count = input.read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(integrity.md5, true)
    }
    fun receive(input: java.io.InputStream, file: File, expected: Long, md5: String?, cancelled: () -> Boolean, progress: (Long) -> Unit, offset: Long = 0) {
        val digest = MessageDigest.getInstance("MD5")
        var received = offset
        try {
            if (offset > 0) {
                check(file.length() == offset) { "GOG partial file changed" }
                file.inputStream().use { retained ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        check(!cancelled()) { "Download paused" }
                        val count = retained.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
            java.io.FileOutputStream(file, offset > 0).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    check(!cancelled()) { "Download paused" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    received += count; progress(received)
                }
                output.fd.sync()
            }
            check(expected <= 0 || received == expected) { "Incomplete GOG download. Please retry." }
            if (!md5.isNullOrBlank()) check(digest.digest().joinToString("") { "%02x".format(it) }.equals(md5, true)) { "GOG download checksum did not match" }
        } catch (error: Exception) { if (!cancelled() || file.length() == 0L) file.delete(); throw error }
    }

}

internal data class GogGame(val id: String, val title: String, val image: String) {
    fun json() = JSONObject().put("id", id).put("title", title).put("image", image)
    companion object {
        fun imageUrl(value: String): String {
            var url = if (value.startsWith("//")) "https:$value" else value
            if (!url.startsWith("https://")) return ""
            url = url.replace("{formatter}", "product_card_v2_mobile_slider_639")
                .replace("images.gog.com/", "images.gog-statics.com/")
            val parsed = URL(url)
            if ((parsed.host == "gog-statics.com" || parsed.host.endsWith(".gog-statics.com")) && !parsed.path.substringAfterLast('/').contains('.')) {
                url = Uri.parse(url).buildUpon().path(parsed.path + ".jpg").build().toString()
            }
            return url
        }
        fun parse(json: JSONObject): GogGame {
            val id = json.opt("id")?.toString().orEmpty()
            require(id.toLongOrNull()?.let { it > 0 } == true) { "Invalid GOG game ID" }
            return GogGame(id, json.getString("title"), imageUrl(json.optString("image")))
        }
    }
}
