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
import java.util.concurrent.*

internal data class GogState(
    val games: List<GogGame> = emptyList(), val transfers: Map<String, JSONObject> = emptyMap(), val installed: Map<String, JSONObject> = emptyMap(),
    val signedIn: Boolean = false, val loading: Boolean = false, val error: String = "",
    val activeId: String = "", val stage: String = "", val done: Long = 0, val total: Long = 0, val speed: Long = 0,
    val busy: Boolean = false, val cancellable: Boolean = true, val revision: Int = 0
)

object GogLibrary {
    internal val mutable = MutableStateFlow(GogState())
    internal val state = mutable.asStateFlow()
    private fun pool(size: Int) = ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()).apply { allowCoreThreadTimeOut(true) }
    internal val worker = pool(1)
    private val backgroundGeneration = java.util.concurrent.atomic.AtomicInteger()
    private var openingGeneration: Int? = null
    internal fun backgroundTicket() = backgroundGeneration.get()
    internal fun backgroundAllowed(context: Context, ticket: Int): Boolean =
        ticket == backgroundGeneration.get() && !com.winlator.cmod.stores.StoreSessions.paused &&
            GogSettings.enabled(context) && !Thread.currentThread().isInterrupted
    internal fun checkBackground(context: Context, ticket: Int) {
        check(backgroundAllowed(context, ticket)) { "GOG background operation cancelled" }
    }
    @JvmStatic fun pauseBackgroundWork() {
        backgroundGeneration.incrementAndGet()
        synchronized(this) { openingGeneration = null }
        artworkChanged.removeCallbacks(artworkUpdate)
        val jobs = artworkJobs.values.toList()
        jobs.forEach { it.cancel(true) }
        GogApi.cancelWork()
        mutable.update { it.copy(loading = false) }
    }
    @Volatile private var initialized = false
    @Volatile internal var cancelled = false
    @Volatile internal var cancelRequested = false
    internal fun records(context: Context) = File(context.filesDir, "gog-installed").apply { mkdirs() }
    internal fun atomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".pending")
        temporary.writeText(text)
        check(temporary.renameTo(file)) { "Cannot save GOG library" }
    }
    internal fun cache(context: Context): File? = GogAccount.load(context)?.optString("user_id")
        ?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }?.let { File(context.filesDir, "gog-library/$it.json") }
    internal fun updateInstalled(context: Context, check: () -> Unit = {}) {
        val installed = records(context).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            check()
            runCatching {
                val record = JSONObject(file.readText())
                if (!File(record.getString("path")).isDirectory) null else file.nameWithoutExtension to record
            }.getOrNull()
        }.toMap()
        mutable.update { it.copy(installed = installed, revision = it.revision + 1) }
    }
    fun open(context: Context) {
        val app = context.applicationContext
        val ticket = backgroundTicket()
        if (!backgroundAllowed(app, ticket)) return
        synchronized(this) {
            if (initialized || openingGeneration == ticket) return
            openingGeneration = ticket
        }
        worker.execute {
            try {
                checkBackground(app, ticket)
                ContainerManager(app).containers.forEach { container ->
                    checkBackground(app, ticket)
                    File(container.rootDir, ".wine/drive_c/GOG Games").listFiles().orEmpty().filter {
                        it.isDirectory && it.name.matches(Regex("\\.ludashi-\\d+-installing-[0-9a-f-]{36}")) && it.lastModified() < com.winlator.cmod.stores.StoreSessions.processStarted
                    }.forEach { com.winlator.cmod.stores.StoreSessions.discard(it) }
                }
                updateInstalled(app) { checkBackground(app, ticket) }
                checkBackground(app, ticket)
                mutable.update { it.copy(transfers = GogTransfers.load(app), signedIn = GogAccount.load(app) != null) }
                val file = cache(app)
                if (file?.isFile == true) runCatching {
                    val rows = JSONArray(file.readText())
                    val games = (0 until rows.length()).map { checkBackground(app, ticket); GogGame.parse(rows.getJSONObject(it)) }
                    checkBackground(app, ticket)
                    mutable.update { it.copy(games = games) }
                }
                checkBackground(app, ticket)
                initialized = true
                if (mutable.value.signedIn && (file?.lastModified() ?: 0) < System.currentTimeMillis() - 6 * 60 * 60 * 1000) refreshNow(app, ticket)
            } catch (error: Exception) {
                if (backgroundAllowed(app, ticket)) mutable.update { it.copy(error = error.message.orEmpty(), loading = false) }
            } finally {
                synchronized(this) { if (openingGeneration == ticket) openingGeneration = null }
            }
        }
    }
    fun refresh(context: Context) {
        val app = context.applicationContext; val ticket = backgroundTicket()
        if (backgroundAllowed(app, ticket)) worker.execute { refreshNow(app, ticket) }
    }
    internal fun refreshNow(context: Context, ticket: Int = backgroundTicket()) {
        if (!backgroundAllowed(context, ticket)) return
        mutable.update { it.copy(loading = true, error = "") }
        try {
            val games = GogApi.library(context) { count -> checkBackground(context, ticket); mutable.update { it.copy(stage = "$count games") } }
            checkBackground(context, ticket)
            val target = cache(context) ?: error("GOG did not identify the account. Sign in again.")
            atomic(target, JSONArray().apply { games.forEach { put(it.json()) } }.toString())
            mutable.update { it.copy(games = games, signedIn = true) }
        } catch (error: Exception) { if (backgroundAllowed(context, ticket)) mutable.update { it.copy(error = error.message ?: "Could not load GOG library") } }
        finally { mutable.update { it.copy(loading = false, stage = if (it.busy) it.stage else "") } }
    }
    internal fun signIn(context: Context, code: String) {
        val ticket = backgroundTicket()
        if (!backgroundAllowed(context, ticket)) return
        worker.execute {
            if (!backgroundAllowed(context, ticket)) return@execute
            mutable.update { it.copy(loading = true, error = "") }
            try {
                check(GogSettings.enabled(context)) { "GOG integration is disabled" }
                val account = GogApi.token(code, false)
                checkBackground(context, ticket)
                require(account.optString("user_id").toLongOrNull()?.let { it > 0 } == true) { "GOG did not identify the account" }
                GogAccount.save(context, account)
                mutable.update { it.copy(games = emptyList(), signedIn = true) }
                refreshNow(context, ticket)
            } catch (error: Exception) { if (backgroundAllowed(context, ticket)) mutable.update { it.copy(loading = false, error = error.message ?: "GOG sign-in failed") } }
        }
    }
    fun signOut(context: Context) {
        if (mutable.value.busy) return
        pauseBackgroundWork()
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
        com.winlator.cmod.stores.StoreDetailActivity.open(context, "gog", gameId)
    } }
    private var icon: android.graphics.Bitmap? = null
    internal fun added(context: Context, id: String) = context.getSharedPreferences("gog-settings", 0).getBoolean("library-added-$id", false)
    internal fun setAdded(context: Context, id: String, value: Boolean) {
        require(id.toLongOrNull()?.let { it > 0 } == true)
        context.getSharedPreferences("gog-settings", 0).edit().putBoolean("library-added-$id", value).apply()
        mutable.update { it.copy(revision = it.revision + 1) }
    }
    internal fun libraryGames(context: Context, snapshot: GogState, mainLibraryOnly: Boolean = false): List<GogGame> {
        val games = (snapshot.games + snapshot.installed.values.mapNotNull { runCatching { GogGame.parse(it) }.getOrNull() }).distinctBy { it.id }
        return if (mainLibraryOnly) games.filter { it.id in snapshot.installed || added(context, it.id) } else games
    }
    internal fun items(context: Context, snapshot: GogState, mainLibraryOnly: Boolean = false): List<LibraryItem> {
        if (!GogSettings.enabled(context)) return emptyList()
        if (icon == null) {
            val drawable = context.getDrawable(com.winlator.cmod.R.drawable.ic_gog)
            icon = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).also {
                drawable?.setBounds(0, 0, 64, 64); drawable?.draw(android.graphics.Canvas(it))
            }
        }
        val games = libraryGames(context, snapshot, mainLibraryOnly)
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
    internal fun art(context: Context, game: GogGame, ticket: Int = backgroundTicket()) {
        checkBackground(context, ticket)
        val target = File(context.cacheDir, "gog-art/${game.id}.jpg")
        if (target.isFile) return
        fun save(url: String): Boolean {
            checkBackground(context, ticket)
            if (url.isBlank()) return false
            return try {
                target.parentFile?.mkdirs()
                val pending = File(target.parentFile, target.name + ".pending")
                // Use the same tracked/cancellable transport as other GOG work.
                // Glide's downloadOnly requests were outside pauseForGame.
                GogApi.artwork(url, pending) { backgroundAllowed(context, ticket) }
                checkBackground(context, ticket)
                check(pending.renameTo(target))
                mutable.value.installed[game.id]?.let { GogDownloadService.ensureIcon(context, game.id, it) }
                checkBackground(context, ticket)
                artworkUpdateGeneration = ticket
                artworkChanged.removeCallbacks(artworkUpdate); artworkChanged.postDelayed(artworkUpdate, 150)
                true
            } catch (error: Exception) {
                checkBackground(context, ticket)
                false
            }
        }
        if (save(GogGame.imageUrl(game.image))) return
        checkBackground(context, ticket)
        runCatching {
            val images = GogApi.product(game.id).optJSONObject("images") ?: return
            for (key in listOf("background", "logo2x", "icon")) if (save(GogGame.imageUrl(images.optString(key)))) return
        }
    }
    private val artworkChanged = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var artworkUpdateGeneration = -1
    private val artworkUpdate = Runnable {
        if (artworkUpdateGeneration == backgroundTicket() && !com.winlator.cmod.stores.StoreSessions.paused)
            mutable.update { it.copy(revision = it.revision + 1) }
    }
    private val artwork = pool(2)
    private val artworkJobs = ConcurrentHashMap<String, FutureTask<Unit>>()
    fun requestArt(context: Context, path: String) {
        val app = context.applicationContext; val ticket = backgroundTicket()
        if (!backgroundAllowed(app, ticket)) return
        val gameId = id(path) ?: return
        if (File(app.cacheDir, "gog-art/$gameId.jpg").isFile) return
        val game = mutable.value.games.find { it.id == gameId }
            ?: mutable.value.installed[gameId]?.let { runCatching { GogGame.parse(it) }.getOrNull() } ?: return
        val task = object : FutureTask<Unit>(Callable {
            try { if (backgroundAllowed(app, ticket)) art(app, game, ticket) }
            catch (_: Exception) { }
            Unit
        }) {
            private val running = java.util.concurrent.atomic.AtomicBoolean()
            override fun run() {
                if (!running.compareAndSet(false, true)) return
                try { super.run() } finally { artworkJobs.remove(gameId, this) }
            }
            override fun done() { if (!running.get()) artworkJobs.remove(gameId, this) }
        }
        if (artworkJobs.putIfAbsent(gameId, task) != null) return
        if (!backgroundAllowed(app, ticket)) task.cancel(true) else artwork.execute(task)
    }
}
