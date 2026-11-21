package com.winlator.cmod.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.core.GameSaveManager
import com.winlator.cmod.ui.uiStr

@Composable
internal fun GameSaveLocationsButton(
    shortcut: Shortcut,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit
) {
    var open by remember(shortcut.file.path) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
        Text(uiStr(R.string.game_save_edit_locations))
    }
    if (open) {
        val context = LocalContext.current
        var source by remember { mutableStateOf(GameSaveManager.getSaveFolder(shortcut)) }
        var destination by remember { mutableStateOf(GameSaveManager.getBackupLocation(shortcut)) }
        var error by remember { mutableStateOf<String?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val path = FileUtils.getFilePathFromUri(context, uri)
                if (path.isNullOrBlank()) error = uiStr(R.string.steam_folder_unavailable)
                else {
                    destination = path
                    error = null
                }
            }
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(uiStr(R.string.game_save_edit_locations)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = source,
                        onValueChange = { source = it; error = null },
                        label = { Text(uiStr(R.string.game_save_source_folder)) },
                        placeholder = { Text("C:\\ProgramData\\Game\\Saves") },
                        supportingText = { Text(uiStr(R.string.game_save_source_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(uiStr(R.string.ui_backup_folder), style = MaterialTheme.typography.labelLarge)
                    Text(destination.ifEmpty { "Winlator/Saves" }, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { picker.launch(null) }) {
                        Text(uiStr(R.string.game_save_choose_backup_folder))
                    }
                    if (destination.isNotEmpty()) TextButton(onClick = { destination = ""; error = null }) {
                        Text(uiStr(R.string.game_save_use_default_backup_folder))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { GameSaveManager.setLocations(shortcut, source, destination) }
                        .onSuccess { open = false; onSaved() }
                        .onFailure { error = it.message ?: uiStr(R.string.steam_folder_unavailable) }
                }) { Text(uiStr(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(uiStr(R.string.cancel)) } }
        )
    }
}
