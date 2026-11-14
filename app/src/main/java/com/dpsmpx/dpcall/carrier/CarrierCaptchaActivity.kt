package com.dpsmpx.dpcall.carrier

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri

/** VK guest login captcha only; no microphone, file access or JavaScript bridge. */
class CarrierCaptchaActivity : Activity() {
    private var web: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")
        if (url == null || url != CarrierTransport.status.value.captchaUrl || Uri.parse(url).host != "127.0.0.1") { finish(); return }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            loadUrl(url)
        }
        setContentView(web)
    }
    override fun onDestroy() { web?.destroy(); web = null; super.onDestroy() }
}
