package com.winlator.cmod.stores

import androidx.compose.foundation.layout.*
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
    val context = LocalContext.current
    val initial = remember(record) {
        record.optJSONObject("information") ?: record.optJSONObject("game")?.optJSONObject("metadata")?.optJSONObject("product")?.optJSONObject("productDetail")?.optJSONObject("details") ?: JSONObject()
    }
    val information by produceState(initial, storeId, gameId) {
        val file = File(context.filesDir, "store-information/$storeId-${storeHash(gameId)}.json")
        value = withContext(Dispatchers.IO) { runCatching { JSONObject(file.readText()) }.getOrDefault(initial) }
        if (!StoreSessions.paused && (storeId != "gog" || GogSettings.enabled(context)) && System.currentTimeMillis() - file.lastModified() > 24 * 3600000) {
            withContext(Dispatchers.IO) { runCatching {
                val result = when (storeId) {
                    "gog" -> GogApi.product(gameId, true)
                    "itch" -> ItchConnector.information(context, gameId)
                    else -> initial
                }
                if (result.length() > 0) atomicStoreFile(file, result.toString())
                result
            }.getOrDefault(initial) }.let { if (it.length() > 0) value = it }
        }
    }
    val description = remember(information) {
        val raw = information.optJSONObject("description")?.optString("lead").orEmpty().ifBlank {
            information.optJSONObject("description")?.optString("full").orEmpty().ifBlank { information.optString("description") }
        }
        Jsoup.parse(raw).text().take(2500)
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
    val release = information.optString("release_date").ifBlank { information.optString("releaseDate") }
    if (description.isNotBlank() || developer.isNotBlank() || publisher.isNotBlank() || release.isNotBlank()) OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiStr(R.string.steam_ui_game_info), style = MaterialTheme.typography.titleSmall)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall)
            if (developer.isNotBlank()) Text(uiStr(R.string.steam_ui_developer) + ": " + developer, style = MaterialTheme.typography.bodySmall)
            if (publisher.isNotBlank()) Text(uiStr(R.string.steam_ui_publisher) + ": " + publisher, style = MaterialTheme.typography.bodySmall)
            if (release.isNotBlank()) Text(uiStr(R.string.steam_ui_release, release), style = MaterialTheme.typography.bodySmall)
        }
    }
}
