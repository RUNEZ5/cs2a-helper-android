package ru.cs2a.helper.crash

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import ru.cs2a.helper.Logs
import ru.cs2a.helper.MainActivity
import ru.cs2a.helper.ORIGIN
import ru.cs2a.helper.R
import ru.cs2a.helper.TokenStore

// Dedicated foreground WebView monitor for csgorun:crash.
// The service never starts bets by itself: only an explicit Start action enables crashAutoRunning.
class CrashMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var webView: WebView? = null
    private var currentRoundId: String? = null
    private var betInFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TokenStore.init(this)
        if (intent?.action == ACTION_STOP) {
            TokenStore.setBool("crashAutoRunning", false)
            TokenStore.setString("crashStatus", "Остановлено вручную")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!TokenStore.getBool("crashAutoRunning")) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification("Подключаюсь к Crash…"))
        ensureWebView()
        return START_STICKY
    }

    private fun ensureWebView() {
        if (webView != null) return
        val wv = WebView(this)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                try {
                    val js = assets.open("cs2a_inject.js").bufferedReader().use { it.readText() }
                    view.evaluateJavascript(js, null)
                    updateStatus("Подключено к cs2a.run · ожидание ставок")
                } catch (e: Exception) {
                    stopWithError("Не удалось подключить монитор: ${e.message}")
                }
            }
        }
        wv.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView = wv
        wv.loadUrl(ORIGIN)
    }

    private fun handleCrashEvent(event: JSONObject) {
        if (!TokenStore.getBool("crashAutoRunning")) return
        when (event.optString("type")) {
            "start" -> {
                currentRoundId = event.opt("id")?.takeIf { it != JSONObject.NULL }?.toString()
                updateStatus("Раунд ${currentRoundId ?: "?"}: ожидание окна ставок")
            }
            "update" -> {
                val status = event.optInt("status", -1)
                val id = event.opt("id")?.takeIf { it != JSONObject.NULL }?.toString() ?: currentRoundId
                if (id != null && status == 1) {
                    updateStatus("Раунд $id: окно ставок открыто")
                    placeBet(id)
                } else if (status == 2) {
                    updateStatus("Раунд ${id ?: "?"}: ставки закрыты")
                }
            }
            "crash" -> updateStatus("Раунд завершён: x${event.opt("crash") ?: "?"}")
        }
    }

    private fun placeBet(roundId: String) {
        if (betInFlight || !TokenStore.getBool("crashAutoRunning")) return
        if (TokenStore.getString("crashLastRoundId") == roundId) return
        betInFlight = true
        // Persist round ID before network work to prevent duplicate requests after repeated updates.
        TokenStore.setString("crashLastRoundId", roundId)
        scope.launch {
            try {
                val balance = Api.crashBalance()
                val allIn = TokenStore.getBool("crashAllIn")
                val amount = if (allIn) balance else TokenStore.getString("crashAmount", "0.10").toDoubleOrNull() ?: 0.0
                if (amount <= 0.0) throw IllegalStateException("недостаточно средств для ставки")
                if (amount > balance + 1e-9) throw IllegalStateException("баланс $balance меньше ставки $amount")
                if (!TokenStore.getBool("crashAutoRunning")) return@launch

                val multiplier = TokenStore.getString("crashAutoMultiplier", "1.25").toDoubleOrNull() ?: 1.25
                val result = Api.createCrashBet(multiplier, amount)
                val outer = result.data ?: JSONObject()
                val inner = outer.optJSONObject("data") ?: outer
                val failed = result.unauthorized || result.error != null || result.status !in 200..299 ||
                    outer.optBoolean("success", true).not() || inner.optBoolean("success", true).not() ||
                    outer.has("error") || inner.has("error")
                if (failed) throw IllegalStateException(result.error ?: outer.optString("error").ifBlank { inner.optString("error").ifBlank { "HTTP ${result.status}" } })

                val done = TokenStore.getInt("crashRoundsDone", 0) + 1
                val total = TokenStore.getInt("crashRoundsTotal", 0)
                TokenStore.setInt("crashRoundsDone", done)
                val message = "Ставка $done/$total принята · раунд $roundId · сумма $amount"
                Logs.add(this@CrashMonitorService, "crash", message, "ok")
                if (done >= total) {
                    TokenStore.setBool("crashAutoRunning", false)
                    updateStatus("Готово: $done/$total ставок")
                    stopSelf()
                } else {
                    updateStatus("$message · жду следующий раунд")
                }
            } catch (e: Exception) {
                stopWithError("Автоставки остановлены: ${e.message ?: "ошибка"}")
            } finally {
                betInFlight = false
            }
        }
    }

    private fun updateStatus(message: String) {
        TokenStore.setString("crashStatus", message)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, notification(message))
    }

    private fun stopWithError(message: String) {
        TokenStore.setBool("crashAutoRunning", false)
        TokenStore.setString("crashStatus", message)
        Logs.add(this, "crash", message, "bad")
        updateStatus(message)
        stopSelf()
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, App.CHANNEL_CRASH)
            .setContentTitle("CS2A Помощник — Crash")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(TokenStore.getBool("crashAutoRunning"))
            .build()
    }

    inner class Bridge {
        @JavascriptInterface
        fun emit(type: String, json: String) {
            handler.post {
                try {
                    val obj = JSONObject(json)
                    when (type) {
                        "jwt" -> obj.optString("token").takeIf { it.isNotBlank() }?.let { TokenStore.saveJwt(it) }
                        "crash-event" -> obj.optJSONObject("event")?.let { handleCrashEvent(it) }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    override fun onDestroy() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.cs2a.helper.crash.START"
        const val ACTION_STOP = "ru.cs2a.helper.crash.STOP"
        private const val NOTIF_ID = 43
    }
}
