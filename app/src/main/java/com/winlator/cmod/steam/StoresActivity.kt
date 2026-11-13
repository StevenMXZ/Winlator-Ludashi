package com.winlator.cmod.steam

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.stores.*
import com.winlator.cmod.gog.GogActivity
import com.winlator.cmod.gog.GogSettings
import com.winlator.cmod.gog.GogSettingsActivity
import com.winlator.cmod.ui.uiStr

class StoresActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StoreTheme {
            var error by remember { mutableStateOf("") }
            Column(Modifier.fillMaxSize().storeBackground().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                    Text(uiStr(R.string.steam_ui_stores), style = MaterialTheme.typography.headlineMedium)
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    LudashiStore.entries.forEach { store ->
                        val enabled = store.enabled(this@StoresActivity)
                        StoreSection {
                            Row(Modifier.fillMaxWidth().clickable {
                                startActivity(Intent(this@StoresActivity, if (enabled) StoreActivity::class.java else StoreSettingsScreen::class.java).putExtra("store", store.id))
                            }.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(painterResource(store.icon), null, Modifier.size(52.dp), tint = Color.Unspecified)
                                Column(Modifier.weight(1f)) {
                                    Text(store.title, style = MaterialTheme.typography.titleLarge)
                                    Text(uiStr(if (enabled) R.string.steam_ui_account_downloads else R.string.gog_integration_disabled), style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Outlined.ChevronRight, null)
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(uiStr(R.string.steam_ui_integration), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(enabled, { store.setEnabled(this@StoresActivity, it) })
                                IconButton(onClick = { startActivity(Intent(this@StoresActivity, StoreSettingsScreen::class.java).putExtra("store", store.id)) }) { Icon(Icons.Outlined.Settings, uiStr(R.string.gog_store_settings)) }
                            }
                        }
                    }
                    listOf(false, true).forEach { gog ->
                        val enabled = if (gog) GogSettings.enabled(this@StoresActivity) else SteamSettings.enabled(this@StoresActivity)
                        val settings = if (gog) GogSettingsActivity::class.java else StoreSettingsActivity::class.java
                        StoreSection {
                            Row(Modifier.fillMaxWidth().clickable {
                                startActivity(Intent(this@StoresActivity, if (!enabled) settings else if (gog) GogActivity::class.java else SteamActivity::class.java))
                            }.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Icon(painterResource(if (gog) R.drawable.ic_gog else R.drawable.ic_steam), null, Modifier.size(52.dp), tint = if (gog) Color.Unspecified else Color.White)
                                Column(Modifier.weight(1f)) {
                                    Text(if (gog) "GOG" else "Steam", style = MaterialTheme.typography.titleLarge)
                                    Text(uiStr(if (enabled) R.string.steam_ui_account_downloads else R.string.gog_integration_disabled), style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Outlined.ChevronRight, null)
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(uiStr(R.string.steam_ui_integration), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(enabled, { value -> error = runCatching {
                                    if (gog) GogSettings.setEnabled(this@StoresActivity, value) else SteamSettings.setEnabled(this@StoresActivity, value)
                                }.exceptionOrNull()?.message.orEmpty() })
                                IconButton(onClick = { startActivity(Intent(this@StoresActivity, settings)) }) { Icon(Icons.Outlined.Settings, uiStr(R.string.gog_store_settings)) }
                            }
                        }
                    }
                }
            }
        } }
    }
}
