package com.winlator.cmod.steam

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoreSettingsActivity : ImmersiveSteamActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); supportActionBar?.hide()
        setContent { StoreTheme {
            var error by remember { mutableStateOf("") }
            var tier by remember { mutableIntStateOf(SteamDownloadSettings.tier(this)) }
            var speedMenu by remember { mutableStateOf(false) }
            var showFolder by remember { mutableStateOf(false) }
            var help by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            var folder by remember { mutableStateOf(SteamStorage.root(this).path) }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val path = com.winlator.cmod.core.FileUtils.getFilePathFromUri(this@StoreSettingsActivity, uri)
                        check(!path.isNullOrBlank()) { uiStr(R.string.steam_folder_unavailable) }
                        SteamStorage.setRoot(this@StoreSettingsActivity, java.io.File(path))
                    } }
                    error = result.exceptionOrNull()?.message.orEmpty(); folder = SteamStorage.root(this@StoreSettingsActivity).path
                }
            }
            fun speedLabel(index: Int) = uiStr(when (index) { 0 -> R.string.steam_ui_slow; 3 -> R.string.steam_ui_blazing; else -> R.string.steam_ui_fast })
            Column(Modifier.fillMaxSize().storeBackground().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                    Text(uiStr(R.string.gog_steam_settings_title), style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StoreSection {
                        Row(Modifier.fillMaxWidth().clickable(enabled = SteamSettings.enabled(this@StoreSettingsActivity)) { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java)) }.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_steam), null, Modifier.size(56.dp), tint = Color.White)
                            Column(Modifier.weight(1f)) {
                                Text("Steam", style = MaterialTheme.typography.titleLarge)
                                Text(uiStr(R.string.steam_ui_account_downloads), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    StoreSection {
                        ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_integration)) },
                            supportingContent = { Text(uiStr(R.string.steam_integration_short), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(Icons.Outlined.Storefront, null) }, trailingContent = {
                                Switch(SteamSettings.enabled(this@StoreSettingsActivity), { enabled ->
                                    error = runCatching { SteamSettings.setEnabled(this@StoreSettingsActivity, enabled) }.exceptionOrNull()?.message.orEmpty()
                                })
                            }, modifier = Modifier.clickable { help = uiStr(R.string.steam_ui_disabled_help) })
                    }
                    if (error.isNotEmpty()) Text(steamStatusText(error), color = MaterialTheme.colorScheme.error)
                    if (SteamSettings.enabled(this@StoreSettingsActivity)) {
                        StoreSection {
                            ListItem(headlineContent = { Text(uiStr(R.string.steam_store_library)) }, supportingContent = { Text(uiStr(R.string.steam_store_library_help), style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Outlined.SportsEsports, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.clickable { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java)) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_downloads)) }, supportingContent = { Text(uiStr(R.string.steam_store_downloads_help), style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Outlined.Download, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.clickable { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java).putExtra("steam_tab", 1)) })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ListItem(headlineContent = { Text(uiStr(R.string.steam_store_location)) }, supportingContent = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Outlined.FolderOpen, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.clickable { showFolder = true })
                        }
                        Text(uiStr(R.string.steam_store_features), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StoreSection {
                            listOf("achievement-notifications" to R.string.steam_achievement_notifications, "achievement-sync" to R.string.steam_achievement_sync, "social" to R.string.steam_friends).forEachIndexed { index, (key, label) ->
                                var checked by remember(key) { mutableStateOf(getSharedPreferences("steam-settings", 0).getBoolean(key, key == "achievement-notifications")) }
                                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                ListItem(headlineContent = { Text(uiStr(label), style = MaterialTheme.typography.bodyMedium) },
                                    leadingContent = { Icon(when (key) { "social" -> Icons.Outlined.PeopleOutline; "achievement-sync" -> Icons.Outlined.Sync; else -> Icons.Outlined.EmojiEvents }, null) },
                                    trailingContent = { Switch(checked, { value ->
                                        checked = value; getSharedPreferences("steam-settings", 0).edit().putBoolean(key, value).apply()
                                        if (key == "social") SteamSession.work { SteamSession.session?.setAutoPersonaOnline(value); SteamSession.session?.setPersonaState(if (value) 1 else 0); if (value) SteamSocial.appEvent(this@StoreSettingsActivity) }
                                        if (key == "achievement-sync" && value) SteamAchievements.syncPending(this@StoreSettingsActivity)
                                    }, enabled = !SteamSession.gameActive) }, modifier = Modifier.clickable { help = uiStr(R.string.steam_achievement_sync_help) })
                            }
                        }
                        StoreSection {
                            Box {
                                ListItem(headlineContent = { Text(uiStr(R.string.steam_ui_download_speed)) }, supportingContent = { Text(speedLabel(tier)) }, leadingContent = { Icon(Icons.Outlined.Speed, null) },
                                    trailingContent = { Icon(Icons.Outlined.ExpandMore, null) }, modifier = Modifier.clickable { speedMenu = true })
                                DropdownMenu(speedMenu, { speedMenu = false }) {
                                    SteamDownloadSettings.tiers.forEach { index -> DropdownMenuItem(text = { Text(speedLabel(index)) }, onClick = { tier = index; SteamDownloadSettings.setTier(this@StoreSettingsActivity, index); speedMenu = false }) }
                                }
                            }
                        }
                    }
                }
            }
            if (help.isNotBlank()) AlertDialog(onDismissRequest = { help = "" }, text = { Text(help) }, confirmButton = { TextButton(onClick = { help = "" }) { Text(uiStr(R.string.ui_close)) } })
            if (showFolder) AlertDialog(onDismissRequest = { showFolder = false }, title = { Text(uiStr(R.string.steam_store_location)) }, text = {
                Column { Text(folder, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(12.dp)); Text(uiStr(R.string.steam_folder_help), style = MaterialTheme.typography.bodySmall) }
            }, confirmButton = { TextButton(enabled = !SteamSession.downloading && !SteamSession.gameActive, onClick = { showFolder = false; picker.launch(null) }) { Text(uiStr(R.string.steam_choose_folder)) } },
                dismissButton = { TextButton(enabled = !SteamSession.downloading && !SteamSession.gameActive, onClick = {
                    error = runCatching { SteamStorage.setRoot(this@StoreSettingsActivity, null); folder = SteamStorage.root(this@StoreSettingsActivity).path }.exceptionOrNull()?.message.orEmpty()
                    showFolder = false
                }) { Text(uiStr(R.string.steam_default_folder)) } })
        } }
    }
}
