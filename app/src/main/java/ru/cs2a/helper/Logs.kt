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
    }

    @Synchronized
    private fun readAll(ctx: Context): List<JSONObject> {
        ensureMigrated(ctx)
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val result = ArrayList<JSONObject>()
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.isNotBlank()) {
                try { result.add(JSONObject(line)) } catch (_: Exception) { }
            }
        }
        return result
    }

    fun render(ctx: Context, tab: String): String {
        val items = readAll(ctx).filter { it.optString("tab") == tab }
        return items.asReversed().joinToString("\n") { item ->
            fmt.format(Date(item.optLong("t"))) + "  " + item.optString("text")
        }
    }
}
