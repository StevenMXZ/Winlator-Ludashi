package com.winlator.cmod.steam

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoreSettingsActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); supportActionBar?.hide()
        setContent { WinZTheme {
            var error by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            var tier by remember { mutableIntStateOf(SteamDownloadSettings.tier(this)) }
            val scope = rememberCoroutineScope()
            Scaffold(topBar = { TopAppBar(title = { Text("Steam / Stores") }, navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedCard {
                        ListItem(headlineContent = { Text("Steam integration") }, supportingContent = { Text("Library, account and downloads") },
                            leadingContent = { Icon(Icons.Outlined.Storefront, null) }, trailingContent = {
                                Switch(SteamSettings.enabled(this@StoreSettingsActivity), { enabled ->
                                    error = runCatching { SteamSettings.setEnabled(this@StoreSettingsActivity, enabled) }.exceptionOrNull()?.message.orEmpty()
                                })
                            })
                        Text("Turning off hides Steam and stops its service. Games and the saved account are kept.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (SteamSettings.enabled(this@StoreSettingsActivity)) {
                        OutlinedCard {
                            TextButton(onClick = { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java)) }) {
                                ListItem(headlineContent = { Text("Manage Steam games") }, leadingContent = { Icon(Icons.Outlined.SportsEsports, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
                            }
                            HorizontalDivider()
                            TextButton(onClick = { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java).putExtra("steam_tab", 1)) }) {
                                ListItem(headlineContent = { Text("Manage downloads") }, leadingContent = { Icon(Icons.Outlined.Download, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
                            }
                        }
                        Text("DOWNLOAD SPEED", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedCard {
                            SteamDownloadSettings.tiers.forEachIndexed { index, title ->
                                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(listOf("Lower network and CPU usage", "Balanced", "Adaptive • recommended", "Maximum concurrency • higher CPU and memory usage")[index]) }, leadingContent = {
                                    RadioButton(tier == index, onClick = { tier = index; SteamDownloadSettings.setTier(this@StoreSettingsActivity, index) })
                                })
                            }
                            Text("Applies to the next download. Actual speed depends on your network and storage.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Text("OPTIONAL COMPONENTS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedCard {
                            ListItem(headlineContent = { Text("Goldberg") }, supportingContent = { Text("Offline Steam API replacement for compatible games") }, leadingContent = { Icon(Icons.Outlined.Extension, null) })
                            TextButton(enabled = !busy, modifier = Modifier.padding(horizontal = 12.dp), onClick = {
                                busy = true; status = "Downloading and verifying…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { SteamGoldberg.ensureInstalled(this@StoreSettingsActivity) } }
                                    busy = false; status = result.exceptionOrNull()?.message ?: "Installed"
                                }
                            }) { Text(if (busy) "Installing…" else "Install component") }
                            if (status.isNotEmpty()) Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } }
    }
}
