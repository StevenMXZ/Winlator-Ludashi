package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

import com.winlator.cmod.R

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle

data class SteamUiSnapshot(val signedIn: Boolean, val gameActive: Boolean, val savedAccount: Boolean, val restoring: Boolean, val authError: String, val ready: Boolean, val busy: Boolean, val status: String, val guard: String,
    val library: String, val libraryLoading: Boolean, val libraryError: String, val downloading: Boolean, val id: Int, val name: String, val done: Long, val total: Long, val tick: Int, val libraryProgress: Int, val rate: Long = 0, val etaSeconds: Long? = null, val verifying: Boolean = false)

@Composable fun observeSteam(): SteamUiSnapshot {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled = SteamSettings.enabled(context)
    var tick by remember { mutableStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(enabled, owner) {
        if (enabled) owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { delay(1000); tick++ }
        }
    }
    val transfer = SteamSession.downloadReading()
    return SteamUiSnapshot(SteamSession.signedIn, SteamSession.gameActive, SteamSession.savedAccount, SteamSession.restoring, SteamSession.authError, SteamSession.connected, SteamSession.signingIn, SteamSession.status,
        SteamSession.guard.label, SteamSession.library, SteamSession.libraryLoading, SteamSession.libraryError, SteamSession.downloading,
        SteamSession.downloadId, SteamSession.downloadName, SteamSession.downloadDone, SteamSession.downloadTotal, tick, SteamSession.libraryProgress, transfer?.bytesPerSecond ?: 0, transfer?.etaSeconds, transfer?.verifying ?: false)
}

@Composable fun SteamDownloadPanel(state: SteamUiSnapshot) {
    if (!state.downloading) return
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.ui.viewinterop.AndroidView(factory = { android.widget.ImageView(it).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
                update = { com.bumptech.glide.Glide.with(it).load("https://shared.steamstatic.com/store_item_assets/steam/apps/${state.id}/library_600x900_2x.jpg").into(it) },
                modifier = Modifier.size(44.dp, 66.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (state.total > 0) LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.weight(1f))
                    else LinearProgressIndicator(modifier = Modifier.weight(1f))
                    IconButton(onClick = { SteamSession.cancel() }, modifier = Modifier.size(36.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Pause, uiStr(R.string.steam_ui_pause_download))
                    }
                }
                Text(downloadTimingText(state), style = MaterialTheme.typography.labelSmall)
                Text("${state.done / 1048576} / ${state.total / 1048576} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun downloadTimingText(state: SteamUiSnapshot): String {
    if (state.verifying) return uiStr(R.string.steam_download_verifying)
    val rate = "%.1f".format(state.rate / 1048576.0)
    val eta = state.etaSeconds?.let { seconds -> if (seconds >= 3600) "%d:%02d:%02d".format(seconds/3600, seconds/60%60, seconds%60) else "%d:%02d".format(seconds/60, seconds%60) }
        ?: uiStr(R.string.steam_download_estimating)
    return uiStr(R.string.steam_download_timing, rate, eta)
}
