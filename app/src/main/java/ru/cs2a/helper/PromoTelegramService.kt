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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.SecureRandom

class PromoTelegramService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var chatId = 0L
    @Volatile private var historyScanStarted = false
    @Volatile private var historyScanCancelled = false
    @Volatile private var historyScanJob: Job? = null
    private var channelSearchStarted = false
    private var historyPagesScanned = 0
    private val historyCandidates = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TokenStore.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Подключение к Telegram…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && (TokenStore.getBool("promoAuto") || TokenStore.getBool("promoScanRequested")) && client == null) startClient()
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
            ACTION_START -> {
                TokenStore.setBool("promoAuto", false)
                startClient()
            }
            ACTION_SCAN_HISTORY -> {
                if (historyScanStarted || TokenStore.getBool("promoScanRequested")) {
                    status("Проверка истории уже запущена")
                } else {
                    historyScanCancelled = false
                    TokenStore.setBool("promoScanRequested", true)
                    if (client == null) startClient() else if (chatId != 0L) scanHistory() else findChannel()
                }
            }
            ACTION_MONITOR_START -> {
                TokenStore.setBool("promoAuto", true)
                if (client == null) startClient() else if (chatId != 0L) status("Мониторинг канала активен") else findChannel()
            }
            ACTION_MONITOR_STOP -> {
                TokenStore.setBool("promoAuto", false)
                status(if (chatId != 0L) "Telegram подключён · мониторинг остановлен" else "Мониторинг остановлен")
            }
            ACTION_SCAN_STOP -> {
                historyScanCancelled = true
                TokenStore.setBool("promoScanRequested", false)
                historyScanJob?.cancel()
                historyScanJob = null
                historyScanStarted = false
                status("Проверка истории остановлена пользователем")
            }
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
                    val phone = TokenStore.getSecret("promoPhone")
                    if (phone.isNotBlank()) client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null), authResult("номер отправлен"))
                    else status("Введите номер телефона")
                }
                is TdApi.AuthorizationStateWaitCode -> status("Telegram отправил код. Введите его в приложении")
                is TdApi.AuthorizationStateWaitPassword -> status("Требуется пароль двухэтапной аутентификации")
                is TdApi.AuthorizationStateReady -> {
                    TokenStore.saveSecret("promo2fa", "")
                    TokenStore.setString("promoLoginCode", "")
                    status("Telegram подключён · поиск канала")
                    findChannel()
                }
                is TdApi.AuthorizationStateClosed -> {
                    client = null
                    if (TokenStore.getBool("promoAuto")) status("Сессия Telegram закрыта")
                }
                else -> status("Состояние Telegram: ${state.javaClass.simpleName}")
            }
            is TdApi.UpdateNewMessage -> {
                if (TokenStore.getBool("promoAuto") && obj.message.chatId == chatId) {
                    scope.launch { processMessage(obj.message, false) }
                }
            }
            is TdApi.UpdateMessageEdited -> {
                if (TokenStore.getBool("promoAuto") && obj.chatId == chatId) {
                    // Telegram does not resend edited posts as UpdateNewMessage.
                    // Fetch the updated message and run the same parser/activation flow.
                    client?.send(TdApi.GetMessage(obj.chatId, obj.messageId)) { result ->
                        if (result is TdApi.Message && TokenStore.getBool("promoAuto")) {
                            scope.launch { processMessage(result, false) }
                        }
                    }
                }
            }
            is TdApi.UpdateConnectionState -> status("Telegram: ${obj.state.javaClass.simpleName}")
        }
    }

    private fun authResult(label: String) = Client.ResultHandler { result ->
        if (result is TdApi.Error) status("Telegram: ${result.message}")
        else status(label)
    }

    private fun findChannel() {
        if (channelSearchStarted) return
        channelSearchStarted = true
        client?.send(TdApi.SearchPublicChat("@promo_runs")) { result ->
            if (result is TdApi.Chat) {
                chatId = result.id
                client?.send(TdApi.OpenChat(chatId), authResult("Канал подключён"))
                when {
                    TokenStore.getBool("promoScanRequested") -> scanHistory()
                    TokenStore.getBool("promoAuto") -> status("Мониторинг канала активен")
                    else -> status("Telegram подключён · канал готов")
                }
            } else if (result is TdApi.Error) {
                channelSearchStarted = false
                status("Не удалось подключиться к каналу: ${result.message}")
            }
        }
    }

    private fun scanHistory() {
        if (historyScanStarted || chatId == 0L) return
        historyScanStarted = true
        historyScanCancelled = false
        historyPagesScanned = 0
        historyCandidates.clear()
        historyScanJob = null
        TokenStore.setBool("promoScanRequested", false)
        val days = TokenStore.getInt("promoScanDays", 7).coerceIn(1, 36500)
        val cutoff = (System.currentTimeMillis() / 1000L) - days * 86400L
        status("Первичная проверка истории канала за $days дн.")
        scanHistoryPage(0L, cutoff, 0)
    }

    private fun scanHistoryPage(fromMessageId: Long, cutoff: Long, scanned: Int) {
        if (historyScanCancelled) return
        if (historyPagesScanned >= 100) {
            historyScanStarted = false
            status("Проверка остановлена: достигнут лимит 10 000 сообщений")
            return
        }
        val activeClient = client
        if (activeClient == null) {
            historyScanStarted = false
            status("Проверка остановлена: соединение Telegram потеряно")
            return
        }
        activeClient.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, 100, false)) { result ->
            if (historyScanCancelled) return@send
            if (result is TdApi.Messages) {
                val page = result.messages
                historyPagesScanned++
                val pageJob = scope.launch(start = CoroutineStart.LAZY) {
                    for (msg in page) {
                        if (historyScanCancelled) return@launch
                        if (msg.date.toLong() >= cutoff) {
                            for (code in extractCodes(msg)) {
                                if (historyCandidates.add(code)) {
                                    Logs.add(this@PromoTelegramService, "promo", "Найден код: $code · история", "ok")
                                }
                            }
                        }
                    }
                    if (historyScanCancelled) return@launch
                    val oldest = page.minByOrNull { it.id }
                    val total = scanned + page.size
                    // TDLib may return a partial page even when older messages remain.
                    // Page length is NOT a reliable end-of-history signal; continue until
                    // the page is empty, reaches the cutoff, or makes no ID progress.
                    val hasOlderPage = oldest != null &&
                        oldest.date.toLong() >= cutoff &&
                        (fromMessageId == 0L || oldest.id < fromMessageId)
                    if (hasOlderPage) {
                        scanHistoryPage(oldest!!.id, cutoff, total)
                    } else {
                        val candidates = historyCandidates.toList()
                        historyScanJob = scope.launch {
                            for (code in candidates) {
                                if (historyScanCancelled) break
                                processCodes(listOf(code), true)
                            }
                            historyScanStarted = false
                            historyScanJob = null
                            historyCandidates.clear()
                            if (!historyScanCancelled) {
                                status("Проверка истории завершена · просмотрено сообщений: " + total)
                            }
                        }
                    }
                }
                historyScanJob = pageJob
                pageJob.start()
            } else if (result is TdApi.Error) {
                historyScanStarted = false
                historyScanJob = null
                status("Ошибка чтения истории: " + result.message)
            }
        }
    }

    private fun extractCodes(message: TdApi.Message): List<String> {
        val formatted = when (val content = message.content) {
            is TdApi.MessageText -> content.text
            is TdApi.MessagePhoto -> content.caption
            is TdApi.MessageVideo -> content.caption
            is TdApi.MessageAnimation -> content.caption
            is TdApi.MessageDocument -> content.caption
            is TdApi.MessageAudio -> content.caption
            else -> return emptyList()
        }
        val body = formatted.text ?: return emptyList()
        val segments = formatted.entities.mapNotNull { entity ->
            val typeName = entity.type.javaClass.simpleName
            if (typeName !in setOf("TextEntityTypeCode", "TextEntityTypePre", "TextEntityTypePreCode")) {
                return@mapNotNull null
            }
            val start = entity.offset.coerceAtLeast(0)
            val end = (start + entity.length).coerceAtMost(body.length)
            if (start >= end) null else body.substring(start, end)
        }
        return PromoCodeParser.extract(body, segments)
    }

    private suspend fun processMessage(message: TdApi.Message, fromHistory: Boolean) {
        processCodes(extractCodes(message), fromHistory)
    }

    private suspend fun processCodes(codes: List<String>, fromHistory: Boolean) {
        for (code in codes) {
            if (fromHistory && historyScanCancelled) return
            if (PromoActivator.alreadyProcessed(code)) continue
            if (!fromHistory) {
                Logs.add(this@PromoTelegramService, "promo", "Найден код: $code · новый пост", "ok")
            }
            val mirror = TokenStore.getString("promoMirror", "cs2a.run")
            val result = PromoActivator.enqueue(code, mirror) { response ->
                Logs.add(
                    this@PromoTelegramService,
                    "promo",
                    "${response.code}: ${response.message}" + if (response.status > 0) " (HTTP ${response.status})" else "",
                    if (response.activated) "ok" else "bad"
                )
            }
            if (result.message != "запрос отправлен; ожидание ответа не блокирует очередь") {
                Logs.add(this@PromoTelegramService, "promo", "${result.code}: ${result.message}", "bad")
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
        historyScanCancelled = true
        historyScanJob?.cancel()
        scope.cancel()
        if (!TokenStore.getBool("promoAuto")) client = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.cs2a.helper.PROMO_START"
        const val ACTION_STOP = "ru.cs2a.helper.PROMO_STOP"
        const val ACTION_SCAN_HISTORY = "ru.cs2a.helper.PROMO_SCAN_HISTORY"
        const val ACTION_MONITOR_START = "ru.cs2a.helper.PROMO_MONITOR_START"
        const val ACTION_MONITOR_STOP = "ru.cs2a.helper.PROMO_MONITOR_STOP"
        const val ACTION_SCAN_STOP = "ru.cs2a.helper.PROMO_SCAN_STOP"
        const val ACTION_CODE = "ru.cs2a.helper.PROMO_CODE"
        const val ACTION_PASSWORD = "ru.cs2a.helper.PROMO_PASSWORD"
        private const val NOTIFICATION_ID = 52
        @Volatile private var client: Client? = null

        fun start(context: Context) {
            val intent = Intent(context, PromoTelegramService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, PromoTelegramService::class.java).setAction(ACTION_MONITOR_STOP))
        }
        fun scanHistory(context: Context) {
            context.startService(Intent(context, PromoTelegramService::class.java).setAction(ACTION_SCAN_HISTORY))
        }
        fun startMonitoring(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, PromoTelegramService::class.java).setAction(ACTION_MONITOR_START)
            )
        }
        fun stopHistoryScan(context: Context) {
            context.startService(Intent(context, PromoTelegramService::class.java).setAction(ACTION_SCAN_STOP))
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
