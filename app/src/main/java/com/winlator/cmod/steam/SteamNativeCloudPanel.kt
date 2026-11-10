package com.winlator.cmod.steam

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Container
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.winlator.cmod.ui.theme.WinZShapes
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SteamNativeCloudPanel(id: Int, container: Container, close: () -> Unit, onBackup: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var direction by remember(id, container.id) { mutableStateOf<SteamNativeCloud.Direction?>(null) }
    var plan by remember { mutableStateOf<SteamNativeCloud.Plan?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var finished by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    LaunchedEffect(id, container.id, direction) {
        if (direction == null) return@LaunchedEffect
        working = true
        plan = null
        error = ""
        result = ""
        val loaded = withContext(Dispatchers.IO) { runCatching { SteamNativeCloud.preview(context, id, container) } }
        loaded.onSuccess { plan = it }.onFailure { error = it.message ?: uiStr(R.string.steam_cloud_failed) }
        working = false
    }
    val entries = plan?.entries.orEmpty().filter {
        if (direction == SteamNativeCloud.Direction.Download) it.remoteSha != null else it.localSha != null
    }
    val needsTransfer = entries.count { it.localSha != it.remoteSha }
    val timestamp = if (direction == SteamNativeCloud.Direction.Download) entries.maxOfOrNull { it.time * 1000L }
        else entries.maxOfOrNull { it.local.lastModified() }
    val date = timestamp?.takeIf { it > 0 }?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    }

    Dialog(onDismissRequest = { if (!working) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(.90f).widthIn(max = 360.dp),
                shape = WinZShapes.Large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .80f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(uiStr(R.string.steam_cloud_saves), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (direction == null) {
                OutlinedButton(onClick = { direction = SteamNativeCloud.Direction.Download }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(uiStr(R.string.steam_cloud_download_action))
                }
                OutlinedButton(onClick = { direction = SteamNativeCloud.Direction.Upload }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(uiStr(R.string.steam_cloud_upload_action))
                }
            } else {
                Text(container.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (working) {
                    if (total > 0) {
                        LinearProgressIndicator(progress = { finished.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        Text("$finished / $total")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(uiStr(R.string.steam_native_cloud_checking))
                    }
                } else if (result.isNotBlank()) {
                    Text(uiStr(R.string.steam_cloud_transfer_done))
                } else if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    if (direction == SteamNativeCloud.Direction.Download)
                        Text(uiStr(R.string.steam_cloud_client_fallback), style = MaterialTheme.typography.bodySmall)
                } else if (plan != null) {
                    Text(uiStr(R.string.steam_cloud_files_found, entries.size))
                    Text(date?.let { uiStr(R.string.steam_cloud_save_date, it) } ?: uiStr(R.string.steam_cloud_date_unknown),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entries.isEmpty() && direction == SteamNativeCloud.Direction.Download)
                        Text(uiStr(R.string.steam_cloud_client_fallback), style = MaterialTheme.typography.bodySmall)
                    else if (needsTransfer == 0) Text(uiStr(R.string.steam_cloud_already_updated), style = MaterialTheme.typography.bodySmall)
                    else Text(uiStr(R.string.steam_cloud_pending_files, needsTransfer), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (direction == null) {
                if (onBackup != null) TextButton(onClick = onBackup) { Text(uiStr(R.string.steam_cloud_backup)) }
                TextButton(onClick = close) { Text(uiStr(R.string.ui_close)) }
            } else {
                TextButton(enabled = !working, onClick = { if (result.isNotBlank()) close() else direction = null }) {
                    Text(if (result.isNotBlank()) uiStr(R.string.ui_close) else uiStr(R.string.steam_ui_back))
                }
                if (result.isBlank() && error.isBlank() && !working && needsTransfer > 0) {
                    TextButton(onClick = {
                        working = true
                        finished = 0
                        total = 0
                        scope.launch {
                            val selected = direction!!
                            val synced = withContext(Dispatchers.IO) {
                                runCatching { SteamNativeCloud.synchronize(context, plan!!, selected) { done, count ->
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { finished = done; total = count }
                                } }
                            }
                            working = false
                            synced.onSuccess { result = it }.onFailure { error = it.message.orEmpty() }
                        }
                    }) {
                        Text(uiStr(if (direction == SteamNativeCloud.Direction.Download) R.string.steam_cloud_apply_download else R.string.steam_cloud_confirm_upload))
                    }
                }
            }
        }
                }
            }
        }
    }
}
