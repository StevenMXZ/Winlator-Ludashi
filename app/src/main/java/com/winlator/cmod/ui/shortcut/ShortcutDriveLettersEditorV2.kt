package com.winlator.cmod.ui.shortcut

import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.FileUtils
import com.winlator.cmod.ui.settings.SettingsCard
import com.winlator.cmod.ui.settings.SettingsDivider
import kotlinx.coroutines.delay

private data class ShortcutDriveEntryV2(
    val letter: String,
    val path: String
)

private val shortcutDriveLettersV2 = ('D'..'Z').map { it.toString() }

@Composable
internal fun ShortcutDriveLettersEditorV2(container: Container) {
    DriveLettersEditorV2(
        initialDrives = container.drives.orEmpty(),
        subtitle = uiStr(R.string.ui_changes_apply_to_this_container)
    ) { serialized ->
        if (serialized != container.drives.orEmpty()) {
            container.setDrives(serialized)
            container.saveData()
        }
    }
}

@Composable
internal fun DriveLettersEditorV2(
    initialDrives: String,
    subtitle: String = "Changes apply to this container",
    onChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val entries = remember {
        mutableStateListOf<ShortcutDriveEntryV2>().apply {
            Container.drivesIterator(initialDrives).forEach { drive ->
                val letter = drive.getOrNull(0).orEmpty().uppercase()
                val path = drive.getOrNull(1).orEmpty()
                if (letter in shortcutDriveLettersV2) add(ShortcutDriveEntryV2(letter, path))
            }
        }
    }
    var browseIndex by remember { mutableIntStateOf(-1) }
    var committedDrives by remember { mutableStateOf(initialDrives) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val index = browseIndex
        browseIndex = -1
        if (uri == null || index !in entries.indices) return@rememberLauncherForActivityResult
        val path = FileUtils.getFilePathFromUri(context, uri)
        if (!path.isNullOrBlank()) entries[index] = entries[index].copy(path = path)
    }

    val serialized = entries
        .filter { it.path.trim().isNotEmpty() }
        .joinToString("") { "${it.letter}:${it.path.trim()}" }

    LaunchedEffect(serialized) {
        delay(180)
        if (serialized != committedDrives) {
            committedDrives = serialized
            onChanged(serialized)
        }
    }

    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(uiStr(R.string.ui_drive_letters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val nextLetter = shortcutDriveLettersV2.firstOrNull { candidate -> entries.none { it.letter == candidate } }
            IconButton(
                onClick = { if (nextLetter != null) entries.add(ShortcutDriveEntryV2(nextLetter, "")) },
                enabled = nextLetter != null
            ) {
                Icon(Icons.Outlined.Add, uiStr(R.string.ui_add_drive))
            }
        }

        if (entries.isEmpty()) {
            Text(
                uiStr(R.string.ui_no_custom_drives),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            entries.forEachIndexed { index, entry ->
                SettingsDivider()
                ShortcutDriveLetterRowV2(
                    entry = entry,
                    availableLetters = shortcutDriveLettersV2.filter { letter ->
                        letter == entry.letter || entries.none { it.letter == letter }
                    },
                    onLetter = { letter -> entries[index] = entry.copy(letter = letter) },
                    onPath = { path -> entries[index] = entry.copy(path = path) },
                    onBrowse = {
                        browseIndex = index
                        folderPicker.launch(null)
                    },
                    onRemove = { entries.removeAt(index) }
                )
            }
        }
    }
}

@Composable
private fun ShortcutDriveLetterRowV2(
    entry: ShortcutDriveEntryV2,
    availableLetters: List<String>,
    onLetter: (String) -> Unit,
    onPath: (String) -> Unit,
    onBrowse: () -> Unit,
    onRemove: () -> Unit
) {
    var letterMenuOpen by remember(entry.letter) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box {
            Surface(
                onClick = { letterMenuOpen = true },
                modifier = Modifier.width(72.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${entry.letter}:", fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
            }
            DropdownMenu(expanded = letterMenuOpen, onDismissRequest = { letterMenuOpen = false }) {
                availableLetters.forEach { letter ->
                    DropdownMenuItem(
                        text = { Text("$letter:") },
                        onClick = {
                            onLetter(letter)
                            letterMenuOpen = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = entry.path,
            onValueChange = onPath,
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text(uiStr(R.string.ui_path)) },
            placeholder = { Text(uiStr(R.string.ui_storage_emulated_0), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            shape = RoundedCornerShape(10.dp)
        )
        IconButton(onClick = onBrowse) { Icon(Icons.Outlined.FolderOpen, uiStr(R.string.ui_choose_folder)) }
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, uiStr(R.string.ui_remove_drive)) }
    }
}
