package com.winlator.cmod.ui.filemanager

import com.winlator.cmod.ui.uiStr
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.core.ExeIconExtractor
import com.winlator.cmod.ui.LandscapeMainNavigation

import com.winlator.cmod.ui.theme.controlAccentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import com.winlator.cmod.ui.library.LibraryImageCache
import com.winlator.cmod.ui.theme.WinZShapes
import com.winlator.cmod.ui.theme.hairlineColor
import com.winlator.cmod.ui.theme.dividerColor

// Icons the file manager shows for entries and for the drive header. The controller (Java) picks
// one by meaning; the actual graphic is resolved in painter() below, so Material vectors (all
// 24dp-grid, so sizes stay consistent) and the two app-specific drawables can be mixed.
enum class FileManagerIcon {
    FOLDER,            // directory row
    FILE,              // regular, non-executable file row
    WINE,              // executable row (until its own icon is extracted) + Drive C:
    INTERNAL_STORAGE,  // Drive D: and external volumes
    SYSTEM_DRIVE       // Drive Z:
}

@Composable
private fun FileManagerIcon.painter(): Painter = when (this) {
    FileManagerIcon.FOLDER -> rememberVectorPainter(Icons.Outlined.Folder)
    FileManagerIcon.FILE -> rememberVectorPainter(Icons.Outlined.Description)
    FileManagerIcon.WINE -> painterResource(R.drawable.icon_wine)
    FileManagerIcon.INTERNAL_STORAGE -> painterResource(R.drawable.ic_internal_storage)
    FileManagerIcon.SYSTEM_DRIVE -> rememberVectorPainter(Icons.Outlined.Storage)
}

// One row in the file listing — a file or a folder in the current directory.
data class FileEntryUiModel(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isExecutable: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val icon: FileManagerIcon,
    // Absolute path to the cached extracted-icon PNG; only set for executables. FileIcon()
    // below extracts and decodes it lazily — only for rows Compose actually renders — so
    // opening a big folder doesn't kick off extraction for every .exe in it at once.
    val iconCachePath: String? = null
)

// One choice in the drive-selector sheet: Drive D:/C:/Z:, a discovered external volume, or the
// "Add External Storage" scan action (id == DRIVE_OPTION_SCAN_ID).
data class DriveOptionUiModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val selected: Boolean
)

data class FileManagerModel(
    val currentPath: String,
    val entries: List<FileEntryUiModel>,
    val driveTitle: String,
    val driveIcon: FileManagerIcon,
    val driveOptions: List<DriveOptionUiModel>,
    val storageUsedText: String,
    val storagePercent: Int,
    val pasteVisible: Boolean
)

interface FileManagerCallbacks {
    fun onUpDir()
    fun onDriveOptionSelected(id: String)
    fun onOpenDirectory(path: String)
    fun onRunFile(path: String)
    fun onAddGame(path: String)
    fun onCopyFile(path: String)
    fun onCutFile(path: String)
    fun onRenameFile(path: String)
    fun onDeleteFile(path: String)
    fun onPasteClick()
}

// Reserved DriveOptionUiModel id for the "Add External Storage / Scan again" row — it triggers a
// rescan rather than navigating anywhere, so the sheet is kept open after it's tapped.
const val DRIVE_OPTION_SCAN_ID = "scan"

// The File Manager screen. Hosted by MainShell through FileManagerRoute (FileManagerRoute.kt);
// the logic lives in FileManagerController.
@Composable
internal fun FileManagerScreen(model: FileManagerModel, callbacks: FileManagerCallbacks) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = LocalContext.current as? MainActivity
    var driveSheetOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) {
            LandscapeMainNavigation(activity, 0, uiStr(R.string.ui_file_manager))
        } else {
            // The existing MainActivity toolbar owns portrait navigation.
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = callbacks::onUpDir) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.ui_up_directory))
            }
            Spacer(Modifier.width(4.dp))
            Surface(
                onClick = { driveSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
                shape = WinZShapes.Medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, hairlineColor())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(model.driveIcon.painter(), null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        model.driveTitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // The current path used to be its own card above this one; it now takes the place of
        // the "Storage" caption over the usage bar.
        StorageMeter(
            path = model.currentPath,
            usedText = model.storageUsedText,
            percent = model.storagePercent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        HorizontalDivider(color = dividerColor())

        // In portrait MainShell's bottom nav floats over this tab; keep the paste button and the
        // last rows above it (File Manager used to be a detail screen in portrait, without the nav).
        val navClearance = 0.dp
        Box(Modifier.weight(1f)) {
            if (model.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(uiStr(R.string.ui_this_folder_is_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val listState = remember(model.currentPath) { LazyListState() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 92.dp + navClearance)
                ) {
                    items(model.entries, key = { it.path }) { entry ->
                        FileRow(entry = entry, callbacks = callbacks)
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 73.dp),
                            color = dividerColor()
                        )
                    }
                }
            }

            if (model.pasteVisible) {
                FloatingActionButton(
                    onClick = callbacks::onPasteClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(start = 18.dp, top = 18.dp, end = 18.dp, bottom = 18.dp + navClearance),
                    containerColor = controlAccentColor(),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Outlined.ContentPaste, uiStr(R.string.ui_paste))
                }
            }
        }
    }

    if (driveSheetOpen) {
        DriveSelectorSheet(
            options = model.driveOptions,
            onSelect = { id ->
                if (id != DRIVE_OPTION_SCAN_ID) driveSheetOpen = false
                callbacks.onDriveOptionSelected(id)
            },
            onDismiss = { driveSheetOpen = false }
        )
    }
}

@Composable
private fun StorageMeter(path: String, usedText: String, percent: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = WinZShapes.Medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairlineColor())
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    path,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // Keeps both the drive root and the current folder visible on long paths.
                    overflow = TextOverflow.MiddleEllipsis
                )
                Spacer(Modifier.width(10.dp))
                Text(usedText, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = controlAccentColor(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

// The file's action menu, anchored right on its row (same DropdownMenu/DropdownMenuItem
// technique as SettingChoice's audio-driver picker) rather than a full-screen dialog: tapping a
// file, or long-pressing any row, opens a small list right where it was tapped.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FileEntryUiModel, callbacks: FileManagerCallbacks) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (entry.isDirectory) callbacks.onOpenDirectory(entry.path) else menuExpanded = true
                    },
                    onLongClick = { menuExpanded = true }
                )
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                FileIcon(entry, Modifier.size(30.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    entryDetails(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier.widthIn(min = 200.dp, max = 320.dp),
            shape = WinZShapes.Medium,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            if (entry.isExecutable) {
                DropdownMenuItem(
                    text = { Text(uiStr(R.string.ui_run_open)) },
                    leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                    onClick = { menuExpanded = false; callbacks.onRunFile(entry.path) }
                )
                DropdownMenuItem(
                    text = { Text(uiStr(R.string.ui_add_this_game)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                    onClick = { menuExpanded = false; callbacks.onAddGame(entry.path) }
                )
            }
            DropdownMenuItem(
                text = { Text(uiStr(R.string.ui_copy)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menuExpanded = false; callbacks.onCopyFile(entry.path) }
            )
            DropdownMenuItem(
                text = { Text(uiStr(R.string.ui_cut_move)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCut, null) },
                onClick = { menuExpanded = false; callbacks.onCutFile(entry.path) }
            )
            DropdownMenuItem(
                text = { Text(uiStr(R.string.ui_rename)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = { menuExpanded = false; callbacks.onRenameFile(entry.path) }
            )
            DropdownMenuItem(
                text = { Text(uiStr(R.string.ui_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                },
                onClick = { menuExpanded = false; callbacks.onDeleteFile(entry.path) }
            )
        }
    }
}

// Mirrors FileManagerController's (formerly FileManagerFragment's) folderDetails()/fileDetails()/modifiedLabel() text
// formatting. Kept here rather than pushed from Java so it stays lazy: a folder's child count
// (File.list()) is only read for rows Compose actually composes, matching the RecyclerView's
// original per-visible-item binding cost instead of paying it for the whole directory upfront.
@Composable
private fun entryDetails(entry: FileEntryUiModel): String {
    val dateText = remember(entry.lastModified) {
        if (entry.lastModified <= 0) "" else
            SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(entry.lastModified))
    }
    return if (entry.isDirectory) {
        val count = remember(entry.path) { File(entry.path).list()?.size ?: 0 }
        "Folder  •  $count" + (if (count == 1) " item" else " items") +
                (if (dateText.isEmpty()) "" else "  •  $dateText")
    } else {
        formatSize(entry.sizeBytes) + (if (dateText.isEmpty()) "" else "  •  $dateText")
    }
}

private fun formatSize(size: Long): String {
    if (size < 1024) return "$size B"
    val z = (63 - java.lang.Long.numberOfLeadingZeros(size)) / 10
    return String.format(Locale.getDefault(), "%.1f %sB", size / (1L shl (z * 10)).toDouble(), " KMGTPE"[z])
}

@Composable
private fun FileIcon(entry: FileEntryUiModel, modifier: Modifier = Modifier) {
    val cachePath = entry.iconCachePath
    if (entry.isExecutable && cachePath != null) {
        // Start from an already decoded icon (same cache as the Library) so rows that scroll back
        // into view, or a folder you return to, don't flash the generic file icon first.
        val iconKey = remember(cachePath) { LibraryImageCache.keyFor(cachePath) }
        val bitmap by produceState(LibraryImageCache.peek(iconKey), entry.path, cachePath) {
            value = withContext(Dispatchers.IO) {
                val cacheFile = File(cachePath)
                if (!cacheFile.exists()) {
                    // ExeIconExtractor.extractAsync runs the PE-resource parsing on its own
                    // background thread and calls back (off the main thread) when the PNG has
                    // been written; bridge that callback into this coroutine.
                    suspendCancellableCoroutine<Unit> { cont ->
                        ExeIconExtractor.extractAsync(File(entry.path), cacheFile, false) {
                            if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        }
                    }
                }
                LibraryImageCache.load(cacheFile.absolutePath)
            }
        }
        val loaded = bitmap
        if (loaded != null) {
            Image(loaded.asImageBitmap(), null, modifier = modifier)
            return
        }
    }
    Icon(
        entry.icon.painter(),
        null,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveSelectorSheet(
    options: List<DriveOptionUiModel>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        val accent = controlAccentColor()
        Column(
            Modifier
                .fillMaxWidth()
                // Many drives don't fit a landscape phone's height: scroll instead of clipping.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 20.dp)
        ) {
            options.forEach { option ->
                val isScan = option.id == DRIVE_OPTION_SCAN_ID
                Surface(
                    onClick = { onSelect(option.id) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = WinZShapes.Medium,
                    color = if (option.selected) accent.copy(alpha = 0.16f) else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isScan) {
                            Icon(
                                Icons.Outlined.Refresh,
                                null,
                                tint = accent,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = when {
                                    option.selected -> accent
                                    isScan -> accent
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (option.subtitle.isNotEmpty()) {
                                Text(
                                    option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (option.selected) Icon(Icons.Outlined.Check, null, tint = accent)
                    }
                }
            }
        }
    }
}
