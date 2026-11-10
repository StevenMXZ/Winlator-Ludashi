package com.winlator.cmod.steam

import com.winlator.cmod.ui.uiStr

import com.winlator.cmod.R

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

class SteamActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        com.winlator.cmod.ui.applyAppFullscreen(this)
        if (!SteamSettings.enabled(this)) { startActivity(Intent(this, StoreSettingsActivity::class.java)); finish(); return }
        startForegroundService(Intent(this, SteamService::class.java))
        if (savedInstanceState != null && supportFragmentManager.findFragmentById(android.R.id.content) is SteamGameDetailFragment) return
        val selectedId = intent.getIntExtra("steam_app_id", 0)
        if (selectedId > 0 && isInstalled(selectedId)) showDetail(selectedId)
        else setContent { WinZTheme { SteamScreen() } }
    }
    @Composable private fun SteamScreen() {
        val state = observeSteam()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.signedIn || state.savedAccount) {
                SteamManagerScreen(state, intent.getIntExtra("steam_tab", 0), { finish() }, ::signOut, ::showDetail)
            } else {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiStr(R.string.steam_ui_back)) }
                        Icon(androidx.compose.ui.res.painterResource(com.winlator.cmod.R.drawable.ic_steam), null, Modifier.size(30.dp))
                        Text("Steam", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                    }
                    LoginForm(state)
                }
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
                Text(uiStr(R.string.steam_ui_login_title), style = MaterialTheme.typography.headlineMedium)
                Text(uiStr(R.string.steam_ui_login_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.guard.isNotEmpty()) {
                    Text(steamStatusText(state.guard), style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(code, { code = it }, singleLine = true, label = { Text(uiStr(R.string.steam_ui_guard_code)) }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { SteamSession.guard.submit(code); code = "" }, enabled = code.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(uiStr(R.string.steam_ui_confirm_code)) }
                } else {
                    OutlinedTextField(username, { username = it }, label = { Text(uiStr(R.string.steam_ui_account_name)) },
                        leadingIcon = { Icon(Icons.Outlined.Person, null) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text(uiStr(R.string.steam_ui_password)) },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, uiStr(R.string.steam_ui_show_password)) } },
                        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { SteamSession.login(username, password); password = ""; visible = false },
                        enabled = state.ready && !state.busy && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(uiStr(R.string.steam_ui_sign_in))
                    }
                }
                if (state.authError.isNotEmpty()) Text(steamStatusText(state.authError), color = MaterialTheme.colorScheme.error)
                Text(steamStatusText(state.status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.gameActive && !state.ready && !state.busy) TextButton(onClick = { SteamSession.reconnect() }) { Text(uiStr(R.string.steam_ui_reconnect)) }
                if (state.busy || state.guard.isNotEmpty()) TextButton(onClick = { SteamSession.cancelLogin() }) { Text(uiStr(R.string.steam_ui_cancel_login)) }
                Text(uiStr(R.string.steam_ui_password_help), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    private fun isInstalled(id: Int) = File(filesDir, "steam-library/$id.json").isFile
    private fun signOut() {
        if (SteamSession.downloading) { Toast.makeText(this, uiStr(R.string.steam_ui_pause_logout), Toast.LENGTH_SHORT).show(); return }
        SteamCredentials.clear(this)
        File(filesDir, "steam-library-cache.json").delete()
        stopService(Intent(this, SteamService::class.java)); finish()
    }
    private fun showDetail(id: Int) {
        supportFragmentManager.beginTransaction().replace(android.R.id.content, SteamGameDetailFragment.newInstance(id)).commit()
    }
}
