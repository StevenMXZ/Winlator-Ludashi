package com.winlator.cmod.stores

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import com.winlator.cmod.ui.library.LibraryItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object StoreSessions {
    internal val processStarted = System.currentTimeMillis()
    private val staleScanned = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var paused = false
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = LudashiStore.entries.associateWith { MutableStateFlow(StoreSnapshot()) }
    private val cancellations = LudashiStore.entries.associateWith { java.util.concurrent.atomic.AtomicBoolean(false) }
    private val generations = LudashiStore.entries.associateWith { java.util.concurrent.atomic.AtomicInteger() }
    private val operationContext = ThreadLocal<Pair<LudashiStore, Int>?>()
    internal fun operation(store: LudashiStore) = operationContext.asContextElement(store to generations.getValue(store).get())
    internal fun live(store: LudashiStore): Boolean {
        val operation = operationContext.get()
        return !cancelled(store) && (operation == null || operation.first != store || operation.second == generations.getValue(store).get())
    }
    private val tasks = ConcurrentHashMap<LudashiStore, Job>()
    private val initialized = ConcurrentHashMap.newKeySet<LudashiStore>()
    private val processes = ConcurrentHashMap<String, Process>()
    internal fun state(store: LudashiStore) = states.getValue(store)
    internal fun cancelled(store: LudashiStore) = cancellations.getValue(store).get()
    internal fun fresh(store: LudashiStore) { cancellations.getValue(store).set(false) }
    fun cancel(store: LudashiStore) {
        generations.getValue(store).incrementAndGet()
        cancellations.getValue(store).set(true)
        tasks.remove(store)?.cancel()
        state(store).update { it.copy(loading = false) }
        StoreNetwork.stop(store)
        processes.remove(store.id)?.destroy()
    }
    @JvmStatic fun pauseForGame() {
        paused = true
        LudashiStore.entries.forEach(::cancel)
        processes.values.forEach { it.destroy() }
        com.winlator.cmod.gog.GogLibrary.cancelled = true
        com.winlator.cmod.gog.GogApi.cancelWork()
    }
    @JvmStatic fun resumeForLibrary() {
        if (paused && !com.winlator.cmod.core.ContainerSessionGate.hasActiveSessions()) {
            paused = false
            LudashiStore.entries.forEach(::fresh)
            scope.launch { cleanDiscarded() }
        }
    }
    internal fun trackProcess(store: String, process: Process) { processes[store] = process; if (paused) process.destroy() }
    private val discarded = ConcurrentHashMap.newKeySet<File>()
    internal fun discard(folder: File) {
        discarded.add(folder)
        if (!paused) scope.launch { cleanDiscarded() }
    }
    private fun cleanDiscarded() {
        for (folder in discarded.toList()) {
            for (file in folder.walkBottomUp()) {
                if (paused) return
                if (file.exists() && !file.delete()) break
            }
            if (!folder.exists()) discarded.remove(folder)
        }
    }
    internal fun root(context: Context, store: LudashiStore) = File(context.filesDir, "ludashi-stores/${store.id}").apply { mkdirs() }
    internal fun records(context: Context, store: LudashiStore) = File(root(context, store), "installed").apply { mkdirs() }
    private fun cache(context: Context, store: LudashiStore): File? {
        val account = StoreVault.load(context, store)?.optString("account")?.takeIf { it.isNotBlank() } ?: return null
        return File(root(context, store), "libraries/${storeHash(account)}.json")
    }
    internal fun installed(context: Context, store: LudashiStore) {
        val records = records(context, store).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching {
                val record = JSONObject(file.readText())
                if (File(record.getString("path")).isDirectory) file.nameWithoutExtension to record else null
            }.getOrNull()
        }.toMap()
        state(store).update { it.copy(installed = records, revision = it.revision + 1) }
    }
    fun open(context: Context, store: LudashiStore) {
        if (!store.enabled(context) || paused) return
        fresh(store)
        if (!initialized.add(store)) return
        val app = context.applicationContext
        fresh(store)
        tasks[store] = scope.launch(operation(store)) {
            try {
                if (staleScanned.compareAndSet(false, true)) File(app.filesDir, "store-transfers").listFiles().orEmpty()
                    .filter { it.isDirectory && it.lastModified() < processStarted }.forEach(::discard)
                installed(app, store)
                state(store).update { it.copy(transfers = StoreTransfers.load(app, store)) }
                val saved = StoreVault.load(app, store)
                state(store).update { it.copy(signedIn = saved != null) }
                val cache = cache(app, store)
                if (cache?.isFile == true) runCatching {
                    val games = JSONArray(cache.readText())
                    state(store).update { it.copy(games = (0 until games.length()).map { i -> StoreGame.parse(games.getJSONObject(i)) }) }
                }
                if (saved != null && (cache?.lastModified() ?: 0) < System.currentTimeMillis() - 6 * 3600000) refreshNow(app, store)
            } catch (error: Exception) {
                if (live(store) && !paused) state(store).update { it.copy(error = error.message.orEmpty(), loading = false) }
            }
        }
    }
    internal fun run(context: Context, store: LudashiStore, operation: suspend () -> Unit) {
        if (paused || !store.enabled(context) || state(store).value.busy) return
        cancel(store)
        fresh(store)
        tasks[store] = scope.launch(operation(store)) {
            state(store).update { it.copy(loading = true, error = "") }
            try { operation() }
            catch (error: Exception) { if (!cancelled(store) && !paused) state(store).update { it.copy(error = error.message ?: "Store operation failed") } }
            finally { if (live(store)) state(store).update { it.copy(loading = false) }; StoreNetwork.stopIfIdle(store) }
        }
    }
    fun refresh(context: Context, store: LudashiStore) { run(context, store) { refreshNow(context.applicationContext, store) } }
    internal suspend fun refreshNow(context: Context, store: LudashiStore) {
        StoreNetwork.check(context, store)
        state(store).update { it.copy(loading = true, error = "") }
        val loaded = if (store == LudashiStore.ITCH) ItchConnector.library(context) else AmazonConnector.library(context)
        currentCoroutineContext().ensureActive()
        StoreNetwork.check(context, store)
        val custom = state(store).value.games.filter { it.metadata.optBoolean("custom") }
        val games = (loaded + custom).distinctBy { it.id }
        atomicStoreFile(cache(context, store) ?: error("Store account is missing"), JSONArray().apply { games.forEach { put(it.json()) } }.toString())
        state(store).update { it.copy(games = games, signedIn = true, loading = false) }
    }
    fun signOut(context: Context, store: LudashiStore) {
        if (state(store).value.busy) return
        cancel(store)
        scope.launch {
            StoreVault.clear(context, store)
            val domain = if (store == LudashiStore.ITCH) "itch.io" else "amazon.com"
            val cookies = android.webkit.CookieManager.getInstance()
            for (host in listOf(domain, "www.$domain")) {
                val address = "https://$host"
                cookies.getCookie(address)?.split(';')?.forEach {
                    val name = it.substringBefore('=').trim()
                    cookies.setCookie(address, "$name=; Domain=.$domain; Path=/; Max-Age=0")
                    cookies.setCookie(address, "$name=; Path=/; Max-Age=0")
                }
            }
            cookies.flush()
            state(store).update { it.copy(games = emptyList(), signedIn = false, loading = false, error = "") }
        }
    }
    internal suspend fun loginFinished(context: Context, store: LudashiStore) {
        state(store).update { it.copy(games = emptyList(), signedIn = true) }
        refreshNow(context, store)
    }
    internal fun addItch(context: Context, url: String) { run(context, LudashiStore.ITCH) {
        val game = ItchConnector.lookup(context, url)
        val games = (state(LudashiStore.ITCH).value.games + game).distinctBy { it.id }
        atomicStoreFile(cache(context, LudashiStore.ITCH) ?: error("Sign in first"), JSONArray().apply { games.forEach { put(it.json()) } }.toString())
        state(LudashiStore.ITCH).update { it.copy(games = games) }
    } }
    internal fun allGames(snapshot: StoreSnapshot): List<StoreGame> =
        (snapshot.games + snapshot.installed.values.mapNotNull { runCatching { StoreGame.parse(it.getJSONObject("game")) }.getOrNull() }).distinctBy { it.id }
    private val badges = ConcurrentHashMap<LudashiStore, android.graphics.Bitmap>()
    internal fun items(context: Context, store: LudashiStore, snapshot: StoreSnapshot): List<LibraryItem> {
        if (!store.enabled(context)) return emptyList()
        val fallback = badges.computeIfAbsent(store) {
            android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                context.getDrawable(store.icon)?.apply { setBounds(0, 0, 64, 64); draw(android.graphics.Canvas(bitmap)) }
            }
        }
        val prefs = context.getSharedPreferences("ludashi-store-games", 0)
        val artNames = File(context.cacheDir, "${store.id}-art").list()?.toSet().orEmpty()
        return allGames(snapshot).map { game ->
            val record = snapshot.installed[game.key]
            val art = File(context.cacheDir, "${store.id}-art/${game.key}.jpg").takeIf { "${game.key}.jpg" in artNames }
            LibraryItem("${store.id}-${game.key}", game.uri, game.title, "${store.title} · " + if (record != null) "Installed" else "Not installed",
                art?.path, art?.path, null, fallback, prefs.getBoolean("${store.id}-${game.key}-favorite", false), prefs.getLong("${store.id}-${game.key}-last", 0))
        }
    }
    fun openGame(context: Context, path: String) {
        val store = LudashiStore.fromPath(path) ?: return
        val id = Uri.decode(path.substringAfter("://"))
        StoreDetailActivity.open(context, store.id, id)
    }
    private val artwork = ConcurrentHashMap.newKeySet<String>()
    private val artworkSlots = kotlinx.coroutines.sync.Semaphore(2)
    fun requestArt(context: Context, path: String) {
        val store = LudashiStore.fromPath(path) ?: return
        if (paused || !store.enabled(context)) return
        val game = allGames(state(store).value).find { it.id == Uri.decode(path.substringAfter("://")) } ?: return
        val target = File(context.cacheDir, "${store.id}-art/${game.key}.jpg")
        if (target.isFile || game.image.isBlank() || !artwork.add(path)) return
        scope.launch(operation(store)) {
            artworkSlots.acquire()
            try {
                StoreNetwork.check(context, store)
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, target.name + ".pending")
                val data = StoreNetwork.bytes(context, store, game.image, 20 * 1024 * 1024)
                temporary.writeBytes(data)
                check(temporary.renameTo(target))
                state(store).value.installed[game.key]?.let { StoreInstalls.icon(context, game, it.getInt("container")) }
                state(store).update { it.copy(revision = it.revision + 1) }
            } catch (_: Exception) { } finally { artworkSlots.release(); artwork.remove(path); StoreNetwork.stopIfIdle(store) }
        }
    }
}
