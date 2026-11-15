package ru.cs2a.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.SecureRandom

class PromoTelegramService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var chatId = 0L
    private var historyScanStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TokenStore.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Подключение к Telegram…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && TokenStore.getBool("promoAuto") && client == null) startClient()
        when (intent?.action) {
            ACTION_STOP -> {
                TokenStore.setBool("promoAuto", false)
                try { client?.send(TdApi.Close(), null) } catch (_: Exception) {}
                client = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CODE -> {
                val code = TokenStore.getString("promoLoginCode")
                TokenStore.setString("promoLoginCode", "")
                if (code.isNotBlank()) client?.send(TdApi.CheckAuthenticationCode(code), authResult("код отправлен"))
                return START_STICKY
            }
            ACTION_PASSWORD -> {
                val password = TokenStore.getSecret("promo2fa")
                if (password.isNotBlank()) client?.send(TdApi.CheckAuthenticationPassword(password), authResult("пароль отправлен"))
                return START_STICKY
            }
            ACTION_START -> startClient()
        }
        return START_STICKY
    }

    private fun startClient() {
        if (client != null) return
        val apiId = TokenStore.getString("promoApiId").toIntOrNull()
        val apiHash = TokenStore.getSecret("promoApiHash")
        val phone = TokenStore.getSecret("promoPhone")
        if (apiId == null || apiId <= 0 || apiHash.isBlank() || phone.isBlank()) {
            status("Заполните API ID, API Hash и номер телефона")
            stopSelf()
            return
        }
        try {
            System.loadLibrary("tdjni")
            client = Client.create(Client.ResultHandler { update -> handleUpdate(update) }, null, null)
            status("Инициализация Telegram…")
        } catch (e: Exception) {
            status("Ошибка TDLib: ${e.message}")
            Log.e("CS2APromo", "TDLib init failed", e)
        }
    }

    private fun handleUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> when (val state = obj.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> {
                    val dir = File(filesDir, "telegram-session").apply { mkdirs() }
                    val p = TdApi.SetTdlibParameters().apply {
                        useTestDc = false
                        databaseDirectory = dir.absolutePath
                        filesDirectory = File(dir, "files").apply { mkdirs() }.absolutePath
                        var dbKey = TokenStore.getSecret("promoDbKey")
                        if (dbKey.isBlank()) {
                            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                            dbKey = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            TokenStore.saveSecret("promoDbKey", dbKey)
                        }
                        databaseEncryptionKey = android.util.Base64.decode(dbKey, android.util.Base64.NO_WRAP)
                        useFileDatabase = true
                        useChatInfoDatabase = true
                        useMessageDatabase = true
                        useSecretChats = false
                        apiId = TokenStore.getString("promoApiId").toInt()
                        apiHash = TokenStore.getSecret("promoApiHash")
                        systemLanguageCode = "ru"
                        deviceModel = Build.MODEL ?: "Android"
                        systemVersion = Build.VERSION.RELEASE ?: "Android"
                        applicationVersion = BuildConfig.VERSION_NAME
                    }
                    client?.send(p, authResult("параметры Telegram заданы"))
                }
                is TdApi.AuthorizationStateWaitPhoneNumber -> {
                    val phone = TokenStore.getString("promoPhone")
                    if (phone.isNotBlank()) client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), authResult("номер отправлен"))
                    else status("Введите номер телефона")
                }
                is TdApi.AuthorizationStateWaitCode -> status("Telegram отправил код. Введите его в приложении")
                is TdApi.AuthorizationStateWaitPassword -> status("Требуется пароль двухэтапной аутентификации")
                is TdApi.AuthorizationStateReady -> {
                    TokenStore.saveSecret("promo2fa", "")
                    TokenStore.setString("promoLoginCode", "")
                    TokenStore.setBool("promoAuto", true)
                    status("Telegram подключён · ищу @promo_runs")
                    findChannel()
                }
                is TdApi.AuthorizationStateClosed -> {
                    client = null
                    if (TokenStore.getBool("promoAuto")) status("Сессия Telegram закрыта")
                }
                else -> status("Состояние Telegram: ${state.javaClass.simpleName}")
            }
            is TdApi.UpdateNewMessage -> {
                if (obj.message.chatId == chatId) processMessage(obj.message, false)
            }
            is TdApi.UpdateConnectionState -> status("Telegram: ${obj.state.javaClass.simpleName}")
        }
    }

    private fun authResult(label: String) = Client.ResultHandler { result ->
        if (result is TdApi.Error) status("Telegram: ${result.message}")
        else status(label)
    }

    private fun findChannel() {
        client?.send(TdApi.SearchPublicChat("@promo_runs")) { result ->
            if (result is TdApi.Chat) {
                chatId = result.id
                client?.send(TdApi.OpenChat(chatId), authResult("Канал @promo_runs открыт"))
                scanHistory()
            } else if (result is TdApi.Error) status("Не удалось открыть @promo_runs: ${result.message}")
        }
    }

    private fun scanHistory() {
        if (historyScanStarted || chatId == 0L) return
        historyScanStarted = true
        val days = TokenStore.getInt("promoScanDays", 7).coerceIn(1, 365)
        val cutoff = (System.currentTimeMillis() / 1000L) - days * 86400L
        status("Первичная проверка @promo_runs за $days дн.")
        scanHistoryPage(0L, cutoff, 0)
    }

    private fun scanHistoryPage(fromMessageId: Long, cutoff: Long, scanned: Int) {
        client?.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 100, false)) { result ->
            if (result is TdApi.Messages) {
                val page = result.messages
                page.sortedBy { it.date }.forEach { msg ->
                    if (msg.date.toLong() >= cutoff) processMessage(msg, true)
                }
                val oldest = page.minByOrNull { it.id }
                val total = scanned + page.size
                if (oldest != null && oldest.date.toLong() >= cutoff && page.size == 100 && oldest.id != fromMessageId) {
                    scanHistoryPage(oldest.id, cutoff, total)
                } else {
                    status("Мониторинг @promo_runs активен · просмотрено сообщений: " + total)
                }
            } else if (result is TdApi.Error) {
                status("Ошибка чтения истории: " + result.message)
            }
        }
    }

    private fun processMessage(message: TdApi.Message, fromHistory: Boolean) {
        val formatted = when (val content = message.content) {
            is TdApi.MessageText -> content.text
            is TdApi.MessagePhoto -> content.caption
            is TdApi.MessageVideo -> content.caption
            is TdApi.MessageAnimation -> content.caption
            is TdApi.MessageDocument -> content.caption
            is TdApi.MessageAudio -> content.caption
            is TdApi.MessageVoice -> content.caption
            else -> return
        }
        val body = formatted.text ?: return
        val segments = formatted.entities.mapNotNull { entity ->
            val start = entity.offset.coerceAtLeast(0)
            val end = (start + entity.length).coerceAtMost(body.length)
            if (start >= end) null else body.substring(start, end)
        }
        val codes = PromoCodeParser.extract(body, segments)
        if (codes.isEmpty()) return
        scope.launch {
            for (code in codes) {
                if (PromoActivator.alreadyProcessed(code)) continue
                Logs.add(this@PromoTelegramService, "promo", "Найден код: $code" + if (fromHistory) " · история" else " · новый пост", "ok")
                val mirror = TokenStore.getString("promoMirror", "cs2a.run")
                val result = PromoActivator.activate(code, mirror)
                Logs.add(this@PromoTelegramService, "promo", "${result.code}: ${result.message} (HTTP ${result.status})", if (result.activated) "ok" else "bad")
            }
        }
    }

    private fun status(text: String) {
        TokenStore.setString("promoStatus", text)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(text))
        Logs.add(this, "promo", text)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 911, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, App.CHANNEL_PROMO)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("CS2A · мониторинг промокодов")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(App.CHANNEL_PROMO, "Мониторинг промокодов Telegram", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        if (!TokenStore.getBool("promoAuto")) {
            client = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.cs2a.helper.PROMO_START"
        const val ACTION_STOP = "ru.cs2a.helper.PROMO_STOP"
        const val ACTION_CODE = "ru.cs2a.helper.PROMO_CODE"
        const val ACTION_PASSWORD = "ru.cs2a.helper.PROMO_PASSWORD"
        private const val NOTIFICATION_ID = 52
        @Volatile private var client: Client? = null

        fun start(context: Context) {
            val intent = Intent(context, PromoTelegramService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, PromoTelegramService::class.java).setAction(ACTION_STOP))
        }
        fun submitCode(context: Context, code: String) {
            TokenStore.setString("promoLoginCode", code)
            context.startService(Intent(context, PromoTelegramService::class.java).setAction(ACTION_CODE))
        }
        fun submitPassword(context: Context, password: String) {
            TokenStore.saveSecret("promo2fa", password)
            context.startService(Intent(context, PromoTelegramService::class.java).setAction(ACTION_PASSWORD))
        }
    }
}
