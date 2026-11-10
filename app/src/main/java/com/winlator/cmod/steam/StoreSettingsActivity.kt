package com.winlator.cmod.steam

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.cmod.ui.theme.WinZTheme

class StoreSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContent { WinZTheme {
            var error by remember { mutableStateOf("") }
            var componentBusy by remember { mutableStateOf(false) }
            var componentStatus by remember { mutableStateOf("") }
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { finish() }) { Text("Back") }
                    Text("Steam / Stores", style = MaterialTheme.typography.headlineMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable Steam", style = MaterialTheme.typography.titleMedium)
                            Text("Turn off to stop the Steam service and hide its library, account button and automatic connections.")
                        }
                        Switch(SteamSettings.enabled(this@StoreSettingsActivity), { value ->
                            error = runCatching { SteamSettings.setEnabled(this@StoreSettingsActivity, value) }.exceptionOrNull()?.message.orEmpty()
                        })
                    }
                    Text("Installed files and the saved account are kept. Re-enable Steam here when needed.")
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (SteamSettings.enabled(this@StoreSettingsActivity)) {
                        Button(onClick = { startActivity(Intent(this@StoreSettingsActivity, SteamActivity::class.java)) }) { Text("Steam account and downloads") }
                        Text("Launch methods: SteamLudashi / Real Steam, Goldberg (offline), or Raw executable. Choose per game.")
                        Button(enabled = !componentBusy, onClick = {
                            componentBusy = true; componentStatus = "Downloading and verifying Goldberg…"
                            SteamSession.work {
                                runCatching { SteamGoldberg.ensureInstalled(this@StoreSettingsActivity) }
                                    .onSuccess { runOnUiThread { componentBusy = false; componentStatus = "Goldberg installed" } }
                                    .onFailure { failure -> runOnUiThread { componentBusy = false; componentStatus = failure.message ?: "Goldberg installation failed" } }
                            }
                        }) { Text("Install Goldberg component") }
                        Text(componentStatus, style = MaterialTheme.typography.bodySmall)
                        val state = observeSteam()
                        Text(state.status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }
    }
}
