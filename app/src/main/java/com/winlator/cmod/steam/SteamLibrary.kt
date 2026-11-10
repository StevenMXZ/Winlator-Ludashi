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
    fun items(context: Context, json: String, filterType: String = "game"): List<LibraryItem> = runCatching {
        val apps = JSONObject(json).optJSONArray("owned_apps") ?: org.json.JSONArray()
        val owned = (0 until apps.length()).map { apps.getJSONObject(it) }
        val ownedIds = owned.map { it.getInt("id") }.toSet()
        val records = File(context.filesDir, "steam-library").listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension.toIntOrNull() != null && it.extension == "json" }
        val installedIds = records.map { it.nameWithoutExtension.toInt() }.toSet()
        val artworkNames = File(context.cacheDir, "steam-art").list()?.toSet().orEmpty()
        val installed = records.filter { it.nameWithoutExtension.toInt() !in ownedIds }.mapNotNull { file ->
            runCatching { val info = JSONObject(file.readText()); JSONObject().put("id", file.nameWithoutExtension.toInt()).put("type", info.optJSONObject("common")?.optString("type").orEmpty().ifBlank { "game" })
                .put("name", info.optJSONObject("common")?.optString("name").orEmpty().ifBlank { "Steam game" }) }.getOrNull()
        }
        (owned + installed).distinctBy { it.getInt("id") }.filter {
            it.optString("type").equals(filterType, true)
        }.map { app ->
            val id = app.getInt("id")
            val installed = id in installedIds
            LibraryItem("steam-$id", "steam://$id", app.optString("name").ifBlank { "App $id" },
                if (installed) "Steam · Installed" else "Steam · Not installed",
                artwork(context, id, "cover").takeIf { "$id-cover.jpg" in artworkNames }?.path,
                artwork(context, id, "banner").takeIf { "$id-banner.jpg" in artworkNames }?.path,
                null, null, context.getSharedPreferences("steam-settings", 0).getBoolean("favorite-$id", false), 0L)
        }
    }.getOrDefault(emptyList())
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
