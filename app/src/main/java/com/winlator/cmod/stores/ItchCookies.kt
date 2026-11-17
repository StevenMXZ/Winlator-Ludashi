package com.winlator.cmod.stores

import android.content.Context
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray

internal object ItchCookies {
    private fun read(context: Context): List<Cookie> {
        val account = StoreVault.load(context, LudashiStore.ITCH) ?: return emptyList()
        val rows = account.optJSONArray("cookies")
        if (rows != null) return (0 until rows.length()).mapNotNull {
            val entry = rows.optJSONObject(it) ?: return@mapNotNull null
            runCatching { Cookie.parse(entry.getString("origin").toHttpUrl(), entry.getString("cookie")) }.getOrNull()
        }
        return account.optString("cookie").split(';').mapNotNull { field ->
            val name = field.substringBefore('=').trim()
            val value = field.substringAfter('=', "").trim()
            if (name.isBlank() || value.isBlank()) null else runCatching { Cookie.Builder().name(name).value(value).domain("itch.io").path("/").secure().build() }.getOrNull()
        }
    }
    fun headerFor(cookies: List<Cookie>, url: HttpUrl, now: Long = System.currentTimeMillis()): String {
        if (!StoreNetwork.trustedItch(url.toString())) return ""
        return cookies.filter { it.expiresAt > now && it.matches(url) }.joinToString("; ") { "${it.name}=${it.value}" }
    }
    fun header(context: Context, url: HttpUrl): String = headerFor(read(context), url)
    @Synchronized fun accept(context: Context, url: HttpUrl, headers: Headers) {
        val account = StoreVault.load(context, LudashiStore.ITCH) ?: return
        val incoming = Cookie.parseAll(url, headers).filter { it.domain == "itch.io" || it.domain.endsWith(".itch.io") }
        if (incoming.isEmpty()) return
        val cookies = read(context).associateBy { "${it.domain}|${it.path}|${it.name}" }.toMutableMap()
        incoming.forEach { cookies["${it.domain}|${it.path}|${it.name}"] = it }
        val rows = JSONArray()
        cookies.values.filter { it.expiresAt > System.currentTimeMillis() }.forEach { rows.put(org.json.JSONObject().put("origin", "https://${it.domain}").put("cookie", it.toString())) }
        account.put("cookies", rows)
        StoreVault.save(context, LudashiStore.ITCH, account)
    }
}
