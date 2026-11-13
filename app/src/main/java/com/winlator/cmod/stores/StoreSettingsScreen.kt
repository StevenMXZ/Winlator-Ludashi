package com.winlator.cmod.stores

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import com.winlator.cmod.steam.*
import com.winlator.cmod.ui.uiStr

class StoreSettingsScreen : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LudashiStore.find(intent.getStringExtra("store")) ?: run { finish(); return }
        setContent { StoreTheme {
            Column(Modifier.fillMaxSize().storeBackground().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                    Text(store.title, style = MaterialTheme.typography.headlineMedium)
                }
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StoreSection { ListItem(headlineContent = { Text(uiStr(R.string.store_integration, store.title)) }, trailingContent = {
                        Switch(store.enabled(this@StoreSettingsScreen), { store.setEnabled(this@StoreSettingsScreen, it) })
                    }) }
                    if (store.enabled(this@StoreSettingsScreen)) StoreSection {
                        listOf(0 to R.string.steam_store_library, 1 to R.string.steam_ui_downloads).forEach { (tab, title) ->
                            ListItem(headlineContent = { Text(uiStr(title)) }, leadingContent = { Icon(if (tab == 0) Icons.Outlined.SportsEsports else Icons.Outlined.Download, null) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, modifier = Modifier.clickable {
                                    startActivity(Intent(this@StoreSettingsScreen, StoreActivity::class.java).putExtra("store", store.id).putExtra("tab", tab))
                                })
                        }
                    }
                }
            }
        } }
    }
}
