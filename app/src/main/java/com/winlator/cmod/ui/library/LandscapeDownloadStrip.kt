package com.winlator.cmod.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.gog.GogState
import com.winlator.cmod.steam.SteamUiSnapshot
import com.winlator.cmod.steam.downloadTimingText
import com.winlator.cmod.stores.DownloadNotifications
import com.winlator.cmod.stores.LudashiStore
import com.winlator.cmod.stores.StoreSnapshot
import com.winlator.cmod.stores.transferTimingText
import com.winlator.cmod.ui.uiStr

@Composable
internal fun LandscapeDownloadStrip(steam: SteamUiSnapshot, gog: GogState?, itch: StoreSnapshot?) {
    if (!steam.downloading && gog?.busy != true && itch?.busy != true) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (steam.downloading) {
            TransferCard("Steam", steam.name, steam.done, steam.total, downloadTimingText(steam), Modifier.weight(1f))
        }
        if (gog != null && gog.busy) {
            val title = gog.transfers[gog.activeId]?.optString("title").orEmpty()
            val status = if (gog.speed > 0) transferTimingText(gog.done, gog.total, gog.speed) else gog.stage
            TransferCard("GOG", title, gog.done, gog.total, status, Modifier.weight(1f))
        }
        if (itch != null && itch.busy) {
            val title = itch.activeGame?.title.orEmpty()
            val status = if (itch.speed > 0) transferTimingText(itch.done, itch.total, itch.speed) else itch.stage
            TransferCard(LudashiStore.ITCH.title, title, itch.done, itch.total, status, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TransferCard(store: String, game: String, done: Long, total: Long, status: String, modifier: Modifier) {
    val context = LocalContext.current
    Surface(
        onClick = { context.startActivity(DownloadNotifications.downloadsIntent(context, store)) },
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val progress = DownloadNotifications.progress(done, total)
            Text(listOf(store, game).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(if (total > 0) "${progress / 10}%" else null,
                status.ifBlank { uiStr(R.string.gog_preparing) }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (total > 0) LinearProgressIndicator(progress = { progress / 1000f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
