package com.gestaoconsultas.webview

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val pickHtmlRequest = 5801
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private val importedHtml: File by lazy { File(filesDir, "home_edition.html") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val pt = Locale("pt", "PT")
                if (engine.isLanguageAvailable(pt) >= TextToSpeech.LANG_AVAILABLE) engine.language = pt
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
            text = "V58 · primeira APK\n\nSeleciona o ficheiro Home_Edition_V58_First_Installable_APK.html que descarregaste do ChatGPT. A app fará uma cópia privada e abrirá esse conteúdo automaticamente nos próximos arranques."
            textSize = 15f
            setTextColor(Color.rgb(190, 200, 210))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(22))
        }
        val button = Button(this).apply {
            text = "Importar Home Edition"
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

        WebView.setWebContentsDebuggingEnabled(true)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(), "HomeNative")
        webView.loadUrl(Uri.fromFile(importedHtml).toString())
    }

    private fun openHtmlPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/html"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/xhtml+xml", "text/plain"))
        }
        startActivityForResult(intent, pickHtmlRequest)
    }

    @Deprecated("Deprecated in Android SDK but retained for this compatibility beta")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickHtmlRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(importedHtml, false).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Não foi possível abrir o ficheiro.")
            Toast.makeText(this, "Home Edition importada.", Toast.LENGTH_SHORT).show()
            showWebApp()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao importar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("HomeNative")
            webView.destroy()
        }
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        fun getRuntimeInfo(): String {
            val pkg = packageManager.getPackageInfo(packageName, 0)
            return JSONObject()
                .put("native", true)
                .put("shell", "android-webview")
                .put("versionName", pkg.versionName ?: "0.58.0")
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("androidRelease", Build.VERSION.RELEASE ?: "")
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("model", Build.MODEL ?: "")
                .toString()
        }
    }
}
