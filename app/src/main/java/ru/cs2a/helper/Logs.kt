package ru.cs2a.helper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Append-only журнал в JSONL: история не обрезается и не раздувает SharedPreferences.
object Logs {
    private const val KEY = "logs"
    private const val MIGRATED_KEY = "logsMigratedToFile"
    private const val FILE_NAME = "logs.jsonl"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private const val DISPLAY_LIMIT = 12
    private val recentByTab = HashMap<String, ArrayDeque<String>>()
    private var cacheLoaded = false

    private fun loadRecentCache(ctx: Context) {
        if (cacheLoaded) return
        ensureMigrated(ctx)
        val file = File(ctx.filesDir, FILE_NAME)
        if (file.exists()) file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) try {
                val item = JSONObject(line)
                val tab = item.optString("tab")
                val queue = recentByTab.getOrPut(tab) { ArrayDeque() }
                if (queue.size == DISPLAY_LIMIT) queue.removeFirst()
                queue.addLast(fmt.format(Date(item.optLong("t"))) + "  " + item.optString("text"))
            } catch (_: Exception) { }
        }
        cacheLoaded = true
    }

    @Synchronized
    private fun ensureMigrated(ctx: Context) {
        if (TokenStore.getBool(MIGRATED_KEY)) return
        val legacy = try { JSONArray(TokenStore.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        if (legacy.length() > 0) {
            FileOutputStream(File(ctx.filesDir, FILE_NAME), true).bufferedWriter(Charsets.UTF_8).use { writer ->
                for (i in 0 until legacy.length()) {
                    val item = legacy.optJSONObject(i) ?: continue
                    writer.append(item.toString()).append('\n')
                }
            }
        }
        TokenStore.setString(KEY, "")
        TokenStore.setBool(MIGRATED_KEY, true)
    }

    @Synchronized
    fun add(ctx: Context, tab: String, text: String, level: String = "") {
        ensureMigrated(ctx)
        val item = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("tab", tab)
            .put("text", text)
            .put("level", level)
        FileOutputStream(File(ctx.filesDir, FILE_NAME), true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(item.toString()).append('\n')
        }
        if (cacheLoaded) {
            val queue = recentByTab.getOrPut(tab) { ArrayDeque() }
            if (queue.size == DISPLAY_LIMIT) queue.removeFirst()
            queue.addLast(fmt.format(Date(item.optLong("t"))) + "  " + text)
        }
    }

    @Synchronized
    fun render(ctx: Context, tab: String): String {
        loadRecentCache(ctx)
        return recentByTab[tab]?.toList()?.asReversed()?.joinToString("\n").orEmpty()
    }
}
