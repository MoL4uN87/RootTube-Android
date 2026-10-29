package com.github.libretube.voiceover

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
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
        .writeTimeout(45, TimeUnit.SECONDS)
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

    /**
     * Reuses the public VOT Player request headers for both direct and relay hosts.
     * The fail-audio-js endpoint requires JSON *without* Vtrans signature headers.
     */
    private fun request(
        path: String,
        body: ByteArray,
        session: Session? = null,
        method: String = "POST",
        contentType: String = PROTOBUF,
        acceptType: String = PROTOBUF,
        withSecHeaders: Boolean = true,
        secType: String = "Vtrans"
    ): ByteArray {
        val builder = Request.Builder().url("https://$host$path")
        val common = mutableListOf(
            "Accept" to acceptType,
            "Content-Type" to contentType,
            "User-Agent" to USER_AGENT
        )
        if (withSecHeaders) {
            if (session != null) {
                val token = "${session.uuid}:$path:$VERSION"
                val signature = sign(token.toByteArray(Charsets.UTF_8))
                common += "Sec-$secType-Token" to "$signature:$token"
                common += "Sec-$secType-Sk" to session.secret
            }
            common += "$secType-Signature" to sign(body)
        }

        if (host == DIRECT_HOST) {
            common.forEach { (key, value) -> builder.header(key, value) }
            builder.header("sec-ch-ua", SEC_CH_UA)
                .header("sec-ch-ua-full-version-list", SEC_CH_UA_FULL_VERSION_LIST)
                .header("Sec-Fetch-Mode", "no-cors")
        } else {
            builder.header("User-Agent", "vot.js/3.1.2")
                .header("Content-Type", contentType)
                .header("X-VOT-Headers", Base64.encodeToString(
                    headerJson(*common.toTypedArray()).toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                ))
        }

        val requestBody = body.toRequestBody(contentType.toMediaType())
        if (method == "PUT") builder.put(requestBody) else builder.post(requestBody)
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "VOT HTTP ${response.code}" }
            return response.body?.bytes() ?: error("Empty VOT response")
        }
    }

    private fun sourceAudioBytes(sourceUrl: String?, maxBytes: Int = MAX_AUDIO_BYTES): ByteArray? {
        val parsed = sourceUrl?.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        // Never let an untrusted video metadata URL request arbitrary/private hosts.
        val domain = parsed.host.lowercase()
        if (!(domain == "googlevideo.com" || domain.endsWith(".googlevideo.com") ||
                domain == "youtube.com" || domain.endsWith(".youtube.com"))) return null

        return try {
            val req = Request.Builder().url(parsed).header("User-Agent", USER_AGENT).build()
            // Reject redirects to arbitrary hosts or local network addresses.
            client.newBuilder().followRedirects(false).build().newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val source = response.body?.byteStream() ?: return null
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (total < maxBytes) {
                    val read = source.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null // Unsupported or expired direct source: try the empty-audio fallback.
        }
    }

    private fun provideSourceAudio(
        url: String,
        videoId: String,
        translationId: String,
        directAudioUrl: String?,
        active: Session,
        onProgress: (String) -> Unit
    ) {
        val failJson = JSONObject().put("video_url", url).toString()
            .toByteArray(Charsets.UTF_8)
        runCatching {
            request(
                "/video-translation/fail-audio-js", failJson, active, "PUT",
                "application/json", "application/json", withSecHeaders = false
            )
        }

        val audio = sourceAudioBytes(directAudioUrl)
        onProgress(if (audio == null) "Пробуем запасной способ перевода…" else
            "Отправляем аудиофрагмент для перевода…")
        val fileId = if (audio == null) {
            "fallback-empty-audio:video-translation:$videoId"
        } else {
            "random-web_abr-${uuid()}"
        }
        request(
            "/video-translation/audio",
            VotProtocol.audioRequest(url, translationId, fileId, audio ?: ByteArray(0)),
            active,
            "PUT"
        )
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

    /**
     * Fetches already available Russian VOT subtitles without starting speech
     * synthesis. Returns null if the unofficial service has no subtitles.
     * No video audio or login credentials are sent.
     */
    suspend fun russianSubtitles(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) return@withContext null
        val videoUrl = "https://youtu.be/$videoId"

        for (index in hosts.indices) {
            currentCoroutineContext().ensureActive()
            try {
                val active = session()
                val bytes = request(
                    "/video-subtitles/get-subtitles",
                    VotProtocol.subtitlesRequest(videoUrl),
                    active,
                    secType = "Vsubs"
                )
                val entries = VotProtocol.subtitlesResponse(bytes)
                val translated = entries.firstOrNull {
                    it.translatedLanguage.equals("ru", ignoreCase = true) &&
                        it.translatedUrl.isNotBlank()
                }?.translatedUrl
                val original = entries.firstOrNull {
                    it.language.equals("ru", ignoreCase = true) &&
                        it.url.isNotBlank()
                }?.url
                val selected = translated ?: original
                if (selected != null) {
                    val parsed = selected.toHttpUrlOrNull()
                    if (parsed != null && parsed.isHttps &&
                        parsed.host != "localhost" && !parsed.host.startsWith("127.")) {
                        client.newBuilder().followRedirects(false).build()
                            .newCall(Request.Builder().url(parsed).get().build())
                            .execute().use { response ->
                                if (response.isSuccessful) {
                                    val body = response.body ?: return@use
                                    // Limit response size; do not load an arbitrary large caption.
                                    if (body.contentLength() > MAX_SUBTITLE_BYTES) return@use
                                    val raw = body.byteStream().use {
                                        it.readNBytes(MAX_SUBTITLE_BYTES + 1)
                                    }
                                    if (raw.size <= MAX_SUBTITLE_BYTES) {
                                        val text = raw.toString(Charsets.UTF_8)
                                        if (text.isNotBlank()) return@withContext text
                                    }
                                }
                            }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Non-critical: fall back to the video's own subtitle tracks.
            }
            rotateHost()
        }
        null
    }

    suspend fun translate(
        videoId: String,
        durationSeconds: Double,
        title: String,
        directAudioUrl: String? = null,
        onProgress: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        require(Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) {
            "Invalid YouTube video ID"
        }
        val videoUrl = "https://youtu.be/$videoId"
        var lastError: Exception = IllegalStateException("Translation not ready")
        var audioSent = false
        var translationId = ""
        var stuckCounter = 0

        repeat(35) { attempt ->
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
                    val audioUrl = result.audioUrl!!
                    require(audioUrl.startsWith("https://")) { "Unexpected VOT audio URL" }
                    return@withContext audioUrl
                }

                when {
                    result.audioRequested && !audioSent -> {
                        // Set the flag only once the server has accepted the audio PUT.
                        onProgress("Сервер запросил исходное аудио…")
                        provideSourceAudio(
                            videoUrl, videoId, translationId, directAudioUrl, active, onProgress
                        )
                        audioSent = true
                        stuckCounter = 0
                        delay(2000L)
                    }
                    result.status == 0 || result.status == 7 -> {
                        lastError = IllegalStateException(
                            result.message ?: "Сервис не может обработать это видео (status ${result.status})"
                        )
                        rotateHost()
                        translationId = ""
                        audioSent = false
                        stuckCounter = 0
                        onProgress("Пробуем другой сервер перевода…")
                        delay(1200L)
                    }
                    else -> {
                        // A pending translation must stay on the same host/session;
                        // rotating the host mid-job can make it start from scratch.
                        lastError = IllegalStateException(
                            result.message ?: "Ожидаем перевод (status ${result.status})"
                        )
                        stuckCounter = if (result.remainingSeconds in 1..5 || result.audioRequested) {
                            stuckCounter + 1
                        } else 0
                        if (stuckCounter >= 6) {
                            rotateHost()
                            translationId = ""
                            audioSent = false
                            stuckCounter = 0
                            onProgress("Перевод завис, меняем сервер…")
                        }
                        delay((result.remainingSeconds.takeIf { it > 0 } ?: 4)
                            .coerceIn(3, 8) * 1000L)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                lastError = error
                rotateHost()
                audioSent = false
                translationId = ""
                stuckCounter = 0
                onProgress("Сервер недоступен, повторяем запрос…")
                delay(if (attempt < hosts.size) 1000L else 2500L)
            }
        }
        throw IllegalStateException(
            "Перевод пока недоступен: ${lastError.message ?: "ошибка сервиса"}",
            lastError
        )
    }

    companion object {
        private const val DIRECT_HOST = "api.browser.yandex.ru"
        private const val PROTOBUF = "application/x-protobuf"
        private const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
        private const val MAX_SUBTITLE_BYTES = 2 * 1024 * 1024
        // Public compatibility key from the third-party VOT Player source, not a RootTube secret.
        private const val VTRANS_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf"
        private const val VERSION = "26.8.3.971"
        private const val SEC_CH_UA =
            "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", " +
                "\"YaBrowser\";v=\"26.8\", \"Yowser\";v=\"2.5\""
        private const val SEC_CH_UA_FULL_VERSION_LIST =
            "\"Not;A=Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"150.0.7871.1002\", " +
                "\"YaBrowser\";v=\"26.8.3.1002\", \"Yowser\";v=\"2.5\""

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/150.0.0.0 YaBrowser/26.8.0.0 Safari/537.36"
    }
}
