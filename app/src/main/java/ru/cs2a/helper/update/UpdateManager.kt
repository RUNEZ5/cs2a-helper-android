package ru.cs2a.helper.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.cs2a.helper.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit

// Проверка обновлений через GitHub Releases: workflow сборки (.github/workflows/build.yml)
// после каждой сборки публикует релиз с тегом "v<номер_сборки>" и прикреплённым .apk; номер
// сборки совпадает с versionCode самого приложения (прокинут в build.gradle.kts через
// -PappVersionCode). "latest" у GitHub — это просто самый свежий непомеченный как pre-release
// релиз, поэтому отдельный "rolling"-тег не нужен.
object UpdateManager {
    private const val REPO = "RUNEZ5/cs2a-helper-android"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val notes: String)

    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val text = res.body?.string() ?: return@withContext null
                val o = JSONObject(text)
                val tag = o.optString("tag_name")
                val code = tag.removePrefix("v").toIntOrNull() ?: return@withContext null
                if (code <= BuildConfig.VERSION_CODE) return@withContext null
                val assets = o.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
                }
                if (apkUrl == null) return@withContext null
                UpdateInfo(code, o.optString("name", tag), apkUrl, o.optString("body", ""))
            }
        } catch (e: Exception) { null }
    }

    fun downloadAndInstall(ctx: Context, info: UpdateInfo, onQueued: () -> Unit = {}) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dir = ctx.getExternalFilesDir("updates") ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "cs2a-helper-update.apk")
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("CS2A Помощник — обновление ${info.versionName}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)

        val downloadId = dm.enqueue(request)
        onQueued()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                try { ctx.applicationContext.unregisterReceiver(this) } catch (e: Exception) {}
                if (!file.exists()) return
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.applicationContext.startActivity(installIntent)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.applicationContext.registerReceiver(receiver, filter)
        }
    }
}
