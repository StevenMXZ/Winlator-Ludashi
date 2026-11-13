package com.winlator.cmod.stores

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.uiStr
import com.winlator.cmod.ui.theme.WinZTheme
import androidx.lifecycle.lifecycleScope
import com.winlator.cmod.steam.ImmersiveSteamActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoreLoginActivity : ImmersiveSteamActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LudashiStore.find(intent.getStringExtra("store")) ?: run { finish(); return }
        if (!store.enabled(this) || StoreSessions.paused) { finish(); return }
        StoreSessions.fresh(store)
        setContent { WinZTheme { ApiSignIn() } }
    }
    @Composable private fun ApiSignIn() {
        var token by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(uiStr(R.string.store_sign_in, "itch.io"), style = MaterialTheme.typography.headlineMedium)
            Text(uiStr(R.string.store_itch_key_help))
            OutlinedButton(onClick = { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ItchApi.KEYS))) }) { Text(uiStr(R.string.store_itch_open_keys)) }
            OutlinedTextField(token, { token = it }, enabled = !busy, label = { Text(uiStr(R.string.store_itch_key)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(enabled = token.isNotBlank() && !busy, onClick = {
                busy = true; error = ""
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { ItchApi.signIn(applicationContext, token) } }
                    busy = false
                    result.onSuccess { token = ""; setResult(Activity.RESULT_OK); finish() }.onFailure { error = it.message.orEmpty() }
                }
            }) { Text(uiStr(R.string.store_sign_in, "itch.io")) }
            TextButton(enabled = !busy, onClick = { finish() }) { Text(uiStr(R.string.cancel)) }
        }
    }
}
