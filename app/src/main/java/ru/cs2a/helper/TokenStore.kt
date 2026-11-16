package ru.cs2a.helper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

// Хранит JWT (токен API onemails.net) и флаги/настройки — аналог chrome.storage.local
// из расширения. Токен шифруется (EncryptedSharedPreferences), остальное — обычные prefs.
object TokenStore {
    private lateinit var secure: SharedPreferences
    private lateinit var plain: SharedPreferences
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        val appCtx = ctx.applicationContext
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        secure = EncryptedSharedPreferences.create(
            appCtx, "cs2a_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        plain = appCtx.getSharedPreferences("cs2a_prefs", Context.MODE_PRIVATE)
        ready = true
    }

    fun saveJwt(token: String) {
        val payload = decodeJwtPayload(token)
        val user = payload?.optJSONObject("user") ?: payload?.optJSONObject("profile")
        val userId = claim(payload, user, arrayOf("id", "userId", "user_id", "sub"))
        val username = claim(payload, user, arrayOf("username", "userName", "login", "nickname", "preferred_username", "displayName", "name"))
        val editor = secure.edit()
            .putString("jwt", token)
            .putLong("jwtExp", (payload?.optDouble("exp", 0.0)?.times(1000))?.toLong() ?: 0L)
            .putString("csgorunUserId", userId)
            .putString("csgorunUsername", username?.take(80) ?: "")
        if (userId != csgorunUserId()) editor.remove("csgorunLinkedUserId")
        editor.apply()
    }

    private fun claim(root: JSONObject?, nested: JSONObject?, names: Array<String>): String? {
        for (name in names) {
            val value = root?.opt(name)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotBlank() && text != "0" && text != "false") return text
            }
            val nestedValue = nested?.opt(name)
            if (nestedValue != null && nestedValue != JSONObject.NULL) {
                val text = nestedValue.toString().trim()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    fun csgorunUserId(): String? = if (ready) secure.getString("csgorunUserId", null) else null
    fun csgorunUsername(): String = if (ready) secure.getString("csgorunUsername", "") ?: "" else ""
    fun markCsgorunLinked(userId: String) { if (ready) secure.edit().putString("csgorunLinkedUserId", userId).apply() }
    fun csgorunLinkedUserId(): String? = if (ready) secure.getString("csgorunLinkedUserId", null) else null

    fun jwt(): String? = if (ready) secure.getString("jwt", null) else null
    fun jwtExp(): Long = if (ready) secure.getLong("jwtExp", 0L) else 0L
    fun isApiTokenValid(): Boolean {
        val t = jwt() ?: return false
        val exp = jwtExp()
        return t.isNotBlank() && exp > System.currentTimeMillis() + 60_000
    }
    fun clearJwt() { if (ready) secure.edit().remove("jwt").remove("jwtExp").apply() }

    fun decodeJwtPayload(token: String): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return null
            var p = parts[1].replace('-', '+').replace('_', '/')
            while (p.length % 4 != 0) p += "="
            val bytes = android.util.Base64.decode(p, android.util.Base64.DEFAULT)
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) { null }
    }

    fun getBool(key: String, def: Boolean = false): Boolean = plain.getBoolean(key, def)
    fun setBool(key: String, v: Boolean) { plain.edit().putBoolean(key, v).apply() }
    fun getInt(key: String, def: Int = 0): Int = plain.getInt(key, def)
    fun setInt(key: String, v: Int) { plain.edit().putInt(key, v).apply() }
    fun getString(key: String, def: String = ""): String = plain.getString(key, def) ?: def
    fun setString(key: String, v: String) { plain.edit().putString(key, v).apply() }
    fun saveSecret(key: String, value: String) { if (ready) secure.edit().putString(key, value).apply() }
    fun getSecret(key: String): String = if (ready) secure.getString(key, "") ?: "" else ""
}
