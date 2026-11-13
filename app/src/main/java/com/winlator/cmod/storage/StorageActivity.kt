package com.winlator.cmod.storage

import android.os.Bundle
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import com.winlator.cmod.R
import com.winlator.cmod.steam.ImmersiveSteamActivity
import com.winlator.cmod.steam.SteamGames
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.*
import java.util.Locale

class StorageActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WinZTheme { Screen() } }
    }
    @Composable private fun Screen() {
        var snapshot by remember { mutableStateOf(StorageRepository.cached) }
        var scanning by remember { mutableStateOf(false) }
        var working by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var scanError by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<StorageCategory?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var confirmation by remember { mutableStateOf<List<StorageEntry>?>(null) }
        val scope = rememberCoroutineScope()
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        LaunchedEffect(owner, refresh) {
            owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                if (StorageRepository.busy()) { scanError = uiStr(R.string.storage_busy); return@repeatOnLifecycle }
                scanning = true; scanError = ""
                try {
                    snapshot = withContext(Dispatchers.IO) {
                        val operation = currentCoroutineContext()
                        StorageRepository.scan(applicationContext) {
                            operation.ensureActive()
                            check(!com.winlator.cmod.core.ContainerSessionGate.hasActiveSessions()) { uiStr(R.string.storage_busy) }
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { scanError = failure.message.orEmpty() }
                finally { scanning = false }
            }
        }
        BackHandler(working) {}
        BackHandler(selected != null && !working) { selected = null }
        var message by remember { mutableStateOf("") }
        var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
        fun archive(uri: android.net.Uri, restore: Boolean) {
            if (working) return
            working = true; error = ""; message = ""
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val job = currentCoroutineContext()
                        if (restore) StorageBackup.restore(applicationContext, uri) { job.ensureActive() }
                        else StorageBackup.backup(applicationContext, uri) { job.ensureActive() }
                    }
                    message = uiStr(if (restore) R.string.storage_restore_done else R.string.storage_backup_done)
                    SteamGames.changed()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message.orEmpty() }
                finally { working = false; refresh++ }
            }
        }
        val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tar")) { uri -> uri?.let { archive(it, false) } }
        val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !working, onClick = { if (selected == null) finish() else selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                Text(selected?.let(::categoryTitle) ?: uiStr(R.string.storage_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(enabled = !scanning && !working, onClick = { error = ""; refresh++ }) { Icon(Icons.Outlined.Refresh, uiStr(R.string.storage_refresh)) }
            }
            if (scanning || working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, Modifier.padding(18.dp), color = MaterialTheme.colorScheme.primary)
            if (error.isNotBlank() || scanError.isNotBlank()) Text(error.ifBlank { scanError }, Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
            val data = snapshot
            if (data == null && scanning) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(uiStr(R.string.storage_calculating)) }
            else if (data != null) LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selected == null) {
                    item("summary") {
                        ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        var start = -90f
                                        val stroke = 13.dp.toPx()
                                        visibleCategories.forEach { category ->
                                            val sweep = if (data.total == 0L) 0f else (data.bytes(category).toDouble() / data.total * 360).toFloat()
                                            if (sweep > 0) drawArc(categoryColor(category), start, (sweep - 2).coerceAtLeast(1f), false, Offset(stroke / 2, stroke / 2), androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                                            start += sweep
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(bytes(data.total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(uiStr(R.string.storage_used), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val free = remember(refresh, data) { runCatching { StatFs(filesDir.path).availableBytes }.getOrDefault(0) }
                                    Text(uiStr(R.string.storage_available, bytes(free)), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    if (data.unreadable > 0) item { Text(uiStr(R.string.storage_unreadable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(enabled = !scanning && !working, modifier = Modifier.weight(1f), onClick = { backup.launch("Ludashi-backup-${java.time.LocalDate.now()}.tar") }) {
                                Icon(Icons.Outlined.Backup, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.storage_backup))
                            }
                            OutlinedButton(enabled = !scanning && !working, modifier = Modifier.weight(1f), onClick = { restore.launch(arrayOf("application/x-tar", "application/octet-stream")) }) {
                                Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(8.dp)); Text(uiStr(R.string.storage_restore))
                            }
                        }
                    }
                    items(visibleCategories.filter { data.bytes(it) > 0 }, key = { it.name }) { category ->
                        CategoryRow(category, data.bytes(category), data.total) { selected = category }
                    }
                } else {
                    val category = selected!!
                    val entries = data.entries.filter { it.target.category == category }.sortedByDescending { it.bytes }
                    item {
                        Text(bytes(data.bytes(category)), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    items(entries, key = { it.target.file.path }) { entry ->
                        OutlinedCard(shape = RoundedCornerShape(18.dp)) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(categoryIcon(category), null, tint = categoryColor(category))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(entry.target.title, style = MaterialTheme.typography.titleSmall)
                                    Text(bytes(entry.bytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    Text(entry.target.file.path, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (entry.target.removable) IconButton(enabled = !working && !scanning, onClick = { confirmation = listOf(entry) }) { Icon(Icons.Outlined.DeleteOutline, uiStr(R.string.remove), tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    if (entries.isEmpty()) item { Text(uiStr(R.string.storage_empty)) }
                }
            }
        }
        restoreUri?.let { uri ->
            AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text(uiStr(R.string.storage_restore)) },
                text = { Text(uiStr(R.string.storage_restore_confirm)) },
                confirmButton = { Button(enabled = !working && !scanning, onClick = { restoreUri = null; archive(uri, true) }) { Text(uiStr(R.string.storage_restore)) } },
                dismissButton = { TextButton(onClick = { restoreUri = null }) { Text(uiStr(R.string.cancel)) } })
        }
        confirmation?.let { entries ->
            val game = entries.any { it.target.category == StorageCategory.GAMES }
            val cache = entries.all { it.target.category == StorageCategory.CACHE }
            AlertDialog(onDismissRequest = { if (!working) confirmation = null }, title = { Text(uiStr(if (game) R.string.steam_ui_uninstall else R.string.storage_remove_title)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (cache) uiStr(R.string.storage_cache_help) else entries.joinToString("\n") { it.target.title })
                    if (entries.any { it.target.category == StorageCategory.CONTAINERS }) Text(uiStr(R.string.storage_remove_container))
                    Text(bytes(entries.sumOf { it.bytes }), fontWeight = FontWeight.Bold)
                    if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                } }, confirmButton = { Button(enabled = !working, onClick = {
                    working = true; error = ""
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { entries.forEach { StorageRepository.remove(applicationContext, it) } } }
                        working = false; confirmation = null
                        result.onSuccess { SteamGames.changed(); refresh++ }.onFailure { error = it.message.orEmpty(); refresh++ }
                    }
                }) { Text(uiStr(if (game) R.string.steam_ui_uninstall else R.string.remove)) } },
                dismissButton = { TextButton(enabled = !working, onClick = { confirmation = null }) { Text(uiStr(R.string.cancel)) } })
        }
    }
}

private val visibleCategories = StorageCategory.entries.filter { it !in listOf(StorageCategory.SYSTEM, StorageCategory.OTHER) }

@Composable private fun CategoryRow(category: StorageCategory, used: Long, total: Long, open: () -> Unit) {
    OutlinedCard(onClick = open, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(categoryIcon(category), null, tint = categoryColor(category))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) { Text(categoryTitle(category), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text(bytes(used), style = MaterialTheme.typography.labelLarge) }
                LinearProgressIndicator(progress = { if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth(), color = categoryColor(category))
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}
private fun categoryTitle(category: StorageCategory) = uiStr(when (category) {
    StorageCategory.GAMES -> R.string.storage_games; StorageCategory.COMPONENTS -> R.string.storage_components; StorageCategory.CONTAINERS -> R.string.storage_containers
    StorageCategory.DOWNLOADS -> R.string.storage_downloads; StorageCategory.CACHE -> R.string.storage_cache; StorageCategory.BACKUPS -> R.string.storage_backups
    StorageCategory.LOGS -> R.string.storage_logs; StorageCategory.SYSTEM -> R.string.storage_system; StorageCategory.OTHER -> R.string.storage_other
})
private fun categoryIcon(category: StorageCategory) = when (category) {
    StorageCategory.GAMES -> Icons.Outlined.SportsEsports; StorageCategory.COMPONENTS -> Icons.Outlined.Extension; StorageCategory.CONTAINERS -> Icons.Outlined.Dns
    StorageCategory.DOWNLOADS -> Icons.Outlined.Download; StorageCategory.CACHE -> Icons.Outlined.CleaningServices; StorageCategory.BACKUPS -> Icons.Outlined.Backup
    StorageCategory.LOGS -> Icons.Outlined.Description; StorageCategory.SYSTEM -> Icons.Outlined.Android; StorageCategory.OTHER -> Icons.Outlined.FolderOpen
}
private fun categoryColor(category: StorageCategory) = Color(when (category) {
    StorageCategory.GAMES -> 0xFFAD85DE; StorageCategory.COMPONENTS -> 0xFF63C7B6; StorageCategory.CONTAINERS -> 0xFF75A6F1
    StorageCategory.DOWNLOADS -> 0xFFE6B85C; StorageCategory.CACHE -> 0xFFE39C72; StorageCategory.BACKUPS -> 0xFF7CC98B
    StorageCategory.LOGS -> 0xFFD285A6; StorageCategory.SYSTEM -> 0xFF9CA7BC; StorageCategory.OTHER -> 0xFF888A96
})
internal fun bytes(value: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble(); var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    return if (unit == 0) "$value B" else String.format(Locale.getDefault(), "%.1f %s", amount, units[unit])
}
