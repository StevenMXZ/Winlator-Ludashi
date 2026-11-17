package com.winlator.cmod.stores

import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bumptech.glide.Glide
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ItchCatalogScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by ItchCatalog.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf(state.query.search) }
    var sortMenu by remember { mutableStateOf(false) }
    var priceMenu by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf(state.query.tag) }
    var opening by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf("") }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            ItchCatalog.open(context)
            try { awaitCancellation() } finally { ItchCatalog.cancel() }
        }
    }
    DisposableEffect(Unit) { onDispose { ItchCatalog.cancel() } }
    fun show(query: ItchBrowseQuery) { ItchCatalog.open(context, query) }
    fun open(game: StoreGame) {
        if (opening) return
        opening = true; openError = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ItchCatalog.rememberGame(context, game) } }
            opening = false
            result.onSuccess { StoreDetailActivity.open(context, "itch", game.id) }.onFailure { openError = it.message.orEmpty() }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(search, { search = it }, singleLine = true, label = { Text(uiStr(R.string.store_discover_search)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { show(ItchBrowseQuery(search = search.trim(), feedOnly = true)) }),
            trailingIcon = { IconButton(onClick = { show(ItchBrowseQuery(search = search.trim(), feedOnly = true)) }) { Icon(Icons.Outlined.Search, null) } })
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(state.query.featured, { search = ""; tag = ""; show(ItchBrowseQuery()) }, label = { Text(uiStr(R.string.store_discover)) }) }
            item {
                Box {
                    FilterChip(!state.query.featured, { sortMenu = true }, label = { Text(sortLabel(state.query.sort)) }, trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) })
                    DropdownMenu(sortMenu, { sortMenu = false }) { ItchCatalog.sorts.forEach { sort -> DropdownMenuItem(text = { Text(sortLabel(sort)) }, onClick = { sortMenu = false; search = ""; show(state.query.copy(search = "", sort = sort, feedOnly = true)) }) } }
                }
            }
            item {
                Box {
                    FilterChip(state.query.price != "any", { priceMenu = true }, label = { Text(priceLabel(state.query.price)) }, trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) })
                    DropdownMenu(priceMenu, { priceMenu = false }) { ItchCatalog.prices.forEach { price -> DropdownMenuItem(text = { Text(priceLabel(price)) }, onClick = { priceMenu = false; search = ""; show(state.query.copy(search = "", price = price, feedOnly = true)) }) } }
                }
            }
            item { FilterChip(state.query.tag.isNotBlank(), { tagDialog = true }, label = { Text(state.query.tag.ifBlank { uiStr(R.string.store_tags) }) }) }
        }
        if (state.loading || opening) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotBlank() || openError.isNotBlank()) Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.error.ifBlank { openError }, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { ItchCatalog.open(context, force = true) }) { Text(uiStr(R.string.store_retry_download)) }
        }
        if (state.query.featured) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("newest" to R.string.store_new_games, "sale" to R.string.store_on_sale, "free" to R.string.store_free_games, "popular" to R.string.store_popular).forEach { (id, label) ->
                val games = state.sections[id].orEmpty()
                if (games.isNotEmpty()) item(key = id) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(uiStr(label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { show(when (id) { "newest" -> ItchBrowseQuery(sort = "newest", feedOnly = true); "sale" -> ItchBrowseQuery(price = "on-sale", feedOnly = true); "free" -> ItchBrowseQuery(price = "price-free", feedOnly = true); else -> ItchBrowseQuery(feedOnly = true) }) }) { Text(uiStr(R.string.store_see_all)) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(games, key = { it.id }) { game -> ItchCatalogCard(game, Modifier.width(185.dp)) { open(game) } }
                    }
                }
            }
            if (!state.loading && state.sections.isEmpty() && state.error.isBlank()) item { Text(uiStr(R.string.store_catalog_empty), Modifier.padding(16.dp)) }
        }
        else LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.weight(1f), contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.games, key = { it.id }) { game -> ItchCatalogCard(game) { open(game) } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (!state.loading && state.more && state.games.isNotEmpty()) OutlinedButton(onClick = { ItchCatalog.open(context, more = true) }, modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.store_load_more)) }
                else if (!state.loading && state.games.isEmpty() && state.error.isBlank()) Text(uiStr(R.string.store_catalog_empty))
            }
        }
    }
    if (tagDialog) AlertDialog(onDismissRequest = { tagDialog = false }, title = { Text(uiStr(R.string.store_tags)) }, text = { OutlinedTextField(tag, { tag = it }, singleLine = true, label = { Text("action, horror, pixel-art…") }) },
        confirmButton = { TextButton(onClick = { tagDialog = false; search = ""; show(state.query.copy(search = "", tag = tag.trim(), feedOnly = true)) }) { Text(uiStr(R.string.store_apply)) } }, dismissButton = { TextButton(onClick = { tagDialog = false }) { Text(uiStr(R.string.cancel)) } })
}

private fun sortLabel(sort: String) = uiStr(when (sort) { "newest" -> R.string.store_new_games; "top-rated" -> R.string.store_top_rated; "top-sellers" -> R.string.store_top_sellers; "new-and-popular" -> R.string.store_new_popular; else -> R.string.store_popular })
private fun priceLabel(price: String) = uiStr(when (price) { "price-free" -> R.string.store_free_games; "on-sale" -> R.string.store_on_sale; "store" -> R.string.store_paid_games; else -> R.string.store_any_price })

@Composable private fun ItchCatalogCard(game: StoreGame, modifier: Modifier = Modifier, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
            update = { Glide.with(it).load(game.image).error(R.drawable.ic_itch).into(it) },
            onRelease = { Glide.with(it.context.applicationContext).clear(it) }, modifier = Modifier.fillMaxWidth().padding(6.dp).aspectRatio(460f / 260f).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (game.metadata.optBoolean("free")) uiStr(R.string.store_free) else game.metadata.optString("price").ifBlank { uiStr(R.string.store_view_on_itch) },
                style = MaterialTheme.typography.labelMedium, color = if (game.metadata.optBoolean("free")) Color(0xFF72C696) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
