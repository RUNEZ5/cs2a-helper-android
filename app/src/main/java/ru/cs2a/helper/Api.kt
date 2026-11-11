package ru.cs2a.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Повторяет authedFetch() из background.js: те же запросы, что шлёт сам сайт,
// с заголовком Authorization: JWT <токен>. Если токен протух (401/Unauthorized) и обновить
// его негде (нет живой вкладки, как в расширении) — просим пользователя перезайти в приложении.
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

    // ---------------- ферма ----------------
    suspend fun farmEggAttempt(ctx: android.content.Context): Boolean {
        val tier = TokenStore.getInt("farmTier", DEFAULT_FARM_TIER)
        val r = call("/v1/farm/eggs/daily/claim", "POST", JSONObject().put("tier", tier))
        if (r.error != null && r.status == 0) { Logs.add(ctx, "farm", "Яйцо: ошибка — ${r.error}", "bad"); return false }
        val body = r.data
        val egg = unwrap(body)
        val err = when {
            r.unauthorized -> AUTH_MSG
            body?.optBoolean("success", true) == false -> body.optString("error").ifBlank { "не удалось забрать" }
            egg.has("error") -> egg.optString("error")
            else -> null
        }
        val ok = r.status in 0..399 && r.status != 0 && err == null
        if (!ok) {
            Logs.add(ctx, "farm", "Яйцо: ${err ?: "HTTP ${r.status}"}", "bad")
            return false
        }
        Logs.add(ctx, "farm", "Яйцо забрано ✅ (tier ${egg.optInt("tier")}, ${egg.optString("slug")})", "ok")
        val boxId = egg.optInt("boxId", 0)
        val slug = egg.optString("slug", "")
        if (boxId != 0 && slug.isNotBlank()) {
            val opened = openFarmBox(boxId, slug)
            if (opened != null) {
                Logs.add(ctx, "farm", "Кейс открыт ✅" + (opened.second?.let { " → $it" } ?: ""), "ok")
                if (TokenStore.getBool("farmSellOpened") && opened.first != 0) {
                    val s = sellFarmItem(opened.first)
                    if (s) Logs.add(ctx, "farm", "Продано ✅", "ok") else Logs.add(ctx, "farm", "Продажа: не удалось", "bad")
                }
            } else {
                Logs.add(ctx, "farm", "Кейс: не удалось открыть", "bad")
            }
        }
        return true
    }

    // возвращает (userItemId, имя предмета) или null при ошибке
    private suspend fun openFarmBox(boxId: Int, slug: String): Pair<Int, String?>? {
        var boxAmount = 0
        val show = call("/v1/box/$slug/show", "GET")
        val shown = unwrap(show.data)
        if (shown.has("price")) boxAmount = shown.optInt("price", 0)
        else if (shown.has("amount")) boxAmount = shown.optInt("amount", 0)
        else if (shown.has("boxAmount")) boxAmount = shown.optInt("boxAmount", 0)

        val body = JSONObject().put("boxId", boxId).put("count", 1).put("duration", 5000).put("isFree", true).put("boxAmount", boxAmount)
        val r = call("/v1/box/open", "POST", body)
        if (r.status !in 0..399 || r.status == 0 || r.unauthorized) return null
        val it = unwrap(r.data)
        val won = it.optJSONObject("item") ?: it.optJSONObject("skin")
            ?: it.optJSONArray("items")?.optJSONObject(0)
        val name = won?.optString("name")?.ifBlank { null }
            ?: won?.optString("marketHashName")?.ifBlank { null }
            ?: won?.optString("localizationKey")?.ifBlank { null }
        val userItemId = won?.optInt("userItemId", won.optInt("id", 0)) ?: 0
        return Pair(userItemId, name)
    }

    private suspend fun sellFarmItem(userItemId: Int): Boolean {
        val body = JSONObject().put("userItemIds", org.json.JSONArray().put(userItemId)).put("wishItemIds", org.json.JSONArray())
        val r = call("/marketplace/exchange-items", "POST", body)
        return r.status in 0..399 && r.status != 0 && !r.unauthorized
    }

    // ---------------- кэшбек ----------------
    suspend fun cashbackAttempt(ctx: android.content.Context): Boolean {
        val r = call("/cashback", "POST")
        if (r.error != null && r.status == 0) { Logs.add(ctx, "farm", "Кэшбек: ошибка — ${r.error}", "bad"); return false }
        val body = r.data
        val data = body?.optJSONObject("data")
        val win = data?.optJSONObject("winCashback")
        val err = when {
            r.unauthorized -> AUTH_MSG
            body?.optBoolean("success", true) == false -> body.optString("error").ifBlank { null }
            else -> null
        }
        val ok = r.status in 0..399 && r.status != 0 && err == null
        if (!ok) {
            Logs.add(ctx, "farm", "Кэшбек: ${err ?: "условия не выполнены"}", "bad")
            return false
        }
        if (win != null) {
            val reward = win.optDouble("reward", 0.0)
            val sym = win.optString("currencySymbol", "$")
            if (reward > 0) Logs.add(ctx, "farm", "Кэшбек получен ✅ +$reward$sym", "ok")
            else Logs.add(ctx, "farm", "Кэшбек: сегодня получать нечего (условия пока не выполнены)", "")
        } else {
            Logs.add(ctx, "farm", "Кэшбек: запрос прошёл, но ответ неожиданного вида", "")
        }
        return true
    }

    // ---------------- розыгрыши ----------------
    suspend fun joinLottery(ctx: android.content.Context, item: Lottery): Boolean {
        val r = call("/lottery/join", "POST", JSONObject().put("lotteryId", item.id))
        val d = r.data?.optJSONObject("data") ?: JSONObject()
        val ok: Boolean
        val msg: String
        when {
            r.unauthorized -> { ok = false; msg = "ошибка: $AUTH_MSG" }
            d.optString("error") == "CONDITION_ERROR" -> { ok = false; msg = "условия розыгрыша ещё не выполнены ⚠️" }
            // 403 от сайта на этот эндпоинт в наблюдаемых случаях означает именно
            // «условия/лимит участия ещё не выполнены», а не ошибку авторизации.
            r.status == 403 -> { ok = false; msg = "условия розыгрыша ещё не выполнены (403) ⚠️" }
            d.optBoolean("success", true) == false || d.has("error") -> { ok = false; msg = "ошибка: ${d.optString("error").ifBlank { "HTTP ${r.status}" }}" }
            r.status !in 0..399 || r.status == 0 -> { ok = false; msg = "ошибка: HTTP ${r.status}" }
            else -> { ok = true; msg = "участвую ✅" }
        }
        Logs.add(ctx, "raffles", "${item.name}: $msg", if (ok) "ok" else if (msg.contains("услов")) "" else "bad")
        return ok
    }
}
