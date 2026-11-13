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
import android.webkit.*
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.winlator.cmod.steam.ImmersiveSteamActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoreLoginActivity : ImmersiveSteamActivity() {
    private lateinit var browser: WebView
    private var completed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LudashiStore.find(intent.getStringExtra("store")) ?: run { finish(); return }
        if (!store.enabled(this) || StoreSessions.paused) { finish(); return }
        StoreSessions.fresh(store)
        if (store == LudashiStore.ITCH) {
            setContent { WinZTheme { ApiSignIn() } }
            return
        }
        val login = if (savedInstanceState != null && savedInstanceState.containsKey("serial")) AmazonConnector.Login(savedInstanceState.getString("serial")!!, savedInstanceState.getString("verifier")!!) else AmazonConnector.login()
        amazonLogin = login
        fun received(url: String): Boolean {
            if (completed) return true
            if (store == LudashiStore.AMAZON) {
                val code = AmazonConnector.callback(url) ?: return false
                completed = true
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { AmazonConnector.register(applicationContext, login, code) } }
                    result.onSuccess { setResult(Activity.RESULT_OK); finish() }.onFailure {
                        Toast.makeText(this@StoreLoginActivity, it.message, Toast.LENGTH_LONG).show(); setResult(Activity.RESULT_CANCELED); finish()
                    }
                }
                return true
            }
            return false
        }
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    if (!request.isForMainFrame) false else received(request.url.toString()) || request.url.scheme != "https"
                override fun onPageStarted(view: WebView, url: String, icon: android.graphics.Bitmap?) { if (received(url)) view.stopLoading() }

            }
        }
        setContentView(browser)
        if (savedInstanceState == null || browser.restoreState(savedInstanceState) == null) browser.loadUrl(login.url())
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
    private var amazonLogin: AmazonConnector.Login? = null
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        amazonLogin?.let { outState.putString("serial", it.serial); outState.putString("verifier", it.verifier) }
        if (::browser.isInitialized) browser.saveState(outState)
    }
    @Deprecated("Legacy Android navigation")
    override fun onBackPressed() { if (::browser.isInitialized && browser.canGoBack()) browser.goBack() else super.onBackPressed() }
    override fun onDestroy() { if (::browser.isInitialized) { browser.stopLoading(); browser.destroy() }; super.onDestroy() }
}
