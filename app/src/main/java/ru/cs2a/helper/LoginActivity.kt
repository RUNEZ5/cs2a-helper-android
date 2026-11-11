package ru.cs2a.helper

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener

// Один и тот же экран служит для входа (JWT подхватывается из localStorage) и для «обучения»
// формата отправки в чат: откройте чат и один раз напишите сообщение вручную — внедрённый
// скрипт (cs2a_inject.js) запомнит формат и мы сохраним его как JSON (TokenStore.spamTplJson).
// Фоновая служба спама при следующем запуске импортирует этот JSON в свой собственный WebView
// (window.__cs2aImportTpl) — учиться заново на каждом перезапуске не нужно.
class LoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var polling = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        TokenStore.init(this)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val js = assets.open("cs2a_inject.js").bufferedReader().use { it.readText() }
                view.evaluateJavascript(js, null)
            }
        }
        webView.loadUrl(ORIGIN)

        findViewById<Button>(R.id.doneBtn).setOnClickListener { finish() }
        pollToken()
    }

    private fun jsResultToString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
    }

    private fun pollToken() {
        webView.evaluateJavascript("(function(){try{return localStorage.getItem('auth-token')||'';}catch(e){return '';}})();") { raw ->
            val token = jsResultToString(raw)?.takeIf { it.isNotBlank() }
            if (token != null && token != TokenStore.jwt()) {
                TokenStore.saveJwt(token)
                if (TokenStore.isApiTokenValid()) Toast.makeText(this, "Токен получен ✅", Toast.LENGTH_SHORT).show()
            }
            if (polling) handler.postDelayed({ pollToken() }, 1000)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun emit(type: String, json: String) {
            handler.post {
                when (type) {
                    "jwt" -> {
                        try {
                            val token = JSONObject(json).optString("token")
                            if (token.isNotBlank()) TokenStore.saveJwt(token)
                        } catch (e: Exception) { }
                    }
                    "learned" -> {
                        webView.evaluateJavascript("window.__cs2aExportTpl();") { raw ->
                            val tplJson = jsResultToString(raw)
                            if (!tplJson.isNullOrBlank()) {
                                TokenStore.setString("spamTplJson", tplJson)
                                TokenStore.setBool("spamHasTemplate", true)
                                Toast.makeText(this@LoginActivity, "Формат отправки записан ✅", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun resolve(reqId: Int, json: String) { /* не используется на этом экране */ }
    }

    override fun onDestroy() {
        polling = false
        super.onDestroy()
    }
}
