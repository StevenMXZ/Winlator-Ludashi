package com.winlator.cmod.steam

import android.content.Context
import android.content.Intent
import com.bumptech.glide.Glide
import com.winlator.cmod.ui.library.LibraryItem
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object SteamLibrary {
    private val artworkRevision = androidx.compose.runtime.mutableIntStateOf(0)
    val artworkVersion get() = artworkRevision.intValue
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val invalidateArtwork = Runnable { artworkRevision.intValue++ }
    @JvmStatic fun shortcutAppId(shortcut: com.winlator.cmod.container.Shortcut): Int? = shortcut.getExtra("steamAppId").toIntOrNull()
    private val images = Executors.newFixedThreadPool(2)
    private val requested = ConcurrentHashMap.newKeySet<String>()
    fun appId(path: String): Int? = path.removePrefix("steam://").takeIf { path.startsWith("steam://") }?.toIntOrNull()
    fun items(context: Context, json: String, filterType: String = "game"): List<LibraryItem> {
        val apps = runCatching { JSONObject(json).optJSONArray("owned_apps") }.getOrNull()
        val owned = (0 until (apps?.length() ?: 0)).mapNotNull { index ->
            apps?.optJSONObject(index)?.takeIf { it.optInt("id") > 0 }
        }
        val records = File(context.filesDir, "steam-library").listFiles().orEmpty().filter {
            it.isFile && it.extension == "json" && it.nameWithoutExtension.toIntOrNull() != null
        }
        val installed = records.associate { file ->
            val id = file.nameWithoutExtension.toInt()
            val common = runCatching { JSONObject(file.readText()).optJSONObject("common") }.getOrNull()
            id to JSONObject().put("id", id)
                .put("type", common?.optString("type").orEmpty().ifBlank { "game" })
                .put("name", common?.optString("name").orEmpty().ifBlank { "Steam game" })
        }
        val artworkNames = File(context.cacheDir, "steam-art").list()?.toSet().orEmpty()
        val preferences = context.getSharedPreferences("steam-settings", 0)
        return (owned + installed.values).distinctBy { it.optInt("id") }.mapNotNull { app ->
            val id = app.optInt("id")
            val local = installed[id]
            val type = app.optString("type").trim().ifBlank { local?.optString("type").orEmpty() }
                .ifBlank { if (local != null) "game" else "" }
            if (!type.equals(filterType, true)) return@mapNotNull null
            LibraryItem("steam-$id", "steam://$id",
                app.optString("name").ifBlank { local?.optString("name").orEmpty() }.ifBlank { "App $id" },
                if (local != null) "Steam · Installed" else "Steam · Not installed",
                artwork(context, id, "cover").takeIf { "$id-cover.jpg" in artworkNames }?.path,
                artwork(context, id, "banner").takeIf { "$id-banner.jpg" in artworkNames }?.path,
                null, null, preferences.getBoolean("favorite-$id", false), 0L)
        }
    }
    private fun artwork(context: Context, id: Int, kind: String) = File(context.cacheDir, "steam-art/$id-$kind.jpg")
    fun requestArtwork(context: Context, path: String, kind: String) {
        val id = appId(path) ?: return
        val type = if (kind.contains("banner", true)) "banner" else "cover"
        val target = artwork(context, id, type)
        if (target.isFile || !requested.add("$id-$type")) return
        val app = context.applicationContext
        images.execute {
            try {
                val image = if (type == "cover") "library_600x900_2x.jpg" else "header.jpg"
                val request = Glide.with(app).downloadOnly().load("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/$image").submit()
                try {
                    val file = request.get(25, java.util.concurrent.TimeUnit.SECONDS)
                    target.parentFile?.mkdirs(); file.copyTo(target, true)
                    main.removeCallbacks(invalidateArtwork); main.postDelayed(invalidateArtwork, 500)
                } finally { Glide.with(app).clear(request) }
            } catch (_: Exception) { requested.remove("$id-$type") }
        }
    }
    @JvmStatic fun showMenu(context: Context) {
        SteamSheets.menu(context)
    }

    fun open(context: Context, path: String) {
        val id = appId(path)
        if (id != null && File(context.filesDir, "steam-library/$id.json").isFile && context is com.winlator.cmod.MainActivity) {
            context.supportFragmentManager.beginTransaction()
                .replace(com.winlator.cmod.R.id.FLFragmentContainer, SteamGameDetailFragment.newInstance(id))
                .addToBackStack("steam-game").commit()
            return
        }
        appId(path)?.let { SteamSheets.game(context, it) }
    }
}
