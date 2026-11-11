package ru.cs2a.helper.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONObject

/**
 * Хранилище JWT API-токена (аналог chrome.storage.local { jwt, jwtExp, jwtIat } из background.js).
 * Токен API cs2a.run (onemails.net) — это {id, iat, exp}; токен WebSocket (без exp) сюда не попадает.
 */
object TokenStore {
    private const val FILE = "cs2a_secure"
    private const val K_JWT = "jwt"
    private const val K_EXP = "jwt_exp"
    private const val K_IAT = "jwt_iat"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            FILE, masterKeyAlias, ctx.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class Payload(val id: Any?, val iat: Long, val exp: Long)

    /** Декодирует payload JWT (вторая часть, base64url). Возвращает null, если это не JWT. */
    fun decode(token: String): Payload? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            var p = parts[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            val bytes = Base64.decode(p, Base64.DEFAULT)
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            val iat = if (obj.has("iat")) obj.optLong("iat") else 0L
            val exp = if (obj.has("exp")) obj.optLong("exp") else return null
            val id = if (obj.has("id")) obj.get("id") else return null
            Payload(id, iat, exp)
        } catch (e: Exception) { null }
    }

    /** true если токен — это токен API (id+exp) и ещё не истёк (с запасом в минуту). */
    fun isApiToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val p = decode(token) ?: return false
        return p.exp * 1000 > System.currentTimeMillis() + 60_000
    }

    fun save(ctx: Context, token: String): Boolean {
        val p = decode(token) ?: return false
        if (p.exp <= 0) return false
        val cur = prefs(ctx)
        val curIat = cur.getLong(K_IAT, 0)
        val curJwt = cur.getString(K_JWT, null)
        if (curJwt == token) return true
        // не затираем более новый уже сохранённый токен более старым
        if (!curJwt.isNullOrEmpty() && isApiToken(curJwt) && curIat > 0 && p.iat > 0 && p.iat < curIat) return true
        cur.edit().putString(K_JWT, token).putLong(K_EXP, p.exp * 1000).putLong(K_IAT, p.iat).apply()
        return true
    }

    fun get(ctx: Context): String? {
        val t = prefs(ctx).getString(K_JWT, null)
        return if (isApiToken(t)) t else null
    }

    fun expiresAt(ctx: Context): Long = prefs(ctx).getLong(K_EXP, 0)

    fun hasAny(ctx: Context): Boolean = !prefs(ctx).getString(K_JWT, null).isNullOrEmpty()

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
}
