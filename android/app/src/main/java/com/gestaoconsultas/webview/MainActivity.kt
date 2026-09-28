package com.gestaoconsultas.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity\nimport androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val pickHtmlRequest = 5901
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var tts: TextToSpeech? = null\n    private lateinit var googleHomeBridge: GoogleHomeBridge
    private val importedHtml: File by lazy { File(filesDir, "home_edition.html") }
    private val ioExecutor = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val wsClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        googleHomeBridge = GoogleHomeBridge(this, lifecycleScope) { type, payload -> emitGoogleHome(type, payload) }\n\n        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val pt = Locale("pt", "PT")
                if (engine.isLanguageAvailable(pt) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.language = pt
                }
            }
        }

        if (importedHtml.exists() && importedHtml.length() > 0) {
            showWebApp()
        } else {
            showImporter()
        }
    }

    private fun showImporter() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(Color.rgb(13, 18, 24))
        }

        val title = TextView(this).apply {
            text = "Home Edition"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "V59 · correção de configuração\n\nSeleciona Home_Edition_V59_Android_Setup_Fix.html. A aplicação passa a servi-lo numa origem HTTPS interna do Android, permitindo Cofre/Web Crypto, e usa a camada nativa para comunicar com o Home Assistant sem depender de CORS."
            textSize = 15f
            setTextColor(Color.rgb(190, 200, 210))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(22))
        }

        val button = Button(this).apply {
            text = "Importar Home Edition V59"
            setOnClickListener { openHtmlPicker() }
        }

        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebApp() {
        webView = WebView(this)
        setContentView(webView)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/files/", WebViewAssetLoader.InternalStoragePathHandler(this, filesDir))
            .build()

        WebView.setWebContentsDebuggingEnabled(true)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowContentAccess = true
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(), "HomeNative")

        webView.loadUrl("https://appassets.androidplatform.net/files/home_edition.html")
    }

    private fun openHtmlPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/xhtml+xml", "text/plain"))
        }
        startActivityForResult(intent, pickHtmlRequest)
    }

    @Deprecated("Compatibility beta")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickHtmlRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(importedHtml, false).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Não foi possível abrir o ficheiro.")
            Toast.makeText(this, "Home Edition V59 importada.", Toast.LENGTH_SHORT).show()
            showWebApp()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao importar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        sockets.values.forEach { it.close(1000, "app destroyed") }
        sockets.clear()
        ioExecutor.shutdownNow()
        wsClient.dispatcher.executorService.shutdown()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("HomeNative")
            webView.destroy()
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun evaluate(script: String) {
        runOnUiThread {
            if (::webView.isInitialized) webView.evaluateJavascript(script, null)
        }
    }

    private fun emitGoogleHome(type: String, payload: String) {\n        evaluate(\n            \"window.HomeEditionGoogleHomeV60&&window.HomeEditionGoogleHomeV60._nativeEvent(\" +\n                JSONObject.quote(type) + \",\" + JSONObject.quote(payload) + \");\"\n        )\n    }\n\n    private fun emitHttp(id: String, payload: JSONObject) {
        evaluate(
            "window.HomeEditionNativeNetwork&&window.HomeEditionNativeNetwork._httpResult(" +
                JSONObject.quote(id) + "," + JSONObject.quote(payload.toString()) + ");"
        )
    }

    private fun emitWs(id: String, type: String, payload: String) {
        evaluate(
            "window.__HomeNativeWs&&window.__HomeNativeWs.event(" +
                JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + JSONObject.quote(payload) + ");"
        )
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "home-edition")
            }
        }

        @JavascriptInterface
        fun vibrate(milliseconds: Long) {
            val ms = milliseconds.coerceIn(10L, 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(ms)
                }
            }
        }

        @JavascriptInterface
        fun openAlarms() {
            runOnUiThread {
                try {
                    startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Aplicação de alarmes indisponível.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun pickHtml() {
            runOnUiThread { openHtmlPicker() }
        }

        @JavascriptInterface
        fun httpRequest(id: String, method: String, url: String, headersJson: String, body: String) {
            ioExecutor.execute {
                val result = JSONObject()
                try {
                    val parsed = URL(url)
                    if (parsed.protocol != "http" && parsed.protocol != "https") {
                        throw IllegalArgumentException("Apenas HTTP/HTTPS é suportado.")
                    }

                    val connection = parsed.openConnection() as HttpURLConnection
                    connection.requestMethod = method.uppercase(Locale.ROOT)
                    connection.connectTimeout = 12000
                    connection.readTimeout = 15000
                    connection.instanceFollowRedirects = true
                    connection.useCaches = false

                    val headers = if (headersJson.isBlank()) JSONObject() else JSONObject(headersJson)
                    val names = headers.keys()
                    while (names.hasNext()) {
                        val key = names.next()
                        connection.setRequestProperty(key, headers.optString(key))
                    }

                    if (body.isNotEmpty() && connection.requestMethod !in listOf("GET", "HEAD")) {
                        connection.doOutput = true
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }

                    val status = connection.responseCode
                    val stream = if (status >= 400) connection.errorStream else connection.inputStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val responseHeaders = JSONObject()
                    connection.headerFields.forEach { (key, values) ->
                        if (key != null && values != null) responseHeaders.put(key, values.joinToString(", "))
                    }

                    result.put("status", status)
                    result.put("statusText", connection.responseMessage ?: "")
                    result.put("body", responseBody)
                    result.put("headers", responseHeaders)
                    connection.disconnect()
                } catch (e: Exception) {
                    result.put("error", e.message ?: e.javaClass.simpleName)
                }
                emitHttp(id, result)
            }
        }

        @JavascriptInterface
        fun wsConnect(id: String, url: String) {
            try {
                if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                    emitWs(id, "error", "URL WebSocket inválido.")
                    emitWs(id, "close", JSONObject().put("code", 1006).put("reason", "invalid URL").toString())
                    return
                }

                val request = Request.Builder().url(url).build()
                val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets[id] = webSocket
                        emitWs(id, "open", "")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        emitWs(id, "message", text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        emitWs(id, "message", bytes.utf8())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        sockets.remove(id)
                        emitWs(id, "close", JSONObject().put("code", code).put("reason", reason).toString())
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        sockets.remove(id)
                        emitWs(id, "error", t.message ?: "WebSocket error")
                        emitWs(id, "close", JSONObject().put("code", 1006).put("reason", t.message ?: "failure").toString())
                    }
                })
                sockets[id] = socket
            } catch (e: Exception) {
                emitWs(id, "error", e.message ?: "WebSocket error")
                emitWs(id, "close", JSONObject().put("code", 1006).put("reason", e.message ?: "failure").toString())
            }
        }

        @JavascriptInterface
        fun wsSend(id: String, data: String) {
            val socket = sockets[id]
            if (socket == null || !socket.send(data)) {
                emitWs(id, "error", "WebSocket não está aberto.")
            }
        }

        @JavascriptInterface
        fun wsClose(id: String, code: Int, reason: String) {
            sockets.remove(id)?.close(code.coerceIn(1000, 4999), reason)
        }

        @JavascriptInterface
        fun reportJsError(message: String, stack: String) {
            Log.e("HomeEditionJS", "$message\n$stack")
        }

        @JavascriptInterface
        fun getRuntimeInfo(): String {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            return JSONObject()
                .put("native", true)
                .put("shell", "android-secure-webview")
                .put("secureOrigin", true)
                .put("nativeHttp", true)
                .put("nativeWebSocket", true)
                .put("versionName", pkg.versionName ?: "0.59.0")
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("androidRelease", Build.VERSION.RELEASE ?: "")
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("model", Build.MODEL ?: "")
                .toString()
        }
    }
}
