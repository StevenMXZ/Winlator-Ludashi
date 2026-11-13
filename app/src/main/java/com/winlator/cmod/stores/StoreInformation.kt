package com.winlator.cmod.stores

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.gog.GogApi
import com.winlator.cmod.gog.GogSettings
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File

@Composable internal fun StoreInformation(storeId: String, gameId: String, record: JSONObject) {
    var show by remember(storeId, gameId) { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Info, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.steam_ui_game_info))
    }
    if (!show) return
    val context = LocalContext.current
    val initial = remember(record) {
        record.optJSONObject("information") ?: record.optJSONObject("game")?.optJSONObject("metadata")?.optJSONObject("information") ?: record.optJSONObject("game")?.optJSONObject("metadata")?.optJSONObject("product")?.optJSONObject("productDetail")?.optJSONObject("details") ?: JSONObject()
    }
    val information by produceState(initial, storeId, gameId) {
        val file = File(context.filesDir, "store-information/$storeId-${storeHash(gameId)}.json")
        value = withContext(Dispatchers.IO) { runCatching { JSONObject(file.readText()) }.getOrDefault(initial) }
        if (!StoreSessions.paused && (storeId != "gog" || GogSettings.enabled(context)) && System.currentTimeMillis() - file.lastModified() > 24 * 3600000) {
            withContext(Dispatchers.IO) { runCatching {
                val result = when (storeId) {
                    "gog" -> GogApi.product(gameId, true)
                    "itch" -> if (ItchApi.key(context).isBlank()) ItchConnector.information(context, gameId) else initial
                    else -> initial
                }
                if (result.length() > 0) atomicStoreFile(file, result.toString())
                result
            }.getOrDefault(initial) }.let { if (it.length() > 0) value = it }
        }
    }
    val developer = remember(information) {
        information.optString("developer").ifBlank {
            val rows = information.optJSONArray("developers")
            (0 until (rows?.length() ?: 0)).mapNotNull { i ->
                val row = rows!!.opt(i)
                (if (row is JSONObject) row.optString("name") else row as? String)?.takeIf { it.isNotBlank() }
            }.joinToString(", ")
        }
    }
    val publisher = information.optJSONObject("publisher")?.optString("name") ?: information.optString("publisher")
    val release = remember(information) {
        val raw = information.optString("release_date").ifBlank { information.optString("releaseDate") }
        runCatching { java.time.LocalDate.parse(raw.take(10)).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrDefault(raw)
    }
    AlertDialog(onDismissRequest = { show = false }, title = { Text(uiStr(R.string.steam_ui_game_info)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (developer.isNotBlank()) Text(uiStr(R.string.steam_ui_developer) + ": " + developer, style = MaterialTheme.typography.bodySmall)
            if (publisher.isNotBlank()) Text(uiStr(R.string.steam_ui_publisher) + ": " + publisher, style = MaterialTheme.typography.bodySmall)
            if (release.isNotBlank()) Text(uiStr(R.string.steam_ui_release, release), style = MaterialTheme.typography.bodySmall)
            record.optString("language").takeIf { it.isNotBlank() }?.let { Text(uiStr(R.string.store_download_language) + ": " + it) }
            if (developer.isBlank() && publisher.isBlank() && release.isBlank() && record.optString("language").isBlank()) Text(uiStr(R.string.store_no_game_info))
        }
    }, confirmButton = { TextButton(onClick = { show = false }) { Text(uiStr(R.string.ui_close)) } })
}
