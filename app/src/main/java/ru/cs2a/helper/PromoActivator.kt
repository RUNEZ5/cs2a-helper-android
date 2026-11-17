package ru.cs2a.helper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PromoActivator {
    val mirrors = listOf("cs2a.run", "csgouv.run", "cs2run.bet", "cs2b.run", "cs2t.run", "runcase.org", "csgorun.games")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // Serialize dispatch only, never wait for the HTTP response while holding the queue.
    private val dispatchMutex = Mutex()
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_TIMEOUT_RETRIES = 2
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastDispatchAt = 0L
    @Volatile private var blockedUntil = 0L
    private fun minRequestIntervalMs(): Long = TokenStore.getInt("promoActivationIntervalSec", 1).coerceIn(1, 86400) * 1_000L
    private const val DEFAULT_RATE_LIMIT_WAIT_MS = 15_000L

    data class Result(val code: String, val activated: Boolean, val message: String, val status: Int)

    private fun historyKey(jwt: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(jwt.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "promoHistory_$hash"
    }

    private fun historyMigrationKey(jwt: String) = historyKey(jwt) + "_migrated"

    @Synchronized
    private fun historyFile(jwt: String): File? {
        val context = TokenStore.appContext() ?: return null
        val hash = historyKey(jwt).removePrefix("promoHistory_")
        val file = File(context.filesDir, "promo_history_$hash.jsonl")
        if (!TokenStore.getBool(historyMigrationKey(jwt))) {
            val legacy = try { JSONArray(TokenStore.getString(historyKey(jwt), "[]")) } catch (_: Exception) { JSONArray() }
            if (legacy.length() > 0) {
                FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
                    for (i in 0 until legacy.length()) {
                        val item = legacy.optJSONObject(i) ?: continue
                        writer.append(item.toString()).append('\n')
                    }
                }
            }
            TokenStore.setString(historyKey(jwt), "")
            TokenStore.setBool(historyMigrationKey(jwt), true)
        }
        return file
    }

    @Synchronized
    private fun readHistory(jwt: String): List<JSONObject> {
        val file = historyFile(jwt)
        if (file == null) {
            val legacy = try { JSONArray(TokenStore.getString(historyKey(jwt), "[]")) } catch (_: Exception) { JSONArray() }
            return (0 until legacy.length()).mapNotNull { legacy.optJSONObject(it) }
        }
        if (!file.exists()) return emptyList()
        val items = ArrayList<JSONObject>()
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) try { items.add(JSONObject(line)) } catch (_: Exception) { }
        }
        return items
    }

    @Synchronized
    private fun appendHistory(jwt: String, item: JSONObject) {
        val file = historyFile(jwt)
        if (file == null) {
            val legacy = try { JSONArray(TokenStore.getString(historyKey(jwt), "[]")) } catch (_: Exception) { JSONArray() }
            legacy.put(item)
            TokenStore.setString(historyKey(jwt), legacy.toString())
            return
        }
        FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(item.toString()).append('\n')
        }
    }

    @Synchronized
    fun clearCurrentAccountHistory(): Boolean {
        val jwt = TokenStore.jwt() ?: return false
        val file = historyFile(jwt)
        if (file != null) file.writeText("", Charsets.UTF_8)
        TokenStore.setString(historyKey(jwt), "")
        TokenStore.setBool(historyMigrationKey(jwt), true)
        return true
    }

    fun alreadyProcessed(code: String): Boolean {
        val jwt = TokenStore.jwt() ?: return false
        return readHistory(jwt).any { item ->
            item.optString("code") == code && item.optBoolean("activated", false)
        }
    }

    suspend fun enqueue(code: String, mirror: String, onResult: (Result) -> Unit): Result = withContext(Dispatchers.IO) {
        val normalized = PromoCodeParser.extract(code).singleOrNull()
            ?: return@withContext Result(code, false, "отклонено: текст не распознан как промокод", 0)
        if (mirror !in mirrors) return@withContext Result(normalized, false, "неизвестное зеркало", 0)
        val jwt = TokenStore.jwt() ?: return@withContext Result(normalized, false, Api.AUTH_MSG, 0)
        if (alreadyProcessed(normalized)) return@withContext Result(normalized, false, "уже активирован ранее", 0)
        if (!pending.add(normalized)) return@withContext Result(normalized, false, "уже ожидает отправки", 0)

        try {
            dispatchMutex.withLock {
                while (true) {
                    val waitMs = maxOf(lastDispatchAt + minRequestIntervalMs(), blockedUntil) - System.currentTimeMillis()
                    if (waitMs <= 0) break
                    delay(waitMs)
                }
                val origin = "https://$mirror"
                val body = JSONObject().put("code", normalized).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://onemails.net/discount")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "JWT $jwt")
                    .header("Origin", origin)
                    .header("Referer", "$origin/")
                    .post(body)
                    .build()

                lastDispatchAt = System.currentTimeMillis()
                sendAsync(request, normalized, mirror, jwt, 0, onResult)
            }
            Result(normalized, false, "запрос отправлен; ожидание ответа не блокирует очередь", 0)
        } catch (e: CancellationException) {
            pending.remove(normalized)
            throw e
        } catch (e: Exception) {
            pending.remove(normalized)
            Result(normalized, false, e.message ?: "ошибка отправки", 0)
        }
    }

    private fun sendAsync(
        request: Request,
        code: String,
        mirror: String,
        jwt: String,
        attempt: Int,
        onResult: (Result) -> Unit
    ) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val isTimeout = e is java.net.SocketTimeoutException ||
                    (e.message?.contains("timeout", ignoreCase = true) == true)
                if (isTimeout && attempt < MAX_TIMEOUT_RETRIES) {
                    onResult(Result(code, false, "таймаут; повторная попытка ${attempt + 1}/$MAX_TIMEOUT_RETRIES", 0))
                    retryScope.launch {
                        dispatchMutex.withLock {
                            while (true) {
                                val waitMs = maxOf(lastDispatchAt + minRequestIntervalMs(), blockedUntil) - System.currentTimeMillis()
                                if (waitMs <= 0) break
                                delay(waitMs)
                            }
                            lastDispatchAt = System.currentTimeMillis()
                            sendAsync(request, code, mirror, jwt, attempt + 1, onResult)
                        }
                    }
                } else {
                    pending.remove(code)
                    onResult(Result(code, false, "ответ не получен: ${e.message ?: "ошибка сети"}", 0))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    val root = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                    val data = root.optJSONObject("data") ?: root
                    val activated = it.isSuccessful && data.optBoolean("isActivate", false)
                    if (it.code == 429) {
                        val retrySeconds = it.header("Retry-After")?.toLongOrNull()
                        val retryMs = retrySeconds?.coerceAtLeast(1)?.times(1_000L) ?: DEFAULT_RATE_LIMIT_WAIT_MS
                        blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + retryMs)
                    }
                    val message = when {
                        activated -> "активирован"
                        it.code == 429 -> "HTTP 429: ограничение частоты, очередь приостановлена"
                        data.optString("error").isNotBlank() -> data.optString("error").take(120)
                        root.optString("message").isNotBlank() -> root.optString("message").take(120)
                        raw.trimStart().startsWith("<") && it.code == 502 -> "HTTP 502: ошибка шлюза (HTML скрыт)"
                        raw.trimStart().startsWith("<") && it.code == 403 -> "HTTP 403: доступ запрещён (HTML скрыт)"
                        raw.trimStart().startsWith("<") -> "HTTP ${it.code}: HTML вместо JSON"
                        else -> "HTTP ${it.code}: ${raw.take(120)}"
                    }
                    if (it.code != 429 && it.code !in 500..599) remember(code, mirror, activated, message, jwt)
                    pending.remove(code)
                    onResult(Result(code, activated, message, it.code))
                }
            }
        })
    }


    // Result windows use stable reason groups; old history entries are classified on read too.
    fun historyForCategory(category: String): String {
        val jwt = TokenStore.jwt() ?: return "Сначала войдите в аккаунт на выбранном зеркале."
        val items = readHistory(jwt).filter { item ->
            val group = resultCategory(item.optBoolean("activated", false), item.optString("message"))
            category == "all" || category == group
        }
        if (items.isEmpty()) return "Пока нет результатов в этой категории."
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
        return items.asReversed().joinToString("\n\n") { item ->
            val time = fmt.format(Date(item.optLong("time")))
            val code = item.optString("code")
            val message = item.optString("message")
            val mirror = item.optString("mirror")
            "$time  ·  $code\n$message\nЗеркало: $mirror"
        }
    }

    private fun resultCategory(activated: Boolean, message: String): String {
        if (activated) return "activated"
        val value = message.uppercase(Locale.ROOT)
        return when {
            value.contains("DEPOSIT_CONDITION") || value.contains("ДЕПОЗИТ") ||
                value.contains("DEPOSIT CONDITION") || value.contains("MINIMUM TOP") -> "deposit"
            value.contains("ALREADY") || value.contains("УЖЕ") ||
                value.contains("USED") || value.contains("ИСПОЛЬЗОВАН") -> "used"
            value.contains("INVALID") || value.contains("NOT FOUND") ||
                value.contains("EXPIRED") || value.contains("НЕ НАЙДЕН") ||
                value.contains("НЕВЕРН") || value.contains("ИСТЁК") ||
                value.contains("ИСТЕК") -> "invalid"
            else -> "other"
        }
    }

    private fun remember(code: String, mirror: String, activated: Boolean, message: String, jwt: String) {
        appendHistory(jwt, JSONObject().put("code", code).put("mirror", mirror)
            .put("activated", activated).put("message", message).put("time", System.currentTimeMillis()))
    }
}
