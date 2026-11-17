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
    fun exportArchive(ctx: Context, appVersion: String): File {
        ensureMigrated(ctx)
        val source = File(ctx.filesDir, FILE_NAME)
        val dir = File(ctx.cacheDir, "log-export").apply { mkdirs() }
        val archive = File(dir, "cs2a-diagnostics-${System.currentTimeMillis()}.zip")
        val jwtPattern = Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
        val bearerPattern = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+=*""")
        val secretPattern = Regex("""(?i)("(?:api[_-]?hash|authorization|access[_-]?token|refresh[_-]?token|jwt)"\s*:\s*")[^"]*(")""")
        java.util.zip.ZipOutputStream(FileOutputStream(archive).buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("logs.jsonl"))
            if (source.exists()) {
                source.forEachLine(Charsets.UTF_8) { line ->
                    var safe = jwtPattern.replace(line, "[REDACTED]")
                    safe = bearerPattern.replace(safe) { it.groupValues[1] + "[REDACTED]" }
                    safe = secretPattern.replace(safe) { it.groupValues[1] + "[REDACTED]" + it.groupValues[2] }
                    zip.write(safe.toByteArray(Charsets.UTF_8))
                    zip.write(10)
                }
            } else {
                zip.write("Лог пока пуст\n".toByteArray(Charsets.UTF_8))
            }
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("report.txt"))
            val generated = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
            val report = "CS2A Helper diagnostics\nApp version: $appVersion\nGenerated: $generated\nLogs file: logs.jsonl\n"
            zip.write(report.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return archive
    }

    @Synchronized
    fun render(ctx: Context, tab: String): String {
        loadRecentCache(ctx)
        return recentByTab[tab]?.toList()?.asReversed()?.joinToString("\n").orEmpty()
    }
}
