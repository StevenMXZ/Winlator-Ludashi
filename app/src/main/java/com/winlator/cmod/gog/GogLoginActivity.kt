package com.winlator.cmod.gog

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import android.widget.Toast
import com.winlator.cmod.steam.ImmersiveSteamActivity
import java.util.UUID

class GogLoginActivity : ImmersiveSteamActivity() {
    private lateinit var browser: WebView
    private lateinit var state: String
    private var completed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!GogSettings.enabled(this)) { finish(); return }
        state = savedInstanceState?.getString("oauth-state") ?: UUID.randomUUID().toString()
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val url = request.url.toString()
                    return try {
                        val code = GogApi.callback(url, state)
                        if (code != null) {
                            if (!completed) {
                                completed = true
                                setResult(Activity.RESULT_OK, Intent().putExtra("code", code)); finish()
                            }
                            true
                        } else !GogApi.trusted(url)
                    } catch (error: Exception) {
                        Toast.makeText(this@GogLoginActivity, error.message, Toast.LENGTH_LONG).show()
                        setResult(Activity.RESULT_CANCELED); finish(); true
                    }
                }
            }
        }
        setContentView(browser)
        if (savedInstanceState == null || browser.restoreState(savedInstanceState) == null) browser.loadUrl(GogApi.loginUrl(state))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("oauth-state", state); browser.saveState(outState)
    }
    @Deprecated("Legacy Android navigation")
    override fun onBackPressed() { if (browser.canGoBack()) browser.goBack() else super.onBackPressed() }
    override fun onDestroy() { if (::browser.isInitialized) { browser.stopLoading(); browser.destroy() }; super.onDestroy() }
}
