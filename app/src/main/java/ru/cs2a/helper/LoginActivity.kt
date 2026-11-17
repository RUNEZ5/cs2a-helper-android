package ru.cs2a.helper

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    private var accountSyncAttempted = false

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
        findViewById<Button>(R.id.logoutBrowserBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Выйти из аккаунта?")
                .setMessage("Будут удалены cookies и данные входа встроенного браузера. Привязка лицензии к текущему аккаунту CSGORun останется.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выйти") { _, _ -> logoutFromBrowser() }
                .show()
        }
        pollToken()
    }

    private fun logoutFromBrowser() {
        polling = false
        accountSyncAttempted = true
        webView.evaluateJavascript(
            "(function(){try{localStorage.clear();sessionStorage.clear();}catch(e){} return true;})();"
        ) {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                TokenStore.clearJwt()
                webView.stopLoading()
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                webView.loadUrl(ORIGIN)
                polling = true
                handler.postDelayed({ pollToken() }, 1000)
                AlertDialog.Builder(this)
                    .setTitle("Вы вышли из аккаунта")
                    .setMessage("Ключ уже привязан к одному аккаунту CSGORun. Выход не снимает эту привязку. Для дальнейших указаний свяжитесь с @pashkanka.")
                    .setPositiveButton("Понятно", null)
                    .show()
            }
        }
    }

    private fun jsResultToString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try { JSONTokener(raw).nextValue() as? String } catch (e: Exception) { null }
    }

    private fun pollToken() {
        webView.evaluateJavascript("(function(){try{return localStorage.getItem('auth-token')||'';}catch(e){return '';}})();") { raw ->
            val token = jsResultToString(raw)?.takeIf { it.isNotBlank() }
            if (token != null && token != TokenStore.jwt()) {
                accountSyncAttempted = false
                TokenStore.saveJwt(token)
                if (TokenStore.isApiTokenValid()) Toast.makeText(this, "Токен получен ✅", Toast.LENGTH_SHORT).show()
            }
            if (!accountSyncAttempted && TokenStore.isApiTokenValid()) {
                accountSyncAttempted = true
                lifecycleScope.launch {
                    val linked = LicenseManager.linkCsgorunAccount(this@LoginActivity)
                    if (!linked.valid) {
                        Toast.makeText(this@LoginActivity, linked.message, Toast.LENGTH_LONG).show()
                        if (linked.message.contains("заблокирован", ignoreCase = true)) TokenStore.saveSecret("licenseToken", "")
                    } else {
                        Toast.makeText(this@LoginActivity, linked.message.ifBlank { "Аккаунт CSGORun привязан к лицензии ✅" }, Toast.LENGTH_LONG).show()
                    }
                }
            }
            if (polling) handler.postDelayed({ pollToken() }, 1000)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun emit(type: String, json: String) {
            handler.post {
                when (type) {
                    "profile" -> {
                        try {
                            val profile = JSONObject(json)
                            val userId = profile.optString("userId")
                            val username = profile.optString("username")
                            if (TokenStore.saveCsgorunUsername(userId, username)) {
                                lifecycleScope.launch {
                                    val linked = LicenseManager.linkCsgorunAccount(this@LoginActivity)
                                    if (!linked.valid) Toast.makeText(this@LoginActivity, linked.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) { }
                    }
                    "jwt" -> {
                        try {
                            val token = JSONObject(json).optString("token")
                            if (token.isNotBlank()) {
                                val tokenChanged = token != TokenStore.jwt()
                                if (tokenChanged) accountSyncAttempted = false
                                TokenStore.saveJwt(token)
                                if (!accountSyncAttempted) {
                                    accountSyncAttempted = true
                                    lifecycleScope.launch {
                                        val linked = LicenseManager.linkCsgorunAccount(this@LoginActivity)
                                        if (!linked.valid) {
                                            Toast.makeText(this@LoginActivity, linked.message, Toast.LENGTH_LONG).show()
                                            if (linked.message.contains("заблокирован", ignoreCase = true)) TokenStore.saveSecret("licenseToken", "")
                                        } else {
                                            Toast.makeText(this@LoginActivity, linked.message.ifBlank { "Аккаунт CSGORun привязан к лицензии ✅" }, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
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
