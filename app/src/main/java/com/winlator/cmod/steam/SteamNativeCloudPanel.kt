package com.winlator.cmod.steam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.winlator.cmod.container.Container
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SteamNativeCloudPanel(id: Int, container: Container, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<SteamNativeCloud.Plan?>(null) }
    var working by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var finished by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<SteamNativeCloud.Choice?>(null) }
    LaunchedEffect(id, container.id) {
        val loaded = withContext(Dispatchers.IO) { runCatching { SteamNativeCloud.preview(context, id, container) } }
        loaded.onSuccess { plan = it }.onFailure { error = it.message.orEmpty() }
        working = false
    }
    AlertDialog(onDismissRequest = { if (!working) close() }, title = { Text(uiStr(R.string.steam_cloud_sync_now)) }, text = {
        Column {
            Text(container.name)
            if (working) {
                if (total > 0) { LinearProgressIndicator(progress = { finished.toFloat()/total }, modifier = Modifier.fillMaxWidth()); Text("$finished / $total") }
                else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(uiStr(R.string.steam_native_cloud_checking)) }
            } else if (result.isNotBlank()) Text(uiStr(R.string.steam_native_cloud_done, result))
            else if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            else plan?.let { p ->
                Text(uiStr(R.string.steam_native_cloud_files, p.entries.size, p.conflicts))
                if (p.conflicts > 0) {
                    Text(uiStr(R.string.steam_cloud_conflict_help))
                    listOf(SteamNativeCloud.Choice.Cloud to R.string.steam_cloud_use_remote, SteamNativeCloud.Choice.Local to R.string.steam_cloud_keep_local).forEach { (value, label) ->
                        Row { RadioButton(choice == value, { choice = value }); TextButton(onClick = { choice = value }) { Text(uiStr(label)) } }
                    }
                }
            }
        }
    }, confirmButton = {
        if (result.isBlank() && error.isBlank()) TextButton(enabled = !working && plan?.let { it.entries.isNotEmpty() && (it.conflicts == 0 || choice != null) } == true, onClick = {
            working = true
            scope.launch {
                val synced = withContext(Dispatchers.IO) { runCatching { SteamNativeCloud.synchronize(context, plan!!, choice) { done, count ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { finished = done; total = count }
                } } }
                working = false
                synced.onSuccess { result = it }.onFailure { error = it.message.orEmpty() }
            }
        }) { Text(uiStr(R.string.steam_cloud_sync_now)) }
    }, dismissButton = { TextButton(enabled = !working, onClick = close) { Text(uiStr(R.string.ui_close)) } })
}
