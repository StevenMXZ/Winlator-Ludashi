package com.winlator.cmod.steam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle

data class SteamUiSnapshot(val signedIn: Boolean, val gameActive: Boolean, val savedAccount: Boolean, val restoring: Boolean, val authError: String, val ready: Boolean, val busy: Boolean, val status: String, val guard: String,
    val library: String, val libraryLoading: Boolean, val libraryError: String, val downloading: Boolean, val id: Int, val name: String, val done: Long, val total: Long, val tick: Int)

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
    return SteamUiSnapshot(SteamSession.signedIn, SteamSession.gameActive, SteamSession.savedAccount, SteamSession.restoring, SteamSession.authError, SteamSession.connected, SteamSession.signingIn, SteamSession.status,
        SteamSession.guard.label, SteamSession.library, SteamSession.libraryLoading, SteamSession.libraryError, SteamSession.downloading,
        SteamSession.downloadId, SteamSession.downloadName, SteamSession.downloadDone, SteamSession.downloadTotal, tick)
}

@Composable fun SteamDownloadPanel(state: SteamUiSnapshot) {
    if (!state.downloading) return
    Surface(tonalElevation = 3.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.name, style = MaterialTheme.typography.titleMedium)
            Text(state.status, style = MaterialTheme.typography.bodySmall)
            if (state.total > 0) LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${state.done / 1048576} / ${state.total / 1048576} MB", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { SteamSession.cancel() }) { Text("Pause download") }
            }
        }
    }
}
