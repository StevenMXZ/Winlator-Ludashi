package com.winlator.cmod.steam

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.cmod.container.Shortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SteamGameOptions(shortcut: Shortcut) {
    val id = shortcut.getExtra("steamAppId").toIntOrNull() ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(SteamSettings.mode(context, id)) }
    var exe by remember { mutableStateOf(shortcut.getExtra("steamExecutable")) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val exes by produceState(emptyList<String>(), id) {
        value = withContext(Dispatchers.IO) {
            val dir = SteamGames.directory(context, id)
            val suggested = SteamCatalog.executables(SteamGames.info(context, id), dir)
            val others = dir.walkTopDown().onEnter { it.canonicalFile.toPath().startsWith(dir.toPath()) }
                .filter { it.isFile && it.extension.equals("exe", true) && it.canonicalFile.toPath().startsWith(dir.toPath()) }
                .map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()
            (suggested + others).distinct()
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Steam launch", style = MaterialTheme.typography.titleMedium)
            Text("Choose a launch method and executable, then save before playing.", style = MaterialTheme.typography.bodySmall)
            listOf("RealSteam" to "SteamLudashi / Real Steam", "Goldberg" to "Goldberg (offline)", "Raw" to "Executable only").forEach { (value, title) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(mode == value, enabled = !busy && !SteamSession.gameActive, onClick = { mode = value }); Text(title)
                }
            }
            Box {
                OutlinedButton(enabled = !busy && !SteamSession.gameActive, onClick = { expanded = true }) { Text(exe.ifEmpty { "Choose executable" }) }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                    exes.forEach { path -> DropdownMenuItem(text = { Text(path) }, onClick = { exe = path; expanded = false }) }
                }
            }
            Button(enabled = exe.isNotEmpty() && !busy && !SteamSession.gameActive, onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        shortcut.putExtra("steamConfigured", "0"); shortcut.saveData()
                        SteamSettings.setMode(context, id, mode)
                        val file = SteamLaunch.createShortcut(context, shortcut.container, id, SteamGames.info(context, id), exe)
                        Shortcut(shortcut.container, file).apply { putExtra("steamExecutable", exe); putExtra("steamConfigured", "1"); saveData() }
                        // Keep the editor's in-memory model from overwriting the prepared target/options.
                        shortcut.putExtra("execArgs", Shortcut(shortcut.container, file).getExtra("execArgs")); shortcut.putExtra("steamExecutable", exe); shortcut.putExtra("steamConfigured", "1"); shortcut.putExtra("steamLaunchMode", mode)
                    } }
                    busy = false; message = result.exceptionOrNull()?.message ?: "Steam launch saved"
                    if (result.isSuccess) context.getSharedPreferences("steam-settings", 0).edit().putInt("steam-container-$id", shortcut.container.id).apply()
                    SteamGames.changed()
                }
            }) { Text(if (busy) "Preparing…" else "Save Steam launch") }
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
