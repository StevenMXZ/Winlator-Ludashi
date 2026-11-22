package com.winlator.cmod.steam

import android.content.Context
import com.bumptech.glide.Glide
import com.winlator.cmod.stores.StoreSessions
import com.winlator.cmod.ui.library.LibraryItem
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object SteamLibrary {
    private val artworkRevision = androidx.compose.runtime.mutableIntStateOf(0)
    val artworkVersion get() = artworkRevision.intValue
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val invalidateArtwork = Runnable { artworkRevision.intValue++ }
    @JvmStatic fun shortcutAppId(shortcut: com.winlator.cmod.container.Shortcut): Int? = shortcut.getExtra("steamAppId").toIntOrNull()
    private val images = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val requested = ConcurrentHashMap<String, FutureTask<Unit>>()
    private val artworkFailures = android.util.LruCache<String, Long>(256)
    private var artworkSnapshot = ""
    private var artworkByApp = emptyMap<Int, JSONObject>()

    @Synchronized private fun ownedArtwork(id: Int): JSONObject? {
        val library = SteamSession.library
        if (artworkSnapshot != library) {
            artworkByApp = ownedApps(library).mapNotNull { app -> app.optJSONObject("art")?.let { app.optInt("id") to it } }.toMap()
        }
        artworkSnapshot = library
        return artworkByApp[id]
    }

    private fun artworkAllowed() = !StoreSessions.paused && !SteamSession.gameActive

    internal fun pauseArtwork() {
        requested.values.forEach { it.cancel(true) }
        images.purge()
    }
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
        if (!artworkAllowed()) return
        val id = appId(path) ?: return
        val type = if (kind.contains("banner", true)) "banner" else "cover"
        val app = context.applicationContext
        val target = artwork(app, id, type)
        val key = target.path
        if (target.isFile || (artworkFailures.get(key) ?: 0L) > android.os.SystemClock.elapsedRealtime() || requested.containsKey(key)) return
        val fetch = Runnable {
            val tried = mutableSetOf<String>()
            fun checkActive() {
                if (!artworkAllowed() || Thread.currentThread().isInterrupted) throw InterruptedException()
            }
            fun save(urls: List<String>): Boolean = urls.any { url ->
                checkActive()
                if (!tried.add(url)) return@any false
                val request = Glide.with(app).downloadOnly().load(url).submit()
                try {
                    val file = request.get(12, java.util.concurrent.TimeUnit.SECONDS)
                    checkActive()
                    if (file.length() == 0L) return@any false
                    target.parentFile?.mkdirs()
                    val temporary = File.createTempFile("${target.name}-", ".pending", target.parentFile)
                    try { file.copyTo(temporary, true); checkActive(); check(temporary.renameTo(target)) }
                    finally { temporary.delete() }
                    main.removeCallbacks(invalidateArtwork); main.postDelayed(invalidateArtwork, 500)
                    true
                } catch (_: java.util.concurrent.ExecutionException) { false }
                catch (_: java.util.concurrent.TimeoutException) { false }
                finally { Glide.with(app).clear(request) }
            }
            try {
                checkActive()
                val ownedArt = ownedArtwork(id)
                val local = runCatching { SteamGames.info(app, id).optJSONObject("common") }.getOrNull()
                if (ownedArt != null && save(SteamArtwork.fromSnapshot(ownedArt, type))) return@Runnable
                if (local != null && save(SteamArtwork.fromCommon(id, local, type))) return@Runnable
                if (save(SteamArtwork.urls(SteamArtwork.defaultUrl(id, type)))) return@Runnable
                checkActive()
                val live = if (SteamSession.signedIn) runCatching {
                    SteamSession.info(id).optJSONObject("common")
                }.onFailure { if (it is InterruptedException) throw it }.getOrNull() else null
                if (live != null && save(SteamArtwork.fromCommon(id, live, type))) return@Runnable
                checkActive()
                val store = runCatching { SteamStoreMetadata.fetch(id) }.getOrNull()
                if (store != null && save(listOf(store.optString("header_image"), store.optString("capsule_image")).filter(SteamArtwork::isSteamAsset))) return@Runnable
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                android.util.Log.d("SteamArtwork", "Could not cache Steam artwork for app $id", error)
            } finally {
                if (!target.isFile && artworkAllowed() && !Thread.currentThread().isInterrupted) {
                    artworkFailures.put(key, android.os.SystemClock.elapsedRealtime() + 60_000)
                }
            }
        }
        val task = object : FutureTask<Unit>(fetch, Unit) {
            override fun done() { requested.remove(key, this) }
        }
        if (requested.putIfAbsent(key, task) != null) return
        if (!artworkAllowed()) task.cancel(false) else images.execute(task)
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
