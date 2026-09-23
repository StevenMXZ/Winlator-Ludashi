package com.winlator.cmod.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.ui.LandscapeMainNavigation
import com.winlator.cmod.ui.theme.WinZTheme
import com.winlator.cmod.ui.theme.WinlatorThemePreferenceCard
import kotlin.math.roundToInt

@Immutable
data class SettingChoice(val id: String, val name: String)

@Immutable
data class SettingsModel(
    val box64Presets: List<SettingChoice>,
    val selectedBox64Preset: String,
    val fexPresets: List<SettingChoice>,
    val selectedFexPreset: String,
    val soundFonts: List<SettingChoice>,
    val winlatorPath: String,
    val shortcutPath: String,
    val bigPicture: Boolean,
    val cursorSpeedPercent: Int,
    val cursorLock: Boolean,
    val xInput: Boolean,
    val useDri3: Boolean,
    val useXr: Boolean,
    val xrSupported: Boolean,
    val highRefreshRate: Boolean,
    val fileProvider: Boolean,
    val openInBrowser: Boolean,
    val shareClipboard: Boolean,
    val pauseWine: Boolean,
    val removeLoadingBar: Boolean,
    val gameSavesAllShortcuts: Boolean,
    val wineDebug: Boolean,
    val wineDebugChannels: String,
    val winlatorLogs: Boolean,
    val box64Logs: Boolean,
    val customApiKeyEnabled: Boolean,
    val customApiKey: String,
    val contentsUrl: String,
    val wineDebugOptions: List<String>
)

@Stable
interface SettingsCallbacks {
    fun onOpenComponents()
    fun onBox64PresetSelected(id: String)
    fun onFexPresetSelected(id: String)
    fun onInstallSoundFont()
    fun onRemoveSoundFont(name: String)
    fun onChooseWinlatorPath()
    fun onChooseShortcutPath()
    fun onBooleanChanged(key: String, value: Boolean)
    fun onCursorSpeedChanged(percent: Int)
    fun onCustomApiKeyChanged(value: String)
    fun onContentsUrlChanged(value: String)
    fun onWineDebugChannelsChanged(value: String)
    fun onReinstallImageFs()
    fun onPresetAction(kind: String, id: String, action: String)
}

object SettingsComposeHost {
    @JvmStatic
    fun create(context: Context, model: SettingsModel, callbacks: SettingsCallbacks): ComposeView {
        val modelState = mutableStateOf(model)
        return ComposeView(context).apply {
            tag = modelState
            setBackgroundColor(Color.TRANSPARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WinZTheme { SettingsScreen(modelState.value, callbacks) } }
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun update(view: View?, model: SettingsModel) {
        (view?.tag as? MutableState<SettingsModel>)?.value = model
    }
}

@Composable
private fun SettingsScreen(model: SettingsModel, callbacks: SettingsCallbacks) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val activity = context as? MainActivity

    DisposableEffect(activity, landscape) {
        if (landscape) {
            activity?.setBottomNavigationVisible(false)
            activity?.setMainToolbarVisible(false)
        }
        onDispose {
            if (landscape) {
                activity?.setBottomNavigationVisible(true)
                activity?.setMainToolbarVisible(true)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (landscape) LandscapeMainNavigation(activity, R.id.main_menu_settings, "Settings")
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item("appearance-title") { SectionTitle("APPEARANCE") }
            item("theme") { WinlatorThemePreferenceCard() }

            item("environment-title") { SectionTitle("ENVIRONMENTS") }
            item("containers") {
                NavigationRow(Icons.Outlined.Dns, "Containers", "Create and manage Windows environments") {
                    context.startActivity(Intent(context, ContainersSettingsActivity::class.java))
                }
            }
            item("components") {
                NavigationRow(Icons.Outlined.Apps, "Components", "Wine, Proton, DXVK, VKD3D and runtimes", callbacks::onOpenComponents)
            }

            item("presets-title") { SectionTitle("PRESETS") }
            item("presets") {
                GroupCard {
                    PresetChoiceRow(
                        icon = Icons.Outlined.Memory,
                        title = stringResource(R.string.box64_preset),
                        choices = model.box64Presets,
                        selectedId = model.selectedBox64Preset,
                        kind = "box64",
                        onSelected = callbacks::onBox64PresetSelected,
                        onAction = callbacks::onPresetAction
                    )
                    GroupDivider()
                    PresetChoiceRow(
                        icon = Icons.Outlined.Speed,
                        title = stringResource(R.string.fexcore_preset),
                        choices = model.fexPresets,
                        selectedId = model.selectedFexPreset,
                        kind = "fexcore",
                        onSelected = callbacks::onFexPresetSelected,
                        onAction = callbacks::onPresetAction
                    )
                }
            }

            item("sound-title") { SectionTitle(stringResource(R.string.sound)) }
            item("soundfonts") {
                SoundFontCard(model.soundFonts, callbacks::onInstallSoundFont, callbacks::onRemoveSoundFont)
            }

            item("paths-title") { SectionTitle("PATH SETTINGS") }
            item("winlator-path") { NavigationRow(Icons.Outlined.Storage, "Winlator Path", model.winlatorPath, callbacks::onChooseWinlatorPath) }
            item("shortcut-path") { NavigationRow(Icons.Outlined.FolderOpen, "Shortcut Export Path", model.shortcutPath, callbacks::onChooseShortcutPath) }

            item("game-saves-title") { SectionTitle("GAME SAVES") }
            item("game-saves") {
                GroupCard {
                    ToggleRow(
                        "Game Saves for all shortcuts",
                        model.gameSavesAllShortcuts,
                        "Automatically detect and back up saves when any shortcut exits"
                    ) { callbacks.onBooleanChanged("game_saves_all_shortcuts", it) }
                }
            }

            item("big-picture-title") { SectionTitle("BIG PICTURE MODE") }
            item("big-picture") {
                GroupCard {
                    ToggleRow("Enable Big Picture Mode on App Launch", model.bigPicture) { callbacks.onBooleanChanged("enable_big_picture_mode", it) }
                    GroupDivider()
                    ToggleRow("Set SteamGrid API Key? (Cover Art)", model.customApiKeyEnabled) { callbacks.onBooleanChanged("enable_custom_api_key", it) }
                }
            }
            if (model.customApiKeyEnabled) {
                item("api-key") { EditableValueCard("SteamGridDB API Key", model.customApiKey, callbacks::onCustomApiKeyChanged) }
            }

            item("xserver-title") { SectionTitle(stringResource(R.string.xserver)) }
            item("xserver") {
                GroupCard {
                    CursorSpeedRow(model.cursorSpeedPercent, callbacks::onCursorSpeedChanged)
                    GroupDivider()
                    ToggleRow(stringResource(R.string.use_dri3_extension), model.useDri3) { callbacks.onBooleanChanged("use_dri3", it) }
                    if (model.xrSupported) {
                        GroupDivider()
                        ToggleRow(stringResource(R.string.use_xr), model.useXr) { callbacks.onBooleanChanged("use_xr", it) }
                    }
                    GroupDivider()
                    ToggleRow("Capture External Pointer", model.cursorLock) { callbacks.onBooleanChanged("cursor_lock", it) }
                    GroupDivider()
                    ToggleRow("Disable Xinput (Used for Exclusive M/KB support)", model.xInput) { callbacks.onBooleanChanged("xinput_toggle", it) }
                }
            }

            item("logs-title") { SectionTitle(stringResource(R.string.logs)) }
            item("logs") {
                GroupCard {
                    ToggleRow(stringResource(R.string.enable_wine_debug), model.wineDebug) { callbacks.onBooleanChanged("enable_wine_debug", it) }
                    if (model.wineDebug) {
                        GroupDivider()
                        WineDebugChannelsRow(
                            selectedValue = model.wineDebugChannels,
                            options = model.wineDebugOptions,
                            onSave = callbacks::onWineDebugChannelsChanged
                        )
                    }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.enable_winlator_logs), model.winlatorLogs) { callbacks.onBooleanChanged("enable_winlator_logs", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.enable_box64_logs), model.box64Logs) { callbacks.onBooleanChanged("enable_box64_logs", it) }
                }
            }

            item("experimental-title") { SectionTitle(stringResource(R.string.experimental)) }
            item("experimental") {
                GroupCard {
                    ToggleRow(stringResource(R.string.enable_file_provider), model.fileProvider) { callbacks.onBooleanChanged("enable_file_provider", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.open_with_android_browser), model.openInBrowser) { callbacks.onBooleanChanged("open_with_android_browser", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.share_android_clipboard), model.shareClipboard) { callbacks.onBooleanChanged("share_android_clipboard", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.pause_resume_wine), model.pauseWine) { callbacks.onBooleanChanged("pause_resume_wine", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.high_refresh_rate), model.highRefreshRate) { callbacks.onBooleanChanged("high_refresh_rate_mode", it) }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.remove_loading_bar_when_booting_games), model.removeLoadingBar) { callbacks.onBooleanChanged("remove_loading_bar_when_booting_games", it) }
                }
            }
            item("contents-url") { EditableValueCard("Downloadable Contents URL", model.contentsUrl, callbacks::onContentsUrlChanged) }

            item("imagefs-title") { SectionTitle(stringResource(R.string.imagefs)) }
            item("imagefs") { NavigationRow(Icons.Outlined.Refresh, stringResource(R.string.reinstall_imagefs), null, callbacks::onReinstallImageFs) }

            item("about-title") { SectionTitle("ABOUT") }
            item("about") {
                NavigationRow(Icons.Outlined.Info, "About", null) { activity?.showAboutDialog() }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(start = 3.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) { Column { content() } }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
}

@Composable
private fun NavigationRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(icon)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector) {
    Surface(Modifier.size(38.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, modifier = Modifier.size(21.dp)) }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onChecked: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 15.dp, end = 11.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun CursorSpeedRow(value: Int, onChanged: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(10, 200).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cursor speed", modifier = Modifier.weight(1f))
            Text("${draft.roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChanged(draft.roundToInt()) },
            valueRange = 10f..200f
        )
    }
}

@Composable
private fun PresetChoiceRow(
    icon: ImageVector,
    title: String,
    choices: List<SettingChoice>,
    selectedId: String,
    kind: String,
    onSelected: (String) -> Unit,
    onAction: (String, String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId } ?: choices.firstOrNull()

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = { expanded = !expanded },
                modifier = Modifier.weight(1f),
                color = androidx.compose.ui.graphics.Color.Transparent
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallIcon(icon)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, fontWeight = FontWeight.Medium)
                        Text(selected?.name.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { actionsOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, "Preset actions")
                }
                DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Create new") },
                        leadingIcon = { Icon(Icons.Outlined.Add, null) },
                        onClick = {
                            actionsOpen = false
                            onAction(kind, "", "add")
                        }
                    )
                    if (selectedId.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("Clone") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                actionsOpen = false
                                onAction(kind, selectedId, "duplicate")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = {
                                actionsOpen = false
                                onAction(kind, selectedId, "edit")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = {
                                actionsOpen = false
                                onAction(kind, selectedId, "remove")
                            }
                        )
                    }
                }
            }
        }

        if (expanded) {
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
            choices.forEach { choice ->
                Surface(
                    onClick = {
                        onSelected(choice.id)
                        expanded = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 62.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            choice.name,
                            modifier = Modifier.weight(1f),
                            color = if (choice.id == selectedId) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (choice.id == selectedId) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (choice.id == selectedId) Text("✓", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun WineDebugChannelsRow(
    selectedValue: String,
    options: List<String>,
    onSave: (String) -> Unit
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val selectedChannels = remember(selectedValue) {
        selectedValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val summary = when {
        selectedChannels.isEmpty() -> "No channels selected"
        selectedChannels.size <= 3 -> selectedChannels.joinToString(", ")
        else -> selectedChannels.take(3).joinToString(", ") + " +${selectedChannels.size - 3}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { dialogOpen = true }
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Wine debug channels", style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (dialogOpen) {
        WineDebugChannelsDialog(
            selectedValue = selectedValue,
            options = options,
            onDismiss = { dialogOpen = false },
            onApply = {
                dialogOpen = false
                onSave(it)
            }
        )
    }
}

@Composable
private fun WineDebugChannelsDialog(
    selectedValue: String,
    options: List<String>,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val selectedAtOpen = remember(selectedValue) {
        selectedValue.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val allOptions = remember(options, selectedValue) { (selectedAtOpen + options).distinct() }
    var selected by remember(selectedValue) { mutableStateOf(selectedAtOpen.toSet()) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(allOptions, query) {
        if (query.isBlank()) allOptions else allOptions.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wine debug channels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search channels") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered, key = { it }) { channel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = selected.toMutableSet().apply {
                                        if (!add(channel)) remove(channel)
                                    }
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = channel in selected,
                                onCheckedChange = { checked ->
                                    selected = selected.toMutableSet().apply {
                                        if (checked) add(channel) else remove(channel)
                                    }
                                }
                            )
                            Text(channel, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(allOptions.filter { it in selected }.joinToString(",")) }) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SoundFontCard(choices: List<SettingChoice>, onInstall: () -> Unit, onRemove: (String) -> Unit) {
    GroupCard {
        choices.forEachIndexed { index, choice ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.MusicNote, null)
                Spacer(Modifier.width(10.dp))
                Text(choice.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(choice.name) }) { Icon(Icons.Outlined.DeleteOutline, null) }
            }
            if (index != choices.lastIndex) GroupDivider()
        }
        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(7.dp))
            Text("Install SoundFont")
        }
    }
}

@Composable
private fun EditableValueCard(label: String, initial: String, onSave: (String) -> Unit) {
    GroupCard { EditableInlineValue(label, initial, onSave) }
}

@Composable
private fun EditableInlineValue(label: String, initial: String, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = { onSave(value) }, modifier = Modifier.align(Alignment.End)) { Text("Save") }
    }
}
