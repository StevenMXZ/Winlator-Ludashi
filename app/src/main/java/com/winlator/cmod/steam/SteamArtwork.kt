package com.winlator.cmod.steam

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.winlator.cmod.R

internal object SteamArtwork {
    fun urls(url: String): List<String> {
        val match = Regex("https://shared\\.steamstatic\\.com/store_item_assets/steam/apps/(\\d+)/([^/?]+)$").matchEntire(url)
            ?: return listOf(url)
        val (id, image) = match.destructured
        val base = "https://cdn.akamai.steamstatic.com/steam/apps/$id"
        return listOf(url, "$base/$image", "https://shared.steamstatic.com/store_item_assets/steam/apps/$id/header.jpg", "$base/header.jpg").distinct()
    }

    fun request(context: Context, url: String): RequestBuilder<Drawable> {
        val glide = Glide.with(context)
        return urls(url).asReversed().fold<String, RequestBuilder<Drawable>?>(null) { fallback, candidate ->
            glide.load(candidate).let { if (fallback == null) it.error(R.drawable.ic_steam) else it.error(fallback) }
        }!!
    }
}
