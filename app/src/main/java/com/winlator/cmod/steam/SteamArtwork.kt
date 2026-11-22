package com.winlator.cmod.steam

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.winlator.cmod.R
import org.json.JSONObject

internal object SteamArtwork {
    private val assetPath = Regex("(?:[0-9a-fA-F]{40}/)?[A-Za-z0-9_.-]+\\.(?:jpg|png)")
    private val iconHash = Regex("[0-9a-fA-F]{40}")

    fun defaultUrl(id: Int, kind: String): String =
        "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/${if (kind == "cover") "library_600x900_2x.jpg" else "header.jpg"}"

    fun fromCommon(id: Int, common: JSONObject, kind: String): List<String> {
        val assets = common.optJSONObject("library_assets_full")
        fun localized(node: JSONObject?) = node?.optString("english").orEmpty().ifBlank {
            node?.let { values -> values.keys().asSequence().map(values::optString).firstOrNull { assetPath.matches(it) } }.orEmpty()
        }
        fun asset(name: String, variant: String) = localized(assets?.optJSONObject(name)?.optJSONObject(variant))
        val header = localized(common.optJSONObject("header_image"))
        val art = if (kind == "cover") listOf(asset("library_capsule", "image2x"), asset("library_capsule", "image"), header)
            else listOf(header, asset("library_header", "image"), localized(common.optJSONObject("small_capsule")))
        return art.filter { assetPath.matches(it) }
            .map { "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/$id/$it" } +
            listOfNotNull(common.optString("icon").takeIf { iconHash.matches(it) && it.any { c -> c != '0' } }
                ?.let { "https://shared.fastly.steamstatic.com/community_assets/images/apps/$id/$it.jpg" })
    }

    fun fromSnapshot(art: JSONObject, kind: String): List<String> {
        val keys = if (kind == "cover") listOf("library_capsule_2x", "library_capsule", "header", "icon")
            else listOf("header", "library_header", "small_capsule", "icon")
        return keys.map(art::optString).filter(::isSteamAsset)
    }

    fun isSteamAsset(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.host?.endsWith(".steamstatic.com") == true
    }.getOrDefault(false)

    fun urls(url: String): List<String> {
        val match = Regex("https://shared\\.steamstatic\\.com/store_item_assets/steam/apps/(\\d+)/([^/?]+)$").matchEntire(url)
            ?: return listOf(url)
        val (id, image) = match.destructured
        val base = "https://cdn.akamai.steamstatic.com/steam/apps/$id"
        return listOf(url, "$base/$image", "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg", "$base/header.jpg").distinct()
    }

    fun request(context: Context, url: String): RequestBuilder<Drawable> {
        val glide = Glide.with(context)
        if (url.isBlank()) return glide.load(R.drawable.ic_steam)
        return urls(url).asReversed().fold<String, RequestBuilder<Drawable>?>(null) { fallback, candidate ->
            glide.load(candidate).let { if (fallback == null) it.error(R.drawable.ic_steam) else it.error(fallback) }
        }!!
    }
}
