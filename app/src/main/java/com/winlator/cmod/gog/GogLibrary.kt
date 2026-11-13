package com.winlator.cmod.gog

import android.content.Context
import android.content.Intent
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.library.LibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal data class GogState(
    val games: List<GogGame> = emptyList(), val transfers: Map<String, JSONObject> = emptyMap(), val installed: Map<String, JSONObject> = emptyMap(),
    val signedIn: Boolean = false, val loading: Boolean = false, val error: String = "",
    val activeId: String = "", val stage: String = "", val done: Long = 0, val total: Long = 0,
    val busy: Boolean = false, val cancellable: Boolean = true, val revision: Int = 0
)

object GogLibrary {
    internal val mutable = MutableStateFlow(GogState())
    internal val state = mutable.asStateFlow()
    internal val worker = Executors.newSingleThreadExecutor()
    @Volatile private var initialized = false
    @Volatile internal var cancelled = false
    internal fun records(context: Context) = File(context.filesDir, "gog-installed").apply { mkdirs() }
    internal fun atomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".pending")
        temporary.writeText(text)
        check(temporary.renameTo(file)) { "Cannot save GOG library" }
    }
    internal fun cache(context: Context): File? = GogAccount.load(context)?.optString("user_id")
        ?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }?.let { File(context.filesDir, "gog-library/$it.json") }
    internal fun updateInstalled(context: Context) {
        val installed = records(context).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching {
                val record = JSONObject(file.readText())
                if (!File(record.getString("path")).isDirectory) null else file.nameWithoutExtension to record
            }.getOrNull()
        }.toMap()
        mutable.update { it.copy(installed = installed, revision = it.revision + 1) }
    }
    fun open(context: Context) {
        if (!GogSettings.enabled(context) || initialized || com.winlator.cmod.stores.StoreSessions.paused) return
        synchronized(this) { if (initialized) return; initialized = true }
        val app = context.applicationContext
        worker.execute {
            ContainerManager(app).containers.forEach { container ->
                File(container.rootDir, ".wine/drive_c/GOG Games").listFiles().orEmpty().filter {
                    it.isDirectory && it.name.matches(Regex("\\.ludashi-\\d+-installing-[0-9a-f-]{36}")) && it.lastModified() < com.winlator.cmod.stores.StoreSessions.processStarted
                }.forEach { com.winlator.cmod.stores.StoreSessions.discard(it) }
            }
            updateInstalled(app)
            mutable.update { it.copy(transfers = GogTransfers.load(app)) }
            mutable.update { it.copy(signedIn = GogAccount.load(app) != null) }
            val file = cache(app)
            runCatching {
                if (file?.isFile == true) {
                    val rows = JSONArray(file.readText())
                    val games = (0 until rows.length()).map { GogGame.parse(rows.getJSONObject(it)) }
                    mutable.update { it.copy(games = games) }
                }
            }
            if (mutable.value.signedIn && (file?.lastModified() ?: 0) < System.currentTimeMillis() - 6 * 60 * 60 * 1000) refreshNow(app)
        }
    }
    fun refresh(context: Context) { val app = context.applicationContext; worker.execute { refreshNow(app) } }
    internal fun refreshNow(context: Context) {
        if (!GogSettings.enabled(context)) return
        mutable.update { it.copy(loading = true, error = "") }
        try {
            val games = GogApi.library(context) { count -> mutable.update { it.copy(stage = "$count games") } }
            val target = cache(context) ?: error("GOG did not identify the account. Sign in again.")
            atomic(target, JSONArray().apply { games.forEach { put(it.json()) } }.toString())
            mutable.update { it.copy(games = games, signedIn = true) }
        } catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "Could not load GOG library") } }
        finally { mutable.update { it.copy(loading = false, stage = if (it.busy) it.stage else "") } }
    }
    internal fun signIn(context: Context, code: String) {
        worker.execute {
            mutable.update { it.copy(loading = true, error = "") }
            try {
                check(GogSettings.enabled(context)) { "GOG integration is disabled" }
                val account = GogApi.token(code, false)
                require(account.optString("user_id").toLongOrNull()?.let { it > 0 } == true) { "GOG did not identify the account" }
                GogAccount.save(context, account)
                mutable.update { it.copy(games = emptyList(), signedIn = true) }
                refreshNow(context)
            } catch (error: Exception) { mutable.update { it.copy(loading = false, error = error.message ?: "GOG sign-in failed") } }
        }
    }
    fun signOut(context: Context) {
        if (mutable.value.busy) return
        worker.execute {
            GogAccount.clear(context)
            mutable.update { it.copy(signedIn = false, games = emptyList(), error = "") }
            val cookies = android.webkit.CookieManager.getInstance()
            for (host in listOf("gog.com", "www.gog.com", "auth.gog.com", "embed.gog.com")) {
                val address = "https://$host"
                cookies.getCookie(address)?.split(';')?.forEach { cookie ->
                    val name = cookie.substringBefore('=').trim()
                    cookies.setCookie(address, "$name=; Domain=.gog.com; Path=/; Max-Age=0")
                    cookies.setCookie(address, "$name=; Path=/; Max-Age=0")
                }
            }
            cookies.flush()
        }
    }
    fun id(path: String): String? = path.removePrefix("gog://").takeIf { path.startsWith("gog://") && it.toLongOrNull()?.let { n -> n > 0 } == true }
    fun openGame(context: Context, path: String) { id(path)?.let { gameId ->
        if (File(records(context), "$gameId.json").isFile) com.winlator.cmod.stores.StoreDetailActivity.open(context, "gog", gameId)
        else context.startActivity(Intent(context, GogActivity::class.java).putExtra("game", gameId))
    } }
    private var icon: android.graphics.Bitmap? = null
    internal fun items(context: Context, snapshot: GogState): List<LibraryItem> {
        if (!GogSettings.enabled(context)) return emptyList()
        if (icon == null) {
            val drawable = context.getDrawable(com.winlator.cmod.R.drawable.ic_gog)
            icon = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).also {
                drawable?.setBounds(0, 0, 64, 64); drawable?.draw(android.graphics.Canvas(it))
            }
        }
        val games = (snapshot.games + snapshot.installed.values.mapNotNull { runCatching { GogGame.parse(it) }.getOrNull() }).distinctBy { it.id }
        val prefs = context.getSharedPreferences("gog-settings", 0)
        val artNames = File(context.cacheDir, "gog-art").list()?.toSet().orEmpty()
        return games.map { game ->
            val record = snapshot.installed[game.id]
            val art = File(context.cacheDir, "gog-art/${game.id}.jpg").takeIf { "${game.id}.jpg" in artNames }
            LibraryItem("gog-${game.id}", "gog://${game.id}", game.title,
                "GOG · " + if (record != null) "Installed" else "Not installed",
                art?.path, art?.path, null, icon,
                prefs.getBoolean("favorite-${game.id}", false), prefs.getLong("last-run-${game.id}", 0))
        }
    }
    internal fun uninstall(context: Context, id: String, record: JSONObject) {
        val cid = record.getInt("container")
        check(com.winlator.cmod.core.ContainerSessionGate.edit(cid)) { "Exit the container before uninstalling" }
        try {
            val container = ContainerManager(context).getContainerById(cid) ?: error("Container was removed")
            val target = File(record.getString("path")).canonicalFile
            require(target == File(container.rootDir, ".wine/drive_c/GOG Games/Ludashi-$id").canonicalFile)
            check(target.deleteRecursively()) { "Could not remove game files" }
            val shortcut = File(record.getString("shortcut")).canonicalFile
            require(shortcut.parentFile == container.desktopDir.canonicalFile)
            shortcut.delete()
            File(container.getIconsDir(64), "gog-$id.png").delete()
            check(File(records(context), "$id.json").delete())
            updateInstalled(context)
        } finally { com.winlator.cmod.core.ContainerSessionGate.edited(cid) }
    }
    internal fun shortcut(context: Context, id: String): Shortcut {
        val record = mutable.value.installed[id] ?: runCatching { JSONObject(File(records(context), "$id.json").readText()) }.getOrElse { error("Install this GOG game first") }
        val container = ContainerManager(context).getContainerById(record.getInt("container")) ?: error("The game's container was removed")
        val file = File(record.getString("shortcut"))
        require(file.canonicalFile.parentFile == container.desktopDir.canonicalFile && file.isFile) { "GOG shortcut is missing" }
        return Shortcut(container, file)
    }
    internal fun play(context: Context, id: String) {
        val shortcut = shortcut(context, id)
        context.getSharedPreferences("gog-settings", 0).edit().putLong("last-run-$id", System.currentTimeMillis()).apply()
        mutable.update { it.copy(revision = it.revision + 1) }
        context.startActivity(Intent(context, XServerDisplayActivity::class.java).putExtra("container_id", shortcut.container.id)
            .putExtra("shortcut_path", shortcut.file.path).putExtra("shortcut_name", shortcut.name))
    }
    internal fun art(context: Context, game: GogGame) {
        if (!GogSettings.enabled(context)) return
        val target = File(context.cacheDir, "gog-art/${game.id}.jpg")
        if (target.isFile) return
        fun save(url: String): Boolean {
            if (url.isBlank()) return false
            val request = com.bumptech.glide.Glide.with(context).downloadOnly().load(url).submit()
            return try {
                val source = request.get(20, java.util.concurrent.TimeUnit.SECONDS)
                target.parentFile?.mkdirs()
                val pending = File(target.parentFile, target.name + ".pending")
                source.copyTo(pending, true)
                check(pending.renameTo(target))
                mutable.value.installed[game.id]?.let { GogDownloadService.ensureIcon(context, game.id, it) }
                artworkChanged.removeCallbacks(artworkUpdate); artworkChanged.postDelayed(artworkUpdate, 150)
                true
            } catch (_: Exception) { false }
            finally { com.bumptech.glide.Glide.with(context).clear(request) }
        }
        if (save(GogGame.imageUrl(game.image)) || !GogSettings.enabled(context)) return
        runCatching {
            val images = GogApi.product(game.id).optJSONObject("images") ?: return
            for (key in listOf("background", "logo2x", "icon")) if (save(GogGame.imageUrl(images.optString(key)))) return
        }
    }
    private val artworkChanged = android.os.Handler(android.os.Looper.getMainLooper())
    private val artworkUpdate = Runnable { mutable.update { it.copy(revision = it.revision + 1) } }
    private val artwork = Executors.newFixedThreadPool(2)
    private val requested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun requestArt(context: Context, path: String) {
        val gameId = id(path) ?: return
        val game = mutable.value.games.find { it.id == gameId }
            ?: mutable.value.installed[gameId]?.let { runCatching { GogGame.parse(it) }.getOrNull() } ?: return
        if (!requested.add(gameId)) return
        artwork.execute { try { art(context.applicationContext, game) } finally { requested.remove(gameId) } }
    }
}
