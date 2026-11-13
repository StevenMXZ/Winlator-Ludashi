package com.winlator.cmod.ui.library

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
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
import com.winlator.cmod.ui.bindOverlayOwners
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
            bindOverlayOwners(activity)
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
                            composeView.disposeComposition()
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
        message = uiStr(R.string.ui_scanning_common_save_locations)
        scope.launch {
            roots = withContext(Dispatchers.IO) { GameSaveManager.rediscoverSaveRoots(shortcut) }
            message = if (roots.isEmpty()) {
                uiStr(R.string.ui_no_per_game_folder_detected_manual_backup_will_fall_back_to_the_w)
            } else {
                uiStr(R.string.ui_detected_1_s_save_location_2_s, roots.size)
            }
            busy = false
        }
    }

    LaunchedEffect(shortcut.file.path) {
        roots = withContext(Dispatchers.IO) { GameSaveManager.getSaveRoots(shortcut) }
        latest = GameSaveManager.getLatestBackup(shortcut)
        loading = false
    }


    Text(uiStr(R.string.ui_backup), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
                        Text(uiStr(R.string.ui_backup_folder), style = MaterialTheme.typography.labelLarge)
                        Text(
                            uiStr(R.string.ui_winlator_saves_1_s, GameSaveManager.getGameDir(shortcut).name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(uiStr(R.string.ui_latest_backup), style = MaterialTheme.typography.labelLarge)
                        Text(
                            latest?.let(::backupLabel) ?: uiStr(R.string.ui_no_backup_yet),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(uiStr(R.string.ui_automatic_backup), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (globalAutoBackup) uiStr(R.string.ui_enabled_globally_in_winlator_settings)
                            else uiStr(R.string.ui_replace_auto_latest_zip_when_the_game_exits),
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

                Text(uiStr(R.string.ui_save_locations), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(22.dp))
                        Text(uiStr(R.string.ui_detecting), style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (roots.isEmpty()) {
                    Text(
                        uiStr(R.string.ui_no_specific_folder_detected_yet),
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
                    Text(uiStr(R.string.ui_rescan_save_locations))
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
                                message = uiStr(R.string.ui_backing_up_saves)
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        GameSaveManager.backup(shortcut, false)
                                    }
                                    refreshLatest()
                                    message = when {
                                        result.ok && result.wholeProfile ->
                                            uiStr(R.string.ui_backup_complete_1_s_files_no_per_game_folder_was_detected_so_the_, result.fileCount)
                                        result.ok -> uiStr(R.string.ui_backup_complete_1_s_files, result.fileCount)
                                        else -> uiStr(R.string.ui_backup_failed_1_s, result.error ?: "unknown error")
                                    }
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !loading
                    ) {
                        Text(uiStr(R.string.ui_back_up_now))
                    }

                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                message = uiStr(R.string.ui_restoring_latest_backup)
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        GameSaveManager.restoreLatest(shortcut)
                                    }
                                    message = if (result.ok) {
                                        "Restored ${result.fileCount} files."
                                    } else {
                                        uiStr(R.string.ui_restore_failed_1_s, result.error ?: "unknown error")
                                    }
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && latest != null
                    ) {
                        Text(uiStr(R.string.ui_restore))
                    }
                }

    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 4.dp), enabled = !busy) { Text(uiStr(R.string.ui_close)) }
}

private fun backupLabel(file: File): String {
    val date = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    return "${file.name}\n$date"
}
