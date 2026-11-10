package com.winlator.cmod.steam

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.bumptech.glide.Glide
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.ui.theme.WinZTheme
import org.json.JSONObject
import java.io.File

class SteamActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        if (!SteamSettings.enabled(this)) { startActivity(Intent(this, StoreSettingsActivity::class.java)); finish(); return }
        startForegroundService(Intent(this, SteamService::class.java))
        if (savedInstanceState != null && supportFragmentManager.findFragmentById(android.R.id.content) is SteamGameDetailFragment) return
        val selectedId = intent.getIntExtra("steam_app_id", 0)
        if (selectedId > 0 && isInstalled(selectedId)) showDetail(selectedId)
        else setContent { WinZTheme { SteamScreen() } }
    }
    @Composable private fun SteamScreen() {
        val state = observeSteam()
        var tab by rememberSaveable { mutableStateOf(intent.getIntExtra("steam_tab", 0).coerceIn(0, 1)) }
        var query by rememberSaveable { mutableStateOf("") }
        var selected by rememberSaveable { mutableStateOf(intent.getIntExtra("steam_app_id", 0)) }
        val apps = SteamLibrary.items(this, state.library)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library") }
                    Column(Modifier.weight(1f)) {
                        Text("Steam", style = MaterialTheme.typography.titleLarge)
                        Text(if (state.signedIn || state.savedAccount) "${apps.size} games in your library" else "Connect your account", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.signedIn || state.savedAccount) TextButton(onClick = { signOut() }) { Text("Sign out") }
                }
                if (!state.signedIn && !state.savedAccount) LoginForm(state)
                else {
                    if (!state.signedIn && state.savedAccount) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (state.gameActive) Text("Exit the game to restore the app session", style = MaterialTheme.typography.labelSmall)
                            else if (state.restoring) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else TextButton(onClick = { SteamSession.reconnect() }) { Text("Reconnect") }
                        }
                    }
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Library") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Downloads") })
                    }
                    if (!state.downloading) Text(state.status, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                    if (tab == 1) {
                        SteamDownloadPanel(state)
                        if (!state.downloading) Column(Modifier.padding(24.dp)) {
                            Icon(Icons.Outlined.DownloadDone, null)
                            Text(if (state.id > 0) state.name else "No active downloads", style = MaterialTheme.typography.titleMedium)
                            Text(state.status, style = MaterialTheme.typography.bodyMedium)
                            if (state.id > 0) Button(onClick = {
                                if (isInstalled(state.id)) showDetail(state.id) else SteamSession.install(state.id)
                            }) { Text(if (isInstalled(state.id)) "View game" else "Resume download") }
                        }
                    } else {
                        SteamDownloadPanel(state)
                        OutlinedTextField(query, { query = it }, label = { Text("Search your Steam games") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp))
                        if (state.libraryLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            if (apps.isEmpty()) Text(state.status, Modifier.padding(24.dp))
                        } else if (state.libraryError.isNotEmpty()) {
                            Column(Modifier.padding(24.dp)) {
                                Text(state.libraryError)
                                Button(onClick = { SteamSession.retryLibrary() }) { Text("Try again") }
                            }
                        } else if (apps.isEmpty()) {
                            Column(Modifier.padding(24.dp)) {
                                Text("No Steam games found in this account.")
                                TextButton(onClick = { SteamSession.retryLibrary() }) { Text("Refresh library") }
                            }
                        }
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(apps.filter { it.name.contains(query, true) }.sortedBy { it.name }, key = { it.id }) { app ->
                                val id = SteamLibrary.appId(app.shortcutPath)!!
                                Card(onClick = { if (isInstalled(id)) showDetail(id) else selected = id }, shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                                            update = { Glide.with(it).load("https://shared.steamstatic.com/store_item_assets/steam/apps/$id/library_600x900_2x.jpg").into(it) },
                                            modifier = Modifier.size(52.dp, 78.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text(app.name, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                                            Text(if (isInstalled(id)) "Installed" else "Ready to download", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Icon(if (isInstalled(id)) Icons.Outlined.ChevronRight else Icons.Outlined.Download, null)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if ((state.signedIn || state.savedAccount) && selected > 0) {
                val id = selected
                val app = apps.find { SteamLibrary.appId(it.shortcutPath) == id }
                AlertDialog(onDismissRequest = { selected = 0 }, title = { Text(app?.name ?: "Steam $id") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Download this game from your Steam account.")
                        if (state.downloading && state.id == id) SteamDownloadPanel(state)
                        Text(state.status, style = MaterialTheme.typography.bodySmall)
                    } },
                    confirmButton = {
                        Button(enabled = !state.gameActive && !state.downloading && (state.signedIn || isInstalled(id)), onClick = {
                            if (isInstalled(id)) showDetail(id) else { SteamSession.install(id); tab = 1 }
                            selected = 0
                        }) { Text(if (isInstalled(id)) "View game" else "Download") }
                    }, dismissButton = { TextButton(onClick = { selected = 0 }) { Text("Close") } })
            }
        }
    }
    @Composable private fun LoginForm(state: SteamUiSnapshot) {
        var username by rememberSaveable { mutableStateOf("") }
        // Passwords and Guard codes are kept only in memory, never saved in instance state.
        var password by remember { mutableStateOf("") }
        var visible by remember { mutableStateOf(false) }
        var code by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .10f), Color.Transparent))), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.SportsEsports, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Text("Sign in to Steam", style = MaterialTheme.typography.headlineMedium)
                Text("Your games, downloads and play sessions in one library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.guard.isNotEmpty()) {
                    Text(state.guard, style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(code, { code = it }, singleLine = true, label = { Text("Steam Guard code") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { SteamSession.guard.submit(code); code = "" }, enabled = code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Confirm code") }
                } else {
                    OutlinedTextField(username, { username = it }, label = { Text("Steam account name") },
                        leadingIcon = { Icon(Icons.Outlined.Person, null) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show or hide password") } },
                        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { SteamSession.login(username, password); password = ""; visible = false },
                        enabled = state.ready && !state.busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Sign in")
                    }
                }
                if (state.authError.isNotEmpty()) Text(state.authError, color = MaterialTheme.colorScheme.error)
                Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.gameActive && !state.ready && !state.busy) TextButton(onClick = { SteamSession.reconnect() }) { Text("Reconnect to Steam") }
                if (state.busy || state.guard.isNotEmpty()) TextButton(onClick = { SteamSession.cancelLogin() }) { Text("Cancel sign-in") }
                Text("Your password is not saved. Steam Guard may ask for an email code or approval in the Steam mobile app.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    private fun isInstalled(id: Int) = File(filesDir, "steam-library/$id.json").isFile
    private fun signOut() {
        if (SteamSession.downloading) { Toast.makeText(this, "Pause the download before signing out", Toast.LENGTH_SHORT).show(); return }
        SteamCredentials.clear(this)
        File(filesDir, "steam-library-cache.json").delete()
        stopService(Intent(this, SteamService::class.java)); finish()
    }
    private fun showDetail(id: Int) {
        supportFragmentManager.beginTransaction().replace(android.R.id.content, SteamGameDetailFragment.newInstance(id)).commit()
    }
}
