package com.winlator.cmod.steam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.container.Container
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-play setup stays in the Play flow; Configure remains optional tuning. */
@Composable fun SteamPlaySetup(id: Int, play: Boolean, done: (Shortcut) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var containers by remember { mutableStateOf<List<Container>>(emptyList()) }
    var selected by remember { mutableStateOf<Container?>(null) }
    var executables by remember { mutableStateOf<List<String>>(emptyList()) }
    var exe by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var containerMenu by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val result = withContext(Dispatchers.IO) { runCatching {
            val all = ContainerManager(context).containers
            val shortcut = SteamGames.shortcut(context, id)
            val directory = SteamGames.directory(context, id)
            val suggested = SteamCatalog.executables(SteamGames.info(context, id), directory)
            val files = directory.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile && it.extension.equals("exe", true) && it.canonicalFile.toPath().startsWith(directory.toPath()) }
                .map { it.relativeTo(directory).invariantSeparatorsPath }.sorted().toList()
            Triple(all, shortcut, (suggested + files).distinct())
        } }
        result.onSuccess { (all, old, files) -> containers = all; selected = all.firstOrNull { it.id == old?.container?.id } ?: all.firstOrNull(); executables = files; exe = old?.getExtra("steamExecutable").orEmpty() }
            .onFailure { error = it.message.orEmpty() }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(uiStr(R.string.steam_play_setup)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(enabled = !busy, onClick = { containerMenu = true }) { Text(selected?.name ?: uiStr(R.string.steam_ui_create_container)) }
                DropdownMenu(containerMenu, { containerMenu = false }) { containers.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { selected = c; containerMenu = false }) } }
            }
            Box {
                OutlinedButton(enabled = !busy, onClick = { menu = true }) { Text(exe.ifBlank { uiStr(R.string.steam_ui_choose_exe) }, maxLines = 2) }
                DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 260.dp)) { executables.forEach { path -> DropdownMenuItem(text = { Text(path) }, onClick = { exe = path; menu = false }) } }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = selected != null && exe.isNotBlank() && !busy, onClick = {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                check(SteamSettings.enabled(context)) { uiStr(R.string.steam_ui_enable_signin) }
                if (SteamSettings.mode(context, id) == "RealSteam") check(SteamCredentials.load(context) != null) { uiStr(R.string.steam_ui_enable_signin) }
                val container = selected!!
                val existing = container.desktopDir.listFiles().orEmpty().any { it.extension == "desktop" && Shortcut(container, it).getExtra("steamAppId") == id.toString() }
                if (!existing) SteamGames.draft(context, id, container)
                val file = SteamLaunch.createShortcut(context, container, id, SteamGames.info(context, id), exe)
                Shortcut(container, file).apply { putExtra("steamExecutable", exe); putExtra("steamConfigured", "1"); saveData() }
            } }
            busy = false
            result.onSuccess { shortcut -> context.getSharedPreferences("steam-settings", 0).edit().putInt("steam-container-$id", shortcut.container.id).apply(); SteamGames.changed(); done(shortcut) }
                .onFailure { error = it.message.orEmpty() }
        }
    }) { Text(if (busy) uiStr(R.string.steam_ui_preparing) else if (play) uiStr(R.string.ui_play) else uiStr(R.string.steam_ui_save_launch)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(uiStr(R.string.steam_ui_cancel)) } })
}
