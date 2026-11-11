package ru.cs2a.helper.crash

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.cs2a.helper.Api
import ru.cs2a.helper.App
import ru.cs2a.helper.MainActivity
import ru.cs2a.helper.ORIGIN
import ru.cs2a.helper.R
import ru.cs2a.helper.TokenStore

class CrashForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var webView: WebView? = null
    private var running = false
    private var amount = 0.0
    private var autoCashout = 1.25
    private var roundsLimit = 1
    private var placed = 0
    private var lastRoundKey = ""
    private var lastStartAt = 0L
    private var requestInFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TokenStore.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                stopRunner("Остановлено вручную")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
                autoCashout = intent.getDoubleExtra(EXTRA_AUTO, 1.25)
                roundsLimit = intent.getIntExtra(EXTRA_ROUNDS, 1).coerceIn(1, 500)
                if (amount <= 0.0 || autoCashout <= 1.0 || !TokenStore.isApiTokenValid()) {
                    CrashState.set(false, "Проверьте ставку, авто-вывод и авторизацию")
                    stopSelf()
                    return START_NOT_STICKY
                }
                placed = 0
                lastRoundKey = ""
                lastStartAt = 0L
                requestInFlight = false
                running = true
                startForeground(NOTIF_ID, notification("Ожидание начала раунда…"))
                CrashState.set(true, "Ожидание раунда")
                ensureWebView()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureWebView() {
        if (webView != null) return
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (!running) return
                val js = assets.open("cs2a_inject.js").bufferedReader().use { it.readText() }
                view.evaluateJavascript(js, null)
                CrashState.set(true, "Подключено · ожидание раунда")
                updateNotification("Подключено · ожидание раунда")
            }
        }
        wv.loadUrl(ORIGIN)
        webView = wv
    }

    private fun onCrashEvent(event: String, payload: JSONObject) {
        if (!running || event != "start" || requestInFlight || placed >= roundsLimit) return
        if (payload.has("status") && payload.optInt("status", -1) != 1) return

        val now = SystemClock.elapsedRealtime()
        val roundKey = firstString(payload, arrayOf("roundId", "gameId", "crashId", "id"))
        if (roundKey.isNotBlank()) {
            if (roundKey == lastRoundKey) return
            lastRoundKey = roundKey
        } else if (now - lastStartAt < 1800L) {
            return
        }
        lastStartAt = now
        requestInFlight = true
        CrashState.set(true, "Раунд замечен · отправляю ставку ${placed + 1}/$roundsLimit")
        updateNotification("Раунд замечен · отправляю ставку")

        scope.launch {
            val body = JSONObject().put("auto", autoCashout).put("amount", amount)
            val result = Api.call("/v1/crash/create-bet", "POST", body)
            requestInFlight = false
            val inner = result.data?.optJSONObject("data")
            val success = result.status in 200..299 &&
                !result.unauthorized &&
                result.data?.optBoolean("success", true) != false &&
                inner?.optBoolean("success", true) != false &&
                result.error == null

            if (!success) {
                val message = result.error
                    ?: if (result.unauthorized) Api.AUTH_MSG
                    else result.data?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: inner?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${result.status}"
                stopRunner("Ставка не принята: $message")
                stopSelf()
                return@launch
            }

            placed++
            if (placed >= roundsLimit) {
                stopRunner("Лимит раундов выполнен ($placed/$roundsLimit)")
                stopSelf()
            } else {
                CrashState.set(true, "Ставка отправлена · $placed/$roundsLimit · ждём следующий раунд")
                updateNotification("Ставка отправлена · $placed/$roundsLimit")
            }
        }
    }

    private fun firstString(o: JSONObject, keys: Array<String>): String {
        for (key in keys) {
            val value = o.opt(key)
            if (value is String && value.isNotBlank()) return value
            if (value is Number) return value.toString()
        }
        return ""
    }

    private fun stopRunner(reason: String) {
        running = false
        requestInFlight = false
        CrashState.set(false, reason)
        updateNotification(reason)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_CRASH)
            .setContentTitle("CS2A Помощник — Crash")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(running)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, notification(text))
    }

    inner class Bridge {
        @JavascriptInterface
        fun emit(type: String, json: String) {
            if (type != "crash-event") return
            handler.post {
                try {
                    val o = JSONObject(json)
                    onCrashEvent(o.optString("event"), o.optJSONObject("payload") ?: JSONObject())
                } catch (_: Exception) { }
            }
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.cs2a.helper.crash.START"
        const val ACTION_STOP = "ru.cs2a.helper.crash.STOP"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_ROUNDS = "rounds"
        const val NOTIF_ID = 43
    }
}
