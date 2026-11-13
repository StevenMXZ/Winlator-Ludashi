package com.winlator.cmod.stores

import android.app.Activity
import android.os.Bundle
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
    private var verifying = false
    private var lastCookie = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LudashiStore.find(intent.getStringExtra("store")) ?: run { finish(); return }
        if (!store.enabled(this) || StoreSessions.paused) { finish(); return }
        StoreSessions.fresh(store)
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
                override fun onPageFinished(view: WebView, url: String) {
                    if (store != LudashiStore.ITCH || completed || verifying || !StoreNetwork.trustedItch(url)) return
                    val cookie = CookieManager.getInstance().getCookie("https://itch.io").orEmpty()
                    if (cookie == lastCookie || cookie.split(';').none { it.trim().startsWith("itchio=") }) return
                    lastCookie = cookie; verifying = true
                    lifecycleScope.launch {
                        val accepted = withContext(Dispatchers.IO) { runCatching { ItchConnector.capture(applicationContext, cookie) }.getOrDefault(false) }
                        verifying = false
                        if (accepted && !completed) { completed = true; setResult(Activity.RESULT_OK); finish() }
                    }
                }
            }
        }
        setContentView(browser)
        if (savedInstanceState == null || browser.restoreState(savedInstanceState) == null) browser.loadUrl(if (store == LudashiStore.AMAZON) login.url() else "https://itch.io/login")
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
