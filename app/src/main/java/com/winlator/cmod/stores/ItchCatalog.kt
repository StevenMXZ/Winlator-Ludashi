package com.winlator.cmod.stores

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class ItchBrowseQuery(val search: String = "", val sort: String = "popular", val price: String = "any", val tag: String = "", val feedOnly: Boolean = false) {
    val featured get() = !feedOnly && search.isBlank() && sort == "popular" && price == "any" && tag.isBlank()
}
internal data class ItchCatalogSnapshot(
    val query: ItchBrowseQuery = ItchBrowseQuery(), val sections: Map<String, List<StoreGame>> = emptyMap(), val games: List<StoreGame> = emptyList(),
    val loading: Boolean = false, val loaded: Boolean = false, val error: String = "", val page: Int = 0, val more: Boolean = true
)
internal object ItchCatalog {
    const val REQUEST_TAG = "itch-discovery"
    val state = MutableStateFlow(ItchCatalogSnapshot())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val generation = AtomicInteger()
    private val known = ConcurrentHashMap<String, StoreGame>()
    private var refreshed = 0L
    val sorts = listOf("popular", "newest", "top-rated", "top-sellers", "new-and-popular")
    val prices = listOf("any", "price-free", "on-sale", "store")
    fun game(id: String) = known[id]
    fun rememberGame(context: Context, game: StoreGame) {
        known[game.id] = game
        atomicStoreFile(File(context.filesDir, "itch-selected/${game.key}.json"), game.json().toString())
    }
    fun selected(context: Context, id: String): StoreGame? = known[id] ?: runCatching {
        StoreGame.parse(JSONObject(File(context.filesDir, "itch-selected/${storeHash(id).take(32)}.json").readText())).also { require(it.id == id); known[id] = it }
    }.getOrNull()
    fun url(query: ItchBrowseQuery, page: Int): String {
        require(query.sort in sorts && query.price in prices && page > 0)
        val tag = query.tag.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80)
        val parts = listOfNotNull(query.sort.takeUnless { it == "popular" }, query.price.takeUnless { it == "any" }, "platform-windows", tag.takeIf { it.isNotBlank() }?.let { "tag-$it" })
        return "https://itch.io/games/${parts.joinToString("/")}.xml" + if (page > 1) "?page=$page" else ""
    }
    fun parse(xml: String): List<StoreGame> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        require(doc.selectFirst("rss > channel") != null) { "itch.io did not return a game feed" }
        return doc.select("item").mapNotNull { row ->
            val id = runCatching { ItchConnector.gameUrl(row.selectFirst("link")?.text().orEmpty()) }.getOrNull() ?: return@mapNotNull null
            val rawTitle = row.selectFirst("title")?.text().orEmpty()
            val title = row.selectFirst("plainTitle")?.text().orEmpty().ifBlank { rawTitle.substringBefore(" [") }
            if (title.isBlank()) return@mapNotNull null
            val price = row.selectFirst("price")?.text().orEmpty()
            val free = price in listOf("$0.00", "0.00", "Free", "free") || "[Free]" in rawTitle
            val info = JSONObject().put("description", Jsoup.parse(row.selectFirst("description")?.text().orEmpty()).text().take(2500))
                .put("releaseDate", row.selectFirst("createDate")?.text().orEmpty())
            StoreGame(LudashiStore.ITCH, id, title, row.selectFirst("imageurl")?.text().orEmpty(), JSONObject()
                .put("catalog", true).put("free", free).put("price", price).put("information", info))
        }.distinctBy { it.id }
    }
    private fun feed(context: Context, query: ItchBrowseQuery, page: Int, force: Boolean): List<StoreGame> {
        val url = url(query, page)
        val file = File(context.cacheDir, "itch-catalog/${storeHash(url)}.json")
        if (!force && file.isFile && System.currentTimeMillis() - file.lastModified() < 3 * 3600000) runCatching {
            val rows = JSONArray(file.readText()); return (0 until rows.length()).map { StoreGame.parse(rows.getJSONObject(it)) }
        }
        val result = StoreNetwork.request(context, LudashiStore.ITCH, Request.Builder().url(url).tag(REQUEST_TAG)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/rss+xml, application/xml, text/xml").build()) { response -> parse(response.body?.string().orEmpty()) }
        atomicStoreFile(file, JSONArray().apply { result.forEach { put(it.json()) } }.toString())
        return result
    }
    fun cancel() {
        generation.incrementAndGet(); job?.cancel(); job = null
        StoreNetwork.stopTagged(LudashiStore.ITCH, REQUEST_TAG)
        state.update { it.copy(loading = false) }
    }
    fun open(context: Context, query: ItchBrowseQuery = state.value.query, force: Boolean = false, more: Boolean = false) {
        if (StoreSessions.paused || !LudashiStore.ITCH.enabled(context)) return
        if (!force && !more && query == state.value.query && state.value.error.isBlank() && state.value.loaded &&
            (state.value.sections.isNotEmpty() || state.value.games.isNotEmpty()) && System.currentTimeMillis() - refreshed < 3 * 3600000) return
        if (more && (state.value.loading || !state.value.more)) return
        cancel()
        StoreSessions.fresh(LudashiStore.ITCH)
        val serial = generation.get()
        val app = context.applicationContext
        val next = if (more) state.value.page + 1 else 1
        val old = if (more) state.value.games else emptyList()
        state.update { if (more) it.copy(loading = true, error = "") else ItchCatalogSnapshot(query = query, loading = true) }
        job = scope.launch(StoreSessions.operation(LudashiStore.ITCH)) {
            try {
                StoreNetwork.check(app, LudashiStore.ITCH)
                if (query.featured) {
                    val sections = linkedMapOf<String, List<StoreGame>>()
                    var failure = ""
                    for ((index, entry) in listOf("newest" to ItchBrowseQuery(sort = "newest"), "sale" to ItchBrowseQuery(price = "on-sale"), "free" to ItchBrowseQuery(price = "price-free"), "popular" to ItchBrowseQuery()).withIndex()) {
                        if (index > 0) delay(1200)
                        ensureActive()
                        runCatching { feed(app, entry.second, 1, force) }.onSuccess { rows ->
                            sections[entry.first] = rows
                            rows.forEach { known[it.id] = it }
                        }.onFailure { failure = it.message.orEmpty() }
                        if (generation.get() == serial) state.update { it.copy(sections = sections.toMap(), error = if (sections.isEmpty()) failure else "") }
                    }
                } else {
                    val rows = if (query.search.isBlank()) feed(app, query, next, force) else {
                        check(ItchApi.key(app).isNotBlank()) { com.winlator.cmod.ui.uiStr(com.winlator.cmod.R.string.store_itch_search_login) }
                        ItchApi.search(app, query.search, next, REQUEST_TAG).filter { game ->
                            query.price != "price-free" || game.metadata.optBoolean("free")
                        }
                    }
                    ensureActive(); StoreNetwork.check(app, LudashiStore.ITCH)
                    rows.forEach { known[it.id] = it }
                    val games = (old + rows).distinctBy { it.id }
                    if (generation.get() == serial) state.update { it.copy(games = games, page = next, more = rows.isNotEmpty() && games.size > old.size) }
                }
                if (generation.get() == serial) state.update { it.copy(loaded = !query.featured || it.sections.size == 4) }
                refreshed = System.currentTimeMillis()
            } catch (error: Exception) {
                if (generation.get() == serial && !StoreSessions.paused && error !is CancellationException) state.update { it.copy(error = error.message.orEmpty()) }
            } finally {
                if (generation.get() == serial) state.update { it.copy(loading = false) }
                StoreNetwork.stopIfIdle(LudashiStore.ITCH)
            }
        }
    }
}
