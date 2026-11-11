package ru.cs2a.helper.spam

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import ru.cs2a.helper.App
import ru.cs2a.helper.Logs
import ru.cs2a.helper.MainActivity
import ru.cs2a.helper.ORIGIN
import ru.cs2a.helper.R
import ru.cs2a.helper.TokenStore
import java.util.concurrent.atomic.AtomicInteger

// Фоновая служба со скрытым WebView: открывает cs2a.run, внедряет cs2a_inject.js и повторяет
// тот же цикл отправки, что spamLoop() в background.js расширения. WebView нужен, потому что
// только живая страница сайта знает актуальный формат WebSocket/HTTP-запроса чата — его нельзя
// надёжно собрать заново на чистом Kotlin.
class SpamForegroundService : Service() {
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reqSeq = AtomicInteger(1)
    private val pending = HashMap<Int, (String) -> Unit>()

    private var running = false
    private var loopToken = 0
    private var fails = 0
    private var warns = 0
    private var sent = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TokenStore.init(this)
        startForeground(NOTIF_ID, buildNotification("Служба спама запущена"))
        when (intent?.action) {
            ACTION_STOP -> { stopSpamLoop("Остановлено вручную"); stopSelf(); return START_NOT_STICKY }
            else -> ensureWebView()
        }
        return START_STICKY
    }

    private fun ensureWebView() {
        if (webView != null) { startIfConfigured(); return }
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val js = assets.open("cs2a_inject.js").bufferedReader().use { it.readText() }
                view.evaluateJavascript(js, null)
                val tplJson = TokenStore.getString("spamTplJson")
                if (tplJson.isNotBlank()) view.evaluateJavascript("window.__cs2aImportTpl(${jsStr(tplJson)});", null)
                handler.postDelayed({ startIfConfigured() }, 800)
            }
        }
        wv.loadUrl(ORIGIN)
        webView = wv
    }

    private fun startIfConfigured() {
        val text = TokenStore.getString("spamText")
        if (text.isBlank()) return
        if (running) return
        startSpamLoop()
    }

    private fun startSpamLoop() {
        running = true
        loopToken++
        fails = 0; warns = 0; sent = 0
        val stopRe = TokenStore.getString("stopRe")
        callJs("window.__cs2aConfig(true, ${jsStr(stopRe)});")
        Logs.add(this, "spam", "Старт", "")
        step(loopToken)
    }

    fun stopSpamLoop(reason: String?) {
        if (!running && reason == null) return
        running = false
        loopToken++
        callJs("window.__cs2aConfig(false, '');")
        if (reason != null) Logs.add(this, "spam", reason, if (reason.contains("Остановл")) "" else "bad")
        updateNotification("Остановлено" + (reason?.let { ": $it" } ?: ""))
    }

    private fun step(myToken: Int) {
        if (!running || myToken != loopToken) return
        val text = TokenStore.getString("spamText")
        val delay = TokenStore.getInt("spamDelay", 1900).coerceAtLeast(200)
        if (text.isBlank()) { stopSpamLoop("Остановлено: пустое сообщение"); return }
        val reqId = reqSeq.incrementAndGet()
        pending[reqId] = pend@{ json ->
            if (myToken != loopToken || !running) return@pend
            try {
                val o = JSONObject(json)
                if (o.optBoolean("ok")) {
                    fails = 0; sent++
                    updateNotification("Работает… отправлено: $sent")
                } else {
                    fails++
                    val err = o.optString("error").ifBlank { "HTTP ${o.optInt("status")}" }
                    updateNotification("Ошибка ($fails/5): $err")
                }
            } catch (e: Exception) { fails++ }
            if (fails >= 5) {
                stopSpamLoop("Остановлено: не удалось отправить несколько раз подряд")
            } else {
                handler.postDelayed({ step(myToken) }, delay.toLong())
            }
        }
        val jwt = TokenStore.jwt() ?: ""
        callJs("window.__cs2aSend(${jsStr(text)}, ${jsStr(jwt)}, $reqId);")
    }

    private fun callJs(code: String) {
        handler.post { webView?.evaluateJavascript(code, null) }
    }

    private fun jsStr(s: String): String = JSONObject.quote(s)

    inner class Bridge {
        @JavascriptInterface
        fun emit(type: String, json: String) {
            handler.post {
                try {
                    val o = JSONObject(json)
                    when (type) {
                        "jwt" -> {
                            val token = o.optString("token")
                            if (token.isNotBlank()) TokenStore.saveJwt(token)
                        }
                        "stop-signal" -> stopSpamLoop("Сайт сообщил об остановке чата — спам остановлен")
                        "learned" -> {
                            TokenStore.setBool("spamHasTemplate", true)
                            Logs.add(this@SpamForegroundService, "spam", "Формат отправки записан", "ok")
                            startIfConfigured()
                        }
                        "warn" -> { /* копим в логе при желании */ }
                    }
                } catch (e: Exception) { }
            }
        }

        @JavascriptInterface
        fun resolve(reqId: Int, json: String) {
            handler.post { pending.remove(reqId)?.invoke(json) }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SPAM)
            .setContentTitle("CS2A Помощник — спам в чат")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.cs2a.helper.spam.START"
        const val ACTION_STOP = "ru.cs2a.helper.spam.STOP"
        const val NOTIF_ID = 42
    }
}
