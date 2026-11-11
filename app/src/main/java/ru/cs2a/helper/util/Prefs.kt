package ru.cs2a.helper.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Обычные (не секретные) настройки — аналог chrome.storage.local для тумблеров/текста. */
object Prefs {
    private const val FILE = "cs2a_prefs"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String, def: Boolean = false) = p(ctx).getBoolean(key, def)
    fun setBool(ctx: Context, key: String, value: Boolean) { p(ctx).edit().putBoolean(key, value).apply() }

    fun getString(ctx: Context, key: String, def: String = "") = p(ctx).getString(key, def) ?: def
    fun setString(ctx: Context, key: String, value: String) { p(ctx).edit().putString(key, value).apply() }

    fun getInt(ctx: Context, key: String, def: Int) = p(ctx).getInt(key, def)
    fun setInt(ctx: Context, key: String, value: Int) { p(ctx).edit().putInt(key, value).apply() }

    // --- тумблеры ---
    const val K_FARM_AUTO = "farmAuto"
    const val K_FARM_SELL = "farmSellOpened"
    const val K_FARM_TIER = "farmTier"
    const val K_CASHBACK_AUTO = "cashbackAuto"
    const val K_RAFFLES_AUTO = "rafflesAuto"
    const val K_SPAM_TEXT = "spamText"
    const val K_SPAM_DELAY = "spamDelay"
    const val DEFAULT_FARM_TIER = 6
}

/** Журнал (аналог общего `logs` в background.js) — общий для фермы/кэшбека/розыгрышей/спама. */
object LogStore {
    private const val FILE = "cs2a_logs"
    private const val KEY = "entries"
    private const val MAX = 200
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Entry(val t: Long, val tab: String, val text: String, val level: String)

    @Synchronized
    fun add(ctx: Context, tab: String, text: String, level: String = "") {
        val arr = readAll(ctx).toMutableList()
        arr.add(Entry(System.currentTimeMillis(), tab, text, level))
        while (arr.size > MAX) arr.removeAt(0)
        writeAll(ctx, arr)
    }

    fun readAll(ctx: Context): List<Entry> {
        val raw = p(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Entry(o.optLong("t"), o.optString("tab"), o.optString("text"), o.optString("level"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeAll(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("t", e.t); o.put("tab", e.tab); o.put("text", e.text); o.put("level", e.level)
            a.put(o)
        }
        p(ctx).edit().putString(KEY, a.toString()).apply()
    }
}
