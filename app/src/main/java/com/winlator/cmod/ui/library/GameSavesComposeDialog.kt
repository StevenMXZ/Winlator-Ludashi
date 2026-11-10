package com.winlator.cmod.ui.library

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.GameSaveManager
import com.winlator.cmod.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// WinLite's dialog shell and layout; Ludashi's save discovery, backup directory,
// global/per-game automatic backup and restoreLatest behavior stay authoritative.
object GameSavesComposeDialog {
    private const val VIEW_TAG = "game_saves_dialog"

    @JvmStatic
    fun show(fragment: Fragment, shortcut: Shortcut) {
        val activity = fragment.requireActivity()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root.findViewWithTag<View>(VIEW_TAG) != null) return

        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            tag = VIEW_TAG
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WinZOverlayTheme {
                    val visibleState = remember { MutableTransitionState(false) }
                    var busy by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visibleState.targetState = true }
                    LaunchedEffect(visibleState.currentState) {
                        if (!visibleState.currentState && !visibleState.targetState) {
                            (composeView.parent as? ViewGroup)?.removeView(composeView)
                        }
                    }
                    val dismiss: () -> Unit = { if (!busy) visibleState.targetState = false }
                    // System back closes this overlay instead of reaching the screen behind it
                    // (it lives in the activity's own view tree, not a Dialog window).
                    BackHandler(enabled = visibleState.targetState) { dismiss() }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = WinZMotion.dialogScrimEnter(),
                        exit = WinZMotion.dialogScrimExit()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(DialogScrimColor)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { dismiss() }
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = WinZMotion.dialogCardEnter(),
                                exit = WinZMotion.dialogCardExit()
                            ) {
                                // Swallow taps inside the card so they don't reach the dismiss scrim.
                                ThemedDialogSurface(
                                    modifier = Modifier
                                        .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(120).dp)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }
                                ) {
                                    GameSavesPanel(
                                        shortcut = shortcut,
                                        onClose = dismiss,
                                        onBusyChanged = { busy = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        root.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun ColumnScope.GameSavesPanel(shortcut: Shortcut, onClose: () -> Unit, onBusyChanged: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val globalAutoBackup = GameSaveManager.isGlobalAutoBackupEnabled(context)
    var roots by remember(shortcut.file.path) { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    SideEffect { onBusyChanged(busy) }
    var autoBackup by remember { mutableStateOf(GameSaveManager.isAutoBackupEnabled(shortcut)) }
    var latest by remember { mutableStateOf(GameSaveManager.getLatestBackup(shortcut)) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshLatest() {
        latest = GameSaveManager.getLatestBackup(shortcut)
    }

    fun rescan() {
        if (busy) return
        busy = true
        message = "Scanning common save locations…"
        scope.launch {
            roots = withContext(Dispatchers.IO) { GameSaveManager.rediscoverSaveRoots(shortcut) }
            message = if (roots.isEmpty()) {
                "No per-game folder detected. Manual backup will fall back to the whole Wine profile."
            } else {
                "Detected ${roots.size} save location${if (roots.size == 1) "" else "s"}."
            }
            busy = false
        }
    }

    LaunchedEffect(shortcut.file.path) {
        roots = withContext(Dispatchers.IO) { GameSaveManager.getSaveRoots(shortcut) }
        latest = GameSaveManager.getLatestBackup(shortcut)
        loading = false
    }


    Text("Backup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text(shortcut.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = dividerColor())
    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = WinZShapes.Small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Backup folder", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Winlator/Saves/${GameSaveManager.getGameDir(shortcut).name}/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        Text("Latest backup", style = MaterialTheme.typography.labelLarge)
                        Text(
                            latest?.let(::backupLabel) ?: "No backup yet",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic backup", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (globalAutoBackup) "Enabled globally in Winlator Settings"
                            else "Replace auto-latest.zip when the game exits",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = globalAutoBackup || autoBackup,
                        onCheckedChange = {
                            autoBackup = it
                            GameSaveManager.setAutoBackupEnabled(shortcut, it)
                        },
                        enabled = !busy && !globalAutoBackup
                    )
                }

                Text("Save locations", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(22.dp))
                        Text("  Detecting…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (roots.isEmpty()) {
                    Text(
                        "No specific folder detected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    roots.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }

                OutlinedButton(
                    onClick = { rescan() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !loading
                ) {
                    Text("Rescan save locations")
                }

        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (!busy) {
                                busy = true
                                message = "Backing up saves…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        GameSaveManager.backup(shortcut, false)
                                    }
                                    refreshLatest()
                                    message = when {
                                        result.ok && result.wholeProfile ->
                                            "Backup complete: ${result.fileCount} files. No per-game folder was detected, so the Wine profile was used."
                                        result.ok -> "Backup complete: ${result.fileCount} files."
                                        else -> "Backup failed: ${result.error ?: "unknown error"}"
                                    }
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !loading
                    ) {
                        Text("Back up now")
                    }

                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                message = "Restoring latest backup…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        GameSaveManager.restoreLatest(shortcut)
                                    }
                                    message = if (result.ok) {
                                        "Restored ${result.fileCount} files."
                                    } else {
                                        "Restore failed: ${result.error ?: "unknown error"}"
                                    }
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && latest != null
                    ) {
                        Text("Restore")
                    }
                }

    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 4.dp), enabled = !busy) { Text("Close") }
}

private fun backupLabel(file: File): String {
    val date = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    return "${file.name}\n$date"
}

