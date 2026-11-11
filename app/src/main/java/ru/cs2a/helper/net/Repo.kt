package ru.cs2a.helper.net

import android.content.Context
import org.json.JSONObject
import ru.cs2a.helper.util.LogStore
import ru.cs2a.helper.util.Prefs

/**
 * Ферма / кэшбек / розыгрыши — портировано 1:1 из farmEggAttempt(), cashbackAttempt(),
 * doLottery(), openFarmBox(), sellFarmItem() в background.js расширения.
 */
object Repo {

    private fun unwrap(o: JSONObject?): JSONObject {
        if (o == null) return JSONObject()
        val d = o.optJSONObject("data")
        return d ?: o
    }

    // ===================== ФЕРМА =====================
    suspend fun farmEggAttempt(ctx: Context): Boolean {
        val tier = Prefs.getInt(ctx, Prefs.K_FARM_TIER, Prefs.DEFAULT_FARM_TIER)
        val body = JSONObject().put("tier", tier)
        val r = ApiClient.post(ctx, "/v1/farm/eggs/daily/claim", body)
        if (r.error != null) {
            LogStore.add(ctx, "farm", "Яйцо: ошибка — ${r.error}", "bad")
            return false
        }
        val egg = unwrap(r.data)
        val err = if (r.unauthorized) ApiClient.AUTH_MSG
            else r.data?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: egg.optString("error")?.takeIf { it.isNotEmpty() }
                ?: if (r.data?.optBoolean("success", true) == false) "не удалось забрать" else null
        val ok = r.status < 400 && r.data?.optBoolean("success", true) != false && err == null
        if (!ok) {
            val hint = if (r.status == 422 || r.status == 400) " (возможно, выбрано не то яйцо — попробуйте другое в списке)" else ""
            LogStore.add(ctx, "farm", "Яйцо: ${err ?: "HTTP ${r.status}"}$hint", "bad")
            return false
        }
        LogStore.add(ctx, "farm", "Яйцо забрано ✅ (tier ${egg.optInt("tier")}, ${egg.optString("slug", egg.optString("boxId"))})", "ok")
        val boxId = egg.optInt("boxId", -1)
        val slug = egg.optString("slug", null)
        if (boxId > 0 && !slug.isNullOrEmpty()) {
            val opened = openFarmBox(ctx, boxId, slug)
            if (opened != null) {
                val name = opened.optString("name", opened.optString("marketHashName", opened.optString("market_hash_name", "")))
                LogStore.add(ctx, "farm", "Кейс открыт ✅" + (if (name.isNotEmpty()) " → $name" else ""), "ok")
                if (Prefs.getBool(ctx, Prefs.K_FARM_SELL)) {
                    val wonItemId = if (opened.has("userItemId")) opened.optLong("userItemId") else if (opened.has("id")) opened.optLong("id") else -1L
                    if (wonItemId > 0) {
                        val sold = sellFarmItem(ctx, wonItemId)
                        if (sold != null) LogStore.add(ctx, "farm", "Продано ✅ (баланс: $sold)", "ok")
                        else LogStore.add(ctx, "farm", "Продажа: не удалось продать", "bad")
                    }
                }
            } else {
                LogStore.add(ctx, "farm", "Кейс: не удалось открыть", "bad")
            }
        }
        return true
    }

    private suspend fun openFarmBox(ctx: Context, boxId: Int, slug: String): JSONObject? {
        var boxAmount = 0.0
        val show = ApiClient.get(ctx, "/v1/box/$slug/show")
        val b = unwrap(show.data)
        val v = when {
            b.has("price") -> b.optDouble("price")
            b.has("amount") -> b.optDouble("amount")
            b.has("boxAmount") -> b.optDouble("boxAmount")
            else -> Double.NaN
        }
        if (!v.isNaN()) boxAmount = v
        val body = JSONObject()
            .put("boxId", boxId).put("count", 1).put("duration", 5000)
            .put("isFree", true).put("boxAmount", boxAmount)
        val r = ApiClient.post(ctx, "/v1/box/open", body)
        if (r.unauthorized || r.status >= 400 || r.data?.optBoolean("success", true) == false) return null
        val it = unwrap(r.data)
        val won = it.optJSONObject("item") ?: it.optJSONObject("skin")
            ?: it.optJSONArray("items")?.optJSONObject(0)
        return won
    }

    private suspend fun sellFarmItem(ctx: Context, userItemId: Long): String? {
        val ids = org.json.JSONArray().put(userItemId)
        val body = JSONObject().put("userItemIds", ids).put("wishItemIds", org.json.JSONArray())
        val r = ApiClient.post(ctx, "/marketplace/exchange-items", body)
        if (r.unauthorized || r.status >= 400 || r.data?.optBoolean("success", true) == false) return null
        return unwrap(r.data).optString("balance", "?")
    }

    // ===================== КЭШБЕК =====================
    private val CASHBACK_REASONS = mapOf("lastWeekDeposit" to "нужен депозит за последнюю неделю")

    suspend fun cashbackAttempt(ctx: Context): Boolean {
        val r = ApiClient.postNoBody(ctx, "/cashback")
        if (r.error != null) {
            LogStore.add(ctx, "farm", "Кэшбек: ошибка — ${r.error}", "bad")
            return false
        }
        val data = r.data?.optJSONObject("data") ?: JSONObject()
        val conditions = data.optJSONArray("errorConditions")
        val conditionsList = mutableListOf<String>()
        if (conditions != null) for (i in 0 until conditions.length()) conditionsList.add(conditions.optString(i))
        val win = data.optJSONObject("winCashback")
        val err = if (r.unauthorized) ApiClient.AUTH_MSG
            else r.data?.optString("error")?.takeIf { it.isNotEmpty() }
                ?: if (conditionsList.isNotEmpty()) conditionsList.joinToString(", ") { CASHBACK_REASONS[it] ?: it } else null
        val ok = r.status < 400 && r.data?.optBoolean("success", true) != false && err == null
        when {
            !ok -> LogStore.add(ctx, "farm", "Кэшбек: ${err ?: "условия не выполнены"}", "bad")
            win != null -> {
                val reward = win.optDouble("reward", 0.0)
                val sym = win.optString("currencySymbol", "$")
                if (reward > 0) LogStore.add(ctx, "farm", "Кэшбек получен ✅ +$reward$sym", "ok")
                else LogStore.add(ctx, "farm", "Кэшбек: сегодня получать нечего (условия пока не выполнены)", "")
            }
            conditionsList.isNotEmpty() -> LogStore.add(ctx, "farm", "Кэшбек: ${conditionsList.joinToString(", ") { CASHBACK_REASONS[it] ?: it }}", "")
            else -> LogStore.add(ctx, "farm", "Кэшбек: запрос прошёл, но ответ неожиданного вида", "")
        }
        return ok
    }

    // ===================== РОЗЫГРЫШИ =====================
    suspend fun lotteryAttempt(ctx: Context, id: Int): Boolean {
        val item = LOTTERIES.find { it.id == id }
        val name = item?.name ?: "#$id"
        val r = ApiClient.post(ctx, "/lottery/join", JSONObject().put("lotteryId", id))
        val d = r.data?.optJSONObject("data") ?: JSONObject()
        val errField = d.optString("error", null)
        val msg: String
        val ok: Boolean
        when {
            r.error != null -> { msg = "ошибка — ${r.error}"; ok = false }
            r.unauthorized -> { msg = "ошибка: ${ApiClient.AUTH_MSG}"; ok = false }
            errField == "CONDITION_ERROR" -> { msg = "условия розыгрыша ещё не выполнены ⚠️"; ok = false }
            d.optBoolean("success", true) == false || !errField.isNullOrEmpty() -> {
                msg = "ошибка: ${if (!errField.isNullOrEmpty()) errField else "HTTP ${r.status}"}"; ok = false
            }
            r.status >= 400 -> { msg = "ошибка: HTTP ${r.status}"; ok = false }
            else -> { msg = "участвую ✅"; ok = true }
        }
        val level = if (ok) "ok" else if (msg.contains("услов")) "" else "bad"
        LogStore.add(ctx, "raffles", "$name: $msg", level)
        return ok
    }
}
