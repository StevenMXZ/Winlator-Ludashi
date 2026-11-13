package com.winlator.cmod.gog

import android.content.Intent
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bumptech.glide.Glide
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import org.json.JSONObject
import java.io.File

@Composable internal fun GogDownloadPanel(state: GogState, transfer: JSONObject? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val record = transfer ?: state.transfers[state.activeId]
    if (record == null || transfer == null && !state.busy) return
    val id = record.optString("id")
    val active = state.busy && state.activeId == id
    val status = if (active) state.stage else uiStr(when (record.optString("status")) {
        "completed" -> R.string.store_download_complete
        "failed" -> R.string.store_download_failed
        else -> R.string.store_download_paused
    })
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val art = remember(id, state.revision) { File(context.cacheDir, "gog-art/$id.jpg").takeIf { it.isFile } }
                AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                    update = { Glide.with(it).load(art ?: record.optString("image")).error(R.drawable.ic_gog).into(it) }, onRelease = { Glide.with(it).clear(it) }, modifier = Modifier.size(54.dp))
                Column(Modifier.weight(1f)) { Text(record.optString("title"), style = MaterialTheme.typography.titleSmall); Text(status, style = MaterialTheme.typography.bodySmall) }
                if (active) TextButton(enabled = state.cancellable, onClick = { GogLibrary.cancelled = true; GogApi.cancelWork() }) { Text(uiStr(R.string.cancel)) }
                else if (record.optString("status") != "completed" && state.signedIn && !state.busy) TextButton(onClick = {
                    context.startForegroundService(Intent(context, GogDownloadService::class.java).putExtra("game", id).putExtra("container", record.optInt("container")).putExtra("installer", record.optString("installer")))
                }) { Text(uiStr(R.string.store_retry_download)) }
            }
            val done = if (active) state.done else record.optLong("done")
            val total = if (active) state.total else record.optLong("total")
            if (active) { if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (active && state.speed > 0) com.winlator.cmod.stores.StoreTransferStatistics(done, total, state.speed)
            if (total > 0) Text("${done / 1048576} / ${total / 1048576} MiB", style = MaterialTheme.typography.bodySmall)
            if (!active && record.optString("error").isNotBlank()) Text(record.optString("error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
