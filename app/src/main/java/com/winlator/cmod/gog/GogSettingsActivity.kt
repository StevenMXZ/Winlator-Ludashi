package com.winlator.cmod.gog

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.steam.ImmersiveSteamActivity
import com.winlator.cmod.steam.StoreTheme
import com.winlator.cmod.steam.StoreSection
import com.winlator.cmod.steam.storeBackground
import com.winlator.cmod.ui.uiStr

class GogSettingsActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StoreTheme {
            var error by remember { mutableStateOf("") }
            val enabled = GogSettings.enabled(this)
            Column(Modifier.fillMaxSize().storeBackground().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                    Text(uiStr(R.string.gog_settings_title), style = MaterialTheme.typography.headlineMedium)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StoreSection {
                        ListItem(headlineContent = { Text(uiStr(R.string.gog_integration)) }, supportingContent = { Text(uiStr(R.string.gog_store_help)) }, trailingContent = {
                            Switch(enabled, { value -> error = runCatching { GogSettings.setEnabled(this@GogSettingsActivity, value) }.exceptionOrNull()?.message.orEmpty() })
                        })
                    }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (enabled) StoreSection {
                        listOf(0 to R.string.steam_store_library, 1 to R.string.steam_ui_downloads).forEach { (tab, title) ->
                            ListItem(headlineContent = { Text(uiStr(title)) }, leadingContent = { Icon(if (tab == 0) Icons.Outlined.SportsEsports else Icons.Outlined.Download, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.clickable { startActivity(Intent(this@GogSettingsActivity, GogActivity::class.java).putExtra("gog_tab", tab)) })
                        }
                    }
                    if (enabled) com.winlator.cmod.stores.StoreDownloadOptions("gog", "GOG")
                }
            }
        } }
    }
}
