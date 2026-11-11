package ru.cs2a.helper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Общий журнал (аналог background.js log()/L.get('logs')) — хранится в обычных prefs как JSON-массив,
// общий для вкладок farm / raffles / spam.
object Logs {
    private const val KEY = "logs"
    private const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(ctx: Context, tab: String, text: String, level: String = "") {
        val arr = readAll(ctx)
        val o = JSONObject()
        o.put("t", System.currentTimeMillis())
        o.put("tab", tab)
        o.put("text", text)
        o.put("level", level)
        arr.put(o)
        while (arr.length() > MAX) arr.remove(0)
        TokenStore.setString(KEY, arr.toString())
    }

    private fun readAll(ctx: Context): JSONArray {
        val s = TokenStore.getString(KEY, "[]")
        return try { JSONArray(s) } catch (e: Exception) { JSONArray() }
    }

    fun render(ctx: Context, tab: String, maxLines: Int = 60): String {
        val arr = readAll(ctx)
        val sb = StringBuilder()
        val items = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("tab") == tab) items.add(o)
        }
        val from = maxOf(0, items.size - maxLines)
        for (i in items.size - 1 downTo from) {
            val o = items[i]
            sb.append(fmt.format(Date(o.optLong("t"))))
            sb.append("  ")
            sb.append(o.optString("text"))
            sb.append("\n")
        }
        return sb.toString().trim()
    }
}
