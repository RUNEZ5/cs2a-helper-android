package ru.cs2a.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

object PromoActivator {
    val mirrors = listOf("cs2a.run", "csgouv.run", "cs2run.bet", "cs2b.run", "cs2t.run", "runcase.org", "csgorun.games")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // All activation calls share one queue, including calls coming from separate Telegram updates.
    private val activationMutex = Mutex()
    private var lastRequestAt = 0L
    private var blockedUntil = 0L
    private const val MIN_REQUEST_INTERVAL_MS = 2_000L
    private const val DEFAULT_RATE_LIMIT_WAIT_MS = 15_000L

    data class Result(val code: String, val activated: Boolean, val message: String, val status: Int)

    private fun historyKey(jwt: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(jwt.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "promoHistory_$hash"
    }

    fun alreadyProcessed(code: String): Boolean {
        val jwt = TokenStore.jwt() ?: return false
        val history = try { JSONArray(TokenStore.getString(historyKey(jwt), "[]")) } catch (_: Exception) { JSONArray() }
        // A code is considered processed only after confirmed activation.
        // Failed attempts remain eligible during later history scans/retries.
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            if (item.optString("code") == code && item.optBoolean("activated", false)) return true
        }
        return false
    }

    suspend fun activate(code: String, mirror: String): Result = withContext(Dispatchers.IO) {
        activationMutex.withLock {
            val normalized = PromoCodeParser.extract(code).firstOrNull() ?: code.trim().uppercase()
            if (normalized.isBlank()) return@withLock Result(code, false, "пустой код", 0)
            if (mirror !in mirrors) return@withLock Result(normalized, false, "неизвестное зеркало", 0)
            if (alreadyProcessed(normalized)) return@withLock Result(normalized, false, "уже обработан ранее", 0)
            val jwt = TokenStore.jwt()
                ?: return@withLock Result(normalized, false, Api.AUTH_MSG, 0)

            try {
                val now = System.currentTimeMillis()
                val allowedAt = maxOf(lastRequestAt + MIN_REQUEST_INTERVAL_MS, blockedUntil)
                val waitMs = allowedAt - now
                if (waitMs > 0) delay(waitMs)
                lastRequestAt = System.currentTimeMillis()

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
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val root = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                    val data = root.optJSONObject("data") ?: root
                    val activated = response.isSuccessful && data.optBoolean("isActivate", false)
                    val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                    if (response.code == 429) {
                        val retryMs = (retryAfterSeconds?.coerceAtLeast(1)?.times(1_000L))
                            ?: DEFAULT_RATE_LIMIT_WAIT_MS
                        blockedUntil = maxOf(blockedUntil, System.currentTimeMillis() + retryMs)
                    }
                    val message = when {
                        activated -> "активирован"
                        response.code == 429 -> "Сервер ограничил частоту запросов. Очередь приостановлена на ${((blockedUntil - System.currentTimeMillis()).coerceAtLeast(0) / 1000)} сек."
                        data.optString("error").isNotBlank() -> data.optString("error")
                        root.optString("message").isNotBlank() -> root.optString("message")
                        else -> "HTTP ${response.code}: ${raw.take(180)}"
                    }
                    if (response.code != 429 && response.code !in 500..599) {
                        remember(normalized, mirror, activated, message, jwt)
                    }
                    Result(normalized, activated, message, response.code)
                }
            } catch (e: Exception) {
                Result(normalized, false, e.message ?: "ошибка сети", 0)
            }
        }
    }

    private fun remember(code: String, mirror: String, activated: Boolean, message: String, jwt: String) {
        val key = historyKey(jwt)
        val history = try { JSONArray(TokenStore.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        history.put(JSONObject().put("code", code).put("mirror", mirror)
            .put("activated", activated).put("message", message).put("time", System.currentTimeMillis()))
        while (history.length() > 500) history.remove(0)
        TokenStore.setString(key, history.toString())
    }
}
