package ru.cs2a.helper.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.cs2a.helper.util.TokenStore
import java.util.concurrent.TimeUnit

/**
 * Те же запросы, что шлёт сам сайт cs2a.run к API onemails.net — Authorization: JWT <токен>,
 * Origin/Referer как у сайта. Портировано из authedFetch() в background.js расширения.
 */
object ApiClient {
    const val ORIGIN = "https://cs2a.run"
    const val API = "https://onemails.net"
    const val AUTH_MSG = "нет авторизации (токен устарел) — войдите на cs2a.run заново в приложении"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class ApiResult(val status: Int, val data: JSONObject?, val unauthorized: Boolean, val error: String? = null)

    /** withBody=false + GET -> запрос совсем без тела (как сайт делает для обычных GET). */
    suspend fun call(ctx: Context, path: String, method: String = "POST", json: JSONObject? = JSONObject()): ApiResult =
        withContext(Dispatchers.IO) {
            val jwt = TokenStore.get(ctx)
                ?: return@withContext ApiResult(0, null, true, AUTH_MSG)
            try {
                val builder = Request.Builder()
                    .url(API + path)
                    .addHeader("Accept", "application/json, text/plain, */*")
                    .addHeader("Authorization", "JWT $jwt")
                    .addHeader("Origin", ORIGIN)
                    .addHeader("Referer", "$ORIGIN/")

                val body = json?.toString()?.toRequestBody("application/json".toMediaType())

                when (method.uppercase()) {
                    "GET" -> builder.get()
                    "POST" -> builder.post(body ?: "".toRequestBody(null))
                    else -> builder.method(method, body)
                }

                client.newCall(builder.build()).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    val data = try { JSONObject(text) } catch (e: Exception) { null }
                    val errMsg = data?.optString("error").takeUnless { it.isNullOrEmpty() }
                        ?: data?.optString("message").takeUnless { it.isNullOrEmpty() }
                    val unauthorized = res.code == 401 || (errMsg?.contains("unauthorized", true) == true)
                    ApiResult(res.code, data, unauthorized)
                }
            } catch (e: Exception) {
                ApiResult(0, null, false, friendly(e))
            }
        }

    suspend fun post(ctx: Context, path: String, json: JSONObject = JSONObject()) = call(ctx, path, "POST", json)
    suspend fun postNoBody(ctx: Context, path: String) = call(ctx, path, "POST", null)
    suspend fun get(ctx: Context, path: String) = call(ctx, path, "GET", null)

    fun friendly(e: Exception): String = e.message ?: e.toString()
}
