package com.github.libretube.ui.models

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A personally configured HTTPS proxy. Never puts a Yandex Cloud key in the APK.
 * The user's proxy access token is encrypted at rest using Android Keystore.
 */
internal object AliceTranslationClient {
    private const val PREFS = "roottube_alice_translation"
    private const val KEY_ALIAS = "roottube_alice_proxy_token_v1"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token_v1"

    fun isConfigured(context: Context): Boolean {
        return load(context) != null
    }

    fun getBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, "").orEmpty()

    fun save(context: Context, baseUrl: String, token: String) {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "Требуется HTTPS" }
        val url = URL(normalized)
        require(url.protocol == "https" &&
            !url.host.isNullOrBlank() &&
            url.userInfo == null &&
            url.query == null &&
            url.ref == null
        ) { "Некорректный HTTPS-адрес" }
        require(token.length in 24..256 && token.none { it.isWhitespace() }) {
            "Токен доступа должен содержать 24–256 символов без пробелов"
        }

        val encrypted = encrypt(token)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, normalized)
            .putString(KEY_TOKEN, encrypted)
            .apply()
    }

    private fun load(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = prefs.getString(KEY_URL, null)?.takeIf { it.startsWith("https://") }
            ?: return null
        val sealed = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            base to decrypt(sealed)
        } catch (_: Exception) {
            // A backup restored to another phone cannot restore its Keystore key.
            null
        }
    }

    suspend fun translate(context: Context, sourceText: String): String =
        withContext(Dispatchers.IO) {
            val (baseUrl, token) = load(context)
                ?: error("Не настроен сервер Alice AI")
            val url = URL("$baseUrl/v1/translate")
            val conn = (url.openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 25_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                val body = JSONObject().put("text", sourceText.take(1800))
                    .toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    // Do not include server response bodies: they may contain user data.
                    error("Alice AI: HTTP $code")
                }
                val response = conn.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText().take(8_192) }
                JSONObject(response).getString("translation").trim().also {
                    require(it.isNotBlank()) { "Alice AI вернула пустой перевод" }
                }
            } finally {
                conn.disconnect()
            }
        }

    private fun key(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY_ALIAS, null)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        return "$iv:$ciphertext"
    }

    private fun decrypt(sealed: String): String {
        val parts = sealed.split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
        )
        return String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            Charsets.UTF_8
        )
    }
}
