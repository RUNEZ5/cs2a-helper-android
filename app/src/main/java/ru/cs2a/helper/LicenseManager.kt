package ru.cs2a.helper

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LicenseResponse(
    val valid: Boolean,
    val token: String? = null,
    val message: String = "",
    val networkError: Boolean = false
)

object LicenseManager {
    private const val BASE_URL = "https://cs2a-license-server.smirnov-pashaqw.workers.dev"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private fun deviceId(context: Context): String {
        val existing = TokenStore.getSecret("licenseDeviceId")
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        TokenStore.saveSecret("licenseDeviceId", created)
        return created
    }

    suspend fun activate(context: Context, key: String): LicenseResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("key", key)
            .put("deviceId", deviceId(context))
            .put("deviceLabel", listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").take(80))
        post("/api/activate", payload)
    }

    suspend fun validate(context: Context, token: String): LicenseResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("token", token)
            .put("deviceId", deviceId(context))
        post("/api/validate", payload)
    }

    suspend fun linkCsgorunAccount(context: Context): LicenseResponse = withContext(Dispatchers.IO) {
        val userId = TokenStore.csgorunUserId()
            ?: return@withContext LicenseResponse(false, message = "Не удалось определить ID аккаунта CSGORun из JWT.")
        val licenseToken = TokenStore.getSecret("licenseToken")
        if (licenseToken.isBlank()) return@withContext LicenseResponse(false, message = "Сначала активируйте лицензию.")
        val payload = JSONObject()
            .put("licenseToken", licenseToken)
            .put("deviceId", deviceId(context))
            .put("userId", userId)
            .put("username", TokenStore.csgorunUsername())
        val result = post("/api/account/link", payload)
        if (result.valid) TokenStore.markCsgorunLinked(userId)
        result
    }

    private fun post(path: String, payload: JSONObject): LicenseResponse {
        val request = Request.Builder()
            .url(BASE_URL + path)
            .post(payload.toString().toRequestBody(mediaType))
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                if (response.isSuccessful && json.optBoolean("ok", json.optBoolean("valid", false))) {
                    LicenseResponse(
                        valid = true,
                        token = json.optString("token").takeIf { it.isNotBlank() }
                    )
                } else if (response.code >= 500 || response.code == 408 || response.code == 429) {
                    LicenseResponse(false, message = "Сервер временно недоступен.", networkError = true)
                } else {
                    val error = json.optString("error", "license_error")
                    LicenseResponse(false, message = when (error) {
                        "key_unavailable" -> "Ключ недействителен, уже использован или заблокирован."
                        "invalid_key" -> "Проверьте правильность введённого ключа."
                        "user_banned" -> "Этот аккаунт CSGORun заблокирован администратором."
                        "license_already_linked_to_another_account" -> "Эта лицензия уже привязана к другому аккаунту CSGORun."
                        else -> "Не удалось подтвердить лицензию ($error)."
                    })
                }
            }
        } catch (_: Exception) {
            LicenseResponse(false, message = "Нет соединения с сервером лицензий.", networkError = true)
        }
    }
}
