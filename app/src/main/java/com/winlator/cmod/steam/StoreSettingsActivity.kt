package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

import com.winlator.cmod.R

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

class StoreSettingsActivity : ImmersiveSteamActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); supportActionBar?.hide()
        setContent { WinZTheme {
            var error by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            var tier by remember { mutableIntStateOf(SteamDownloadSettings.tier(this)) }
            val scope = rememberCoroutineScope()
            Scaffold(modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)), contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0), title = { Text(uiStr(R.string.steam_ui_stores)) }, navigationIcon = {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
            }) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedCard {
                        ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_integration)) }, supportingContent = { Text(uiStr(R.string.steam_ui_account_downloads)) },
                            leadingContent = { Icon(Icons.Outlined.Storefront, null) }, trailingContent = {
                                Switch(SteamSettings.enabled(this@StoreSettingsActivity), { enabled ->
                                    error = runCatching { SteamSettings.setEnabled(this@StoreSettingsActivity, enabled) }.exceptionOrNull()?.message.orEmpty()
                                })
                            })
                        Text(uiStr(R.string.steam_ui_disabled_help), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    if (error.isNotEmpty()) Text(steamStatusText(error), color = MaterialTheme.colorScheme.error)
                    if (SteamSettings.enabled(this@StoreSettingsActivity)) {
                        OutlinedCard {
                            TextButton(onClick = { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java)) }) {
                                ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_manage_games)) }, leadingContent = { Icon(Icons.Outlined.SportsEsports, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
                            }
                            HorizontalDivider()
                            TextButton(onClick = { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java).putExtra("steam_tab", 1)) }) {
                                ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_manage_downloads)) }, leadingContent = { Icon(Icons.Outlined.Download, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) })
                            }
                        }
                        Text(uiStr(R.string.steam_ui_download_speed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedCard {
                            SteamDownloadSettings.tiers.forEach { index ->
                                val title = when (index) { 0 -> uiStr(R.string.steam_ui_slow); 3 -> uiStr(R.string.steam_ui_blazing); else -> uiStr(R.string.steam_ui_fast) }
                                ListItem(headlineContent = { Text(title) }, supportingContent = { Text(when (index) { 0 -> uiStr(R.string.steam_ui_slow_help); 3 -> uiStr(R.string.steam_ui_blazing_help); else -> uiStr(R.string.steam_ui_fast_help) }) }, leadingContent = {
                                    RadioButton(tier == index, onClick = { tier = index; SteamDownloadSettings.setTier(this@StoreSettingsActivity, index) })
                                })
                            }
                            Text(uiStr(R.string.steam_ui_speed_help), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(uiStr(R.string.steam_ui_components), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedCard {
                            ListItem(headlineContent = { Text("Goldberg") }, supportingContent = { Text(uiStr(R.string.steam_ui_offline_help)) }, leadingContent = { Icon(Icons.Outlined.Extension, null) })
                            TextButton(enabled = !busy, modifier = Modifier.padding(horizontal = 12.dp), onClick = {
                                busy = true; status = uiStr(R.string.steam_ui_verifying)
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { SteamGoldberg.ensureInstalled(this@StoreSettingsActivity) } }
                                    busy = false; status = result.exceptionOrNull()?.message ?: uiStr(R.string.steam_ui_component_installed)
                                }
                            }) { Text(if (busy) uiStr(R.string.steam_ui_installing) else uiStr(R.string.steam_ui_install_component)) }
                            if (status.isNotEmpty()) Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } }
    }
}
