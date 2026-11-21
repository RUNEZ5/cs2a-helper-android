package ru.cs2a.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Повторяет authedFetch() из background.js: те же запросы, что шлёт сам сайт,
// с заголовком Authorization: JWT <токен>. Если токен протух и обновить его негде,
// просим пользователя перезайти в приложении.
object Api {
    const val AUTH_MSG = "нет авторизации (токен устарел) — войдите заново в разделе «Статус»"
    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class ApiResult(val status: Int, val data: JSONObject?, val unauthorized: Boolean, val error: String? = null)

    suspend fun call(path: String, method: String = "POST", json: JSONObject? = null): ApiResult = withContext(Dispatchers.IO) {
        val jwt = TokenStore.jwt()
        if (jwt.isNullOrBlank() || !TokenStore.isApiTokenValid()) {
            return@withContext ApiResult(0, null, true, AUTH_MSG)
        }
        try {
            val b = Request.Builder()
                .url(API + path)
                .header("Accept", "application/json, text/plain, */*")
                .header("Authorization", "JWT $jwt")
                .header("Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
            when (method) {
                "GET" -> b.get()
                else -> {
                    val body = (json ?: JSONObject()).toString().toRequestBody(JSON)
                    b.method(method, body)
                }
            }
            client.newCall(b.build()).execute().use { res ->
                val text = res.body?.string()
                val data = try { if (text.isNullOrBlank()) null else JSONObject(text) } catch (e: Exception) { null }
                val errMsg = data?.optString("error").orEmpty()
                val unauthorized = res.code == 401 || errMsg.contains("unauthorized", ignoreCase = true)
                ApiResult(res.code, data, unauthorized)
            }
        } catch (e: Exception) {
            ApiResult(-1, null, false, e.message ?: "ошибка сети")
        }
    }

    fun unwrap(d: JSONObject?): JSONObject {
        if (d == null) return JSONObject()
        val inner = d.optJSONObject("data")
        return inner ?: d
    }

    private fun apiError(r: ApiResult): String? {
        if (r.error != null) return r.error
        if (r.unauthorized) return AUTH_MSG
        if (r.status !in 200..299) return "HTTP ${r.status}"
        val root = r.data ?: return "пустой ответ сервера"
        if (root.optBoolean("success", true) == false) {
            return root.optString("error").ifBlank { "сервер отклонил запрос" }
        }
        val data = unwrap(root)
        if (data.has("error")) return data.optString("error").ifBlank { "ошибка сервера" }
        return null
    }

    // ---------------- ферма ----------------
    // Защита от одновременного ручного нажатия и срабатывания будильника.
    private val farmEggMutex = Mutex()

    suspend fun farmEggAttempt(ctx: android.content.Context): Boolean = farmEggMutex.withLock {
        // Сначала читаем фактическое состояние аккаунта. Никакой выбранный вручную tier
        // или локальный каталог не должен определять, какое яйцо можно забрать.
        val stateResult = call("/v1/farm/state", "GET")
        val stateError = apiError(stateResult)
        if (stateError != null) {
            Logs.add(ctx, "farm", "Не удалось проверить ферму: $stateError", "bad")
            return@withLock false
        }

        val state = unwrap(stateResult.data)
        val dailyEgg = state.optJSONObject("dailyEgg")
        if (dailyEgg == null) {
            val firstPending = state.optBoolean("firstEggPending", false)
            val reason = if (firstPending) "ожидается первое яйцо аккаунта" else "сервер не вернул dailyEgg"
            Logs.add(ctx, "farm", "Яйцо не найдено: $reason", "")
            return@withLock false
        }

        val status = dailyEgg.optString("status", "unknown").lowercase()
        val tier = dailyEgg.optInt("tier", -1)
        val boxId = dailyEgg.optInt("boxId", 0)
        val slug = dailyEgg.optString("slug", "").trim()
        val nextEggAt = dailyEgg.optString("nextEggAt", "")

        if (status != "ready") {
            val message = when (status) {
                "opened" -> "Ежедневное яйцо уже открыто"
                "claimed" -> "Яйцо уже забрано; повторное получение пропущено"
                "expired" -> "Срок получения яйца истёк"
                else -> "Яйцо пока недоступно (status: $status)"
            }
            Logs.add(ctx, "farm", message + if (nextEggAt.isNotBlank()) "; следующее: $nextEggAt" else "", "")
            return@withLock false
        }

        if (tier < 0 || boxId <= 0 || slug.isBlank()) {
            Logs.add(ctx, "farm", "Сервер сообщил status=ready, но не вернул корректные tier/boxId/slug", "bad")
            return@withLock false
        }

        Logs.add(ctx, "farm", "Найдено доступное яйцо: $slug (tier $tier, boxId $boxId)", "ok")

        // Claim использует tier из dailyEgg текущего аккаунта, а не сохранённую настройку.
        val claim = call("/v1/farm/eggs/daily/claim", "POST", JSONObject().put("tier", tier))
        val claimError = apiError(claim)
        if (claimError != null) {
            Logs.add(ctx, "farm", "Не удалось забрать яйцо $slug: $claimError", "bad")
            return@withLock false
        }

        Logs.add(ctx, "farm", "Яйцо забрано: $slug (tier $tier) — открываю", "ok")
        val opened = openFarmBox(ctx, boxId, slug)
        if (opened == null) {
            Logs.add(ctx, "farm", "Яйцо забрано, но открыть кейс $slug не удалось", "bad")
            return@withLock false
        }

        Logs.add(ctx, "farm", "Кейс открыт ✅" + (opened.second?.let { " → $it" } ?: ""), "ok")
        if (TokenStore.getBool("farmSellOpened") && opened.first != 0) {
            val sold = sellFarmItem(opened.first)
            if (sold) Logs.add(ctx, "farm", "Предмет продан ✅", "ok")
            else Logs.add(ctx, "farm", "Продажа предмета: не удалось", "bad")
        }
        true
    }

    // Возвращает (userItemId, имя предмета) или null при ошибке.
    private suspend fun openFarmBox(ctx: android.content.Context, boxId: Int, slug: String): Pair<Int, String?>? {
        val show = call("/v1/box/$slug/show", "GET")
        val showError = apiError(show)
        if (showError != null) {
            Logs.add(ctx, "farm", "Не удалось получить параметры кейса: $showError", "bad")
            return null
        }
        val shown = unwrap(show.data)
        val boxAmount = when {
            shown.has("price") -> shown.optInt("price", 0)
            shown.has("amount") -> shown.optInt("amount", 0)
            else -> shown.optInt("boxAmount", 0)
        }

        val body = JSONObject()
            .put("boxId", boxId)
            .put("count", 1)
            .put("duration", 5000)
            .put("isFree", true)
            .put("boxAmount", boxAmount)
        val result = call("/v1/box/open", "POST", body)
        if (apiError(result) != null) return null

        val data = unwrap(result.data)
        val won = data.optJSONObject("item") ?: data.optJSONObject("skin")
            ?: data.optJSONArray("items")?.optJSONObject(0)
        val name = won?.optString("name")?.ifBlank { null }
            ?: won?.optString("marketHashName")?.ifBlank { null }
            ?: won?.optString("localizationKey")?.ifBlank { null }
        val userItemId = won?.optInt("userItemId", won.optInt("id", 0)) ?: 0
        return Pair(userItemId, name)
    }

    private suspend fun sellFarmItem(userItemId: Int): Boolean {
        val body = JSONObject()
            .put("userItemIds", org.json.JSONArray().put(userItemId))
            .put("wishItemIds", org.json.JSONArray())
        val result = call("/marketplace/exchange-items", "POST", body)
        return apiError(result) == null
    }

    // ---------------- розыгрыши ----------------
    // Единая очередь для ручного участия, массового автоучастия и будильников.
    private val lotteryJoinMutex = Mutex()
    suspend fun joinLottery(ctx: android.content.Context, item: Lottery): Boolean =
        lotteryJoinMutex.withLock {
            try {
                performLotteryJoin(ctx, item)
            } finally {
                delay(100L)
            }
        }

    private suspend fun performLotteryJoin(ctx: android.content.Context, item: Lottery): Boolean {
        val r = call("/lottery/join", "POST", JSONObject().put("lotteryId", item.id))
        val d = r.data?.optJSONObject("data") ?: JSONObject()
        val ok: Boolean
        val msg: String
        when {
            r.unauthorized -> { ok = false; msg = "ошибка: $AUTH_MSG" }
            d.optString("error") == "CONDITION_ERROR" -> { ok = false; msg = "условия розыгрыша ещё не выполнены ⚠️" }
            r.status == 403 -> { ok = false; msg = "условия розыгрыша ещё не выполнены (403) ⚠️" }
            d.optBoolean("success", true) == false || d.has("error") -> { ok = false; msg = "ошибка: ${d.optString("error").ifBlank { "HTTP ${r.status}" }}" }
            r.status !in 0..399 || r.status == 0 -> { ok = false; msg = "ошибка: HTTP ${r.status}" }
            else -> { ok = true; msg = "участвую ✅" }
        }
        Logs.add(ctx, "raffles", "${item.name}: $msg", if (ok) "ok" else if (msg.contains("услов")) "" else "bad")
        return ok
    }
}
