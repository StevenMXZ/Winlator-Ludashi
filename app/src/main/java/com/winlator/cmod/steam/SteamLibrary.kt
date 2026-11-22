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
    private val artworkFailures = android.util.LruCache<String, Long>(256)
    fun appId(path: String): Int? = path.removePrefix("steam://").takeIf { path.startsWith("steam://") }?.toIntOrNull()

    internal fun exportShortcut(context: Context, appId: Int) {
        val shortcut = SteamGames.shortcut(context, appId)
        if (shortcut != null && shortcut.getExtra("steamConfigured") == "1") {
            com.winlator.cmod.ui.library.ShortcutExporter.export(context, shortcut)
        } else {
            android.widget.Toast.makeText(context, com.winlator.cmod.ui.uiStr(com.winlator.cmod.R.string.steam_ui_configure_game_first),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun added(context: Context, id: Int) = context.getSharedPreferences("steam-settings", 0).getBoolean("library-added-$id", false)
    fun setAdded(context: Context, id: Int, value: Boolean) {
        require(id > 0)
        context.getSharedPreferences("steam-settings", 0).edit().putBoolean("library-added-$id", value).apply()
        SteamGames.changed()
    }
    private fun ownedApps(json: String): Sequence<JSONObject> {
        val apps = runCatching { JSONObject(json).optJSONArray("owned_apps") }.getOrNull() ?: return emptySequence()
        return (0 until apps.length()).asSequence().mapNotNull { apps.optJSONObject(it)?.takeIf { app -> app.optInt("id") > 0 } }
    }
    internal fun ownsApp(json: String, id: Int): Boolean = ownedApps(json).any { it.optInt("id") == id }

    fun items(context: Context, json: String, filterType: String = "game", mainLibraryOnly: Boolean = false, includeDemos: Boolean = false): List<LibraryItem> {
        val owned = ownedApps(json).toList()
        val records = File(context.filesDir, "steam-library").listFiles().orEmpty().filter {
            it.isFile && it.extension == "json" && it.nameWithoutExtension.toIntOrNull() != null
        }
        val installed = records.associate { file ->
            val id = file.nameWithoutExtension.toInt()
            val common = runCatching { JSONObject(file.readText()).optJSONObject("common") }.getOrNull()
            id to JSONObject().put("id", id)
                .put("type", common?.optString("type").orEmpty())
                .put("name", common?.optString("name").orEmpty().ifBlank { "Steam game" })
        }
        val artworkNames = File(context.cacheDir, "steam-art").list()?.toSet().orEmpty()
        val preferences = context.getSharedPreferences("steam-settings", 0)
        return (owned + installed.values).distinctBy { it.optInt("id") }.mapNotNull { app ->
            val id = app.optInt("id")
            val local = installed[id]
            if (mainLibraryOnly && local == null && !preferences.getBoolean("library-added-$id", false)) return@mapNotNull null
            val type = local?.optString("type").orEmpty().trim().ifBlank { app.optString("type").trim() }
                .ifBlank { if (local != null) "game" else "" }
            if (!type.equals(filterType, true) && !(includeDemos && type.equals("demo", true))) return@mapNotNull null
            LibraryItem("steam-$id", "steam://$id",
                app.optString("name").ifBlank { local?.optString("name").orEmpty() }.ifBlank { "App $id" },
                if (local != null) "Steam · Installed" else "Steam · Not installed",
                artwork(context, id, "cover").takeIf { "$id-cover.jpg" in artworkNames }?.path,
                artwork(context, id, "banner").takeIf { "$id-banner.jpg" in artworkNames }?.path,
                null, null, preferences.getBoolean("favorite-$id", false), 0L)
        }
    }
    private fun artwork(context: Context, id: Int, kind: String) = File(context.cacheDir, "steam-art/$id-$kind.jpg")

    internal fun artworkPath(context: Context, id: Int, kind: String) = artwork(context, id, kind).takeIf { it.isFile }?.path

    fun requestArtwork(context: Context, path: String, kind: String) {
        val id = appId(path) ?: return
        val type = if (kind.contains("banner", true)) "banner" else "cover"
        val app = context.applicationContext
        val target = artwork(app, id, type)
        val key = target.path
        if (target.isFile || (artworkFailures.get(key) ?: 0L) > android.os.SystemClock.elapsedRealtime() || !requested.add(key)) return
        images.execute {
            val tried = mutableSetOf<String>()
            fun save(urls: List<String>): Boolean = urls.any { url ->
                if (!tried.add(url)) return@any false
                val request = Glide.with(app).downloadOnly().load(url).submit()
                try {
                    val file = request.get(12, java.util.concurrent.TimeUnit.SECONDS)
                    if (file.length() == 0L) return@any false
                    val temporary = File(target.parentFile, target.name + ".pending")
                    target.parentFile?.mkdirs()
                    try { file.copyTo(temporary, true); check(temporary.renameTo(target)) }
                    finally { temporary.delete() }
                    main.removeCallbacks(invalidateArtwork); main.postDelayed(invalidateArtwork, 500)
                    true
                } catch (_: java.util.concurrent.ExecutionException) { false }
                catch (_: java.util.concurrent.TimeoutException) { false }
                finally { Glide.with(app).clear(request) }
            }
            try {
                val ownedArt = ownedApps(SteamSession.library).firstOrNull { it.optInt("id") == id }?.optJSONObject("art")
                val local = runCatching { SteamGames.info(app, id).optJSONObject("common") }.getOrNull()
                if (ownedArt != null && save(SteamArtwork.fromSnapshot(ownedArt, type))) return@execute
                if (local != null && save(SteamArtwork.fromCommon(id, local, type))) return@execute
                if (save(SteamArtwork.urls(SteamArtwork.defaultUrl(id, type)))) return@execute
                val live = if (SteamSession.signedIn && !SteamSession.gameActive) runCatching {
                    SteamSession.info(id).optJSONObject("common")
                }.getOrNull() else null
                if (live != null && save(SteamArtwork.fromCommon(id, live, type))) return@execute
                val store = runCatching { SteamStoreMetadata.fetch(id) }.getOrNull()
                if (store != null && save(listOf(store.optString("header_image"), store.optString("capsule_image")).filter(SteamArtwork::isSteamAsset))) return@execute
            } catch (error: Exception) {
                android.util.Log.d("SteamArtwork", "Could not cache Steam artwork for app $id", error)
            } finally {
                if (!target.isFile) artworkFailures.put(key, android.os.SystemClock.elapsedRealtime() + 60_000)
                requested.remove(key)
            }
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
