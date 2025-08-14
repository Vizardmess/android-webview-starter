
package com.gestaoconsultas.webview

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startUrl = readStartUrl() ?: "file:///android_asset/www/index.html"
        val webView = WebView(this)
        setContentView(webView)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.loadUrl(startUrl)
    }
    private fun readStartUrl(): String? = try {
        val jsonStr = assets.open("config.json").use { it.readBytes().toString(Charset.forName("UTF-8")) }
        val obj = JSONObject(jsonStr); obj.optString("startUrl", null)
    } catch (e: Exception) { null }
}
