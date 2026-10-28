package com.github.libretube.voiceover

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Experimental unofficial Yandex VOT client, adapted from the MIT-licensed
 * VOT Player by Alexandr Motologa. See third_party/vot-android-player-LICENSE.txt.
 *
 * Proxies are opt-in via the RootTube UI. Video URLs are sent to the selected VOT host.
 * Never attach YouTube credentials/cookies to these requests.
 */
internal class VotClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Same backend order as the working VOT Player reference implementation.
    private val hosts = listOf(
        "vot-worker.vtrans.eu.cc",
        "vot-worker.eu.cc",
        "vot-worker.toil.cc",
        "api.browser.yandex.ru"
    )
    private var hostIndex = 0
    private val host: String get() = hosts[hostIndex]
    private var session: Session? = null

    private data class Session(val uuid: String, val secret: String, val expiresAt: Long)

    private fun rotateHost() {
        hostIndex = (hostIndex + 1) % hosts.size
        session = null
    }

    private fun sign(bytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(VTRANS_KEY.toByteArray(), "HmacSHA256"))
        return mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun uuid(): String = ByteArray(16).also {
        SecureRandom().nextBytes(it)
    }.joinToString("") { "%02x".format(it) }

    private fun headerJson(vararg pairs: Pair<String, String>): String =
        JSONObject(mapOf(*pairs)).toString()

    private fun request(
        path: String,
        body: ByteArray,
        session: Session? = null,
        method: String = "POST"
    ): ByteArray {
        val protobuf = "application/x-protobuf"
        val builder = Request.Builder().url("https://$host$path")
        if (session != null) {
            val token = "${session.uuid}:$path:$VERSION"
            val signature = sign(token.toByteArray())
            val common = arrayOf(
                "Accept" to protobuf,
                "Content-Type" to protobuf,
                "User-Agent" to USER_AGENT,
                "Sec-Vtrans-Token" to "$signature:$token",
                "Sec-Vtrans-Sk" to session.secret,
                "Vtrans-Signature" to sign(body)
            )
            if (host == DIRECT_HOST) {
                common.forEach { (k, v) -> builder.header(k, v) }
            } else {
                builder.header("User-Agent", "vot.js/3.1.2")
                    .header("Content-Type", protobuf)
                    .header("X-VOT-Headers", Base64.encodeToString(
                        headerJson(*common).toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                    ))
            }
        } else {
            val common = arrayOf(
                "Accept" to protobuf,
                "Content-Type" to protobuf,
                "User-Agent" to USER_AGENT,
                "Vtrans-Signature" to sign(body)
            )
            if (host == DIRECT_HOST) {
                common.forEach { (k, v) -> builder.header(k, v) }
            } else {
                builder.header("User-Agent", "vot.js/3.1.2")
                    .header("Content-Type", protobuf)
                    .header("X-VOT-Headers", Base64.encodeToString(
                        headerJson(*common).toByteArray(Charsets.UTF_8), Base64.NO_WRAP
                    ))
            }
        }

        val requestBody = body.toRequestBody(protobuf.toMediaType())
        if (method == "PUT") builder.put(requestBody) else builder.post(requestBody)
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "VOT HTTP ${response.code}" }
            return response.body?.bytes() ?: error("Empty VOT response")
        }
    }

    private fun session(): Session {
        val now = System.currentTimeMillis()
        session?.takeIf { it.expiresAt > now + 60_000L }?.let { return it }
        val id = uuid()
        val (secret, ttlSeconds) = VotProtocol.sessionResponse(
            request("/session/create", VotProtocol.sessionRequest(id))
        )
        check(secret.isNotEmpty() && ttlSeconds > 0) { "VOT session unavailable" }
        return Session(id, secret, now + ttlSeconds * 1000L).also { session = it }
    }

    suspend fun translate(videoId: String, durationSeconds: Double, title: String): String =
        withContext(Dispatchers.IO) {
            require(Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) { "Invalid YouTube video ID" }
            val videoUrl = "https://youtu.be/$videoId"
            var lastError: Exception = IllegalStateException("Translation did not become ready")
            var audioRequested = false
            var translationId = ""

            repeat(32) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    val active = session()
                    val data = request(
                        "/video-translation/translate",
                        VotProtocol.translationRequest(videoUrl, durationSeconds, title),
                        active
                    )
                    val result = VotProtocol.translationResponse(data)
                    if (result.translationId.isNotBlank()) translationId = result.translationId
                    if (result.ready) {
                        val url = result.audioUrl!!
                        require(url.startsWith("https://")) { "Unexpected VOT audio URL" }
                        return@withContext url
                    }
                    when {
                        result.audioRequested && !audioRequested -> {
                            audioRequested = true
                            // Requests remote processing for sources not present in the cache.
                            // Some videos also require real source audio upload; this may not be enough.
                            request(
                                "/video-translation/audio",
                                VotProtocol.emptyAudioRequest(videoUrl, translationId, videoId),
                                active,
                                "PUT"
                            )
                        }
                        result.status == 0 || result.status == 7 -> {
                            lastError = IllegalStateException(result.message ?: "VOT status ${result.status}")
                            rotateHost()
                        }
                        else -> {
                            lastError = IllegalStateException(
                                result.message ?: "Translation pending (status ${result.status})"
                            )
                        }
                    }
                    delay((result.remainingSeconds.takeIf { it > 0 } ?: 4).coerceIn(3, 8) * 1000L)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    lastError = e
                    rotateHost()
                    delay(if (attempt < hosts.size) 1000L else 2500L)
                }
            }
            throw IllegalStateException(
                "VOT translation unavailable: ${lastError.message ?: "unknown error"}",
                lastError
            )
        }

    companion object {
        private const val DIRECT_HOST = "api.browser.yandex.ru"
        // Public compatibility key from the third-party VOT Player source, not a RootTube secret.
        private const val VTRANS_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf"
        private const val VERSION = "26.8.3.971"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 YaBrowser/26.8.0.0 Safari/537.36"
    }
}
