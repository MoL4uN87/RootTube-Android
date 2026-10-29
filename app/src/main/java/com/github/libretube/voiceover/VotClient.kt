package com.github.libretube.voiceover

import android.util.Base64
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.File
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
internal class VotClient(private val cacheDir: File? = null) {
    data class AudioSource(
        val url: String,
        val mimeType: String?,
        val codec: String?,
        val contentLength: Long,
        val language: String?
    )


    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    // Same backend order as the working VOT Player reference implementation.
    private val hosts = listOf(
        "vot-worker.vtrans.eu.cc",
        "vot-worker.eu.cc",
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
        secType: String = "Vtrans",
        timeoutSeconds: Long? = null
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
        val transport = if (timeoutSeconds == null) client
            else client.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        transport.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "VOT HTTP ${response.code}" }
            return response.body?.bytes() ?: error("Empty VOT response")
        }
    }

    private fun trustedMediaUrl(url: okhttp3.HttpUrl): Boolean {
        if (!url.isHttps || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val name = url.host.lowercase()
        return name == "googlevideo.com" || name.endsWith(".googlevideo.com") ||
            name == "youtube.com" || name.endsWith(".youtube.com")
    }

    private fun sourceAudioBytes(sourceUrl: String?, maxBytes: Int = MAX_AUDIO_BYTES): ByteArray? {
        var mediaUrl = sourceUrl?.toHttpUrlOrNull()
        if (mediaUrl == null || !trustedMediaUrl(mediaUrl)) {
            Log.i("RootTube-VOT", "sourceAudio unavailable: missing or untrusted HTTPS URL")
            return null
        }

        return try {
            // Media endpoints often return 302 to a signed Googlevideo CDN URL.
            // Follow only a few HTTPS redirects and revalidate EVERY destination
            // to avoid forwarding requests to arbitrary hosts.
            val downloader = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()

            for (redirectIndex in 0..4) {
                val request = Request.Builder()
                    .url(mediaUrl!!)
                    .header("User-Agent", USER_AGENT)
                    .build()
                downloader.newCall(request).execute().use { response ->
                    if (response.code in 300..399) {
                        val location = response.header("Location")
                        val target = location?.let { response.request.url.resolve(it) }
                        if (target == null || !trustedMediaUrl(target)) {
                            Log.w(
                                "RootTube-VOT",
                                "sourceAudio redirect blocked: status=${response.code} " +
                                    "targetHost=${target?.host ?: "missing"}"
                            )
                            return null
                        }
                        Log.i(
                            "RootTube-VOT",
                            "sourceAudio redirect=${redirectIndex + 1} HTTP ${response.code} " +
                                "from=${mediaUrl!!.host} to=${target.host}"
                        )
                        mediaUrl = target
                    } else {
                        if (!response.isSuccessful) {
                            Log.i("RootTube-VOT", "sourceAudio download HTTP ${response.code}")
                            return null
                        }
                        val body = response.body
                        if (body.contentLength() > maxBytes) {
                            Log.i(
                                "RootTube-VOT",
                                "sourceAudio too large: ${body.contentLength()} bytes"
                            )
                            return null
                        }

                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(32 * 1024)
                        body.byteStream().use { input ->
                            while (output.size() <= maxBytes) {
                                val count = input.read(
                                    buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size())
                                )
                                if (count < 0) break
                                if (count > 0) output.write(buffer, 0, count)
                            }
                        }
                        if (output.size() > maxBytes) {
                            Log.i("RootTube-VOT", "sourceAudio exceeds $maxBytes byte cap")
                            return null
                        }
                        return output.toByteArray().takeIf { it.isNotEmpty() }?.also {
                            Log.i("RootTube-VOT", "sourceAudio downloaded completely: ${it.size} bytes")
                        }
                    }
                }
            }
            Log.w("RootTube-VOT", "sourceAudio redirect limit exceeded")
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.i(
                "RootTube-VOT",
                "sourceAudio download failed: ${error.javaClass.simpleName} " +
                    "${error.message?.take(90)}"
            )
            null
        }
    }

    private fun provideSourceAudio(
        url: String,
        videoId: String,
        translationId: String,
        directAudioCandidates: List<AudioSource>,
        active: Session,
        onProgress: (String) -> Unit
    ) {
        val failJson = JSONObject().put("video_url", url).toString()
            .toByteArray(Charsets.UTF_8)
        Log.i("RootTube-VOT", "stage=fail-audio-js started host=$host")
        try {
            request(
                "/video-translation/fail-audio-js", failJson, active, "PUT",
                "application/json", "application/json",
                withSecHeaders = false, timeoutSeconds = 12L
            )
            Log.i("RootTube-VOT", "stage=fail-audio-js completed host=$host")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // The fallback endpoint may fail, but we can still try real audio.
            Log.w(
                "RootTube-VOT",
                "stage=fail-audio-js failed host=$host cause=${error.javaClass.simpleName} " +
                    "message=${error.message?.take(100)}"
            )
        }

        Log.i("RootTube-VOT", "stage=sourceAudio download-started host=$host")
        var audio: ByteArray? = null
        for ((index, candidate) in directAudioCandidates.take(4).withIndex()) {
            Log.i(
                "RootTube-VOT",
                "stage=sourceAudio trying candidate=${index + 1} " +
                    "mime=${candidate.mimeType ?: "unknown"} " +
                    "codec=${candidate.codec ?: "unknown"} bytes=${candidate.contentLength}"
            )
            val raw = sourceAudioBytes(candidate.url) ?: continue
            // EBML marker identifies actual WebM data; metadata alone can be wrong.
            val isWebm = raw.size >= 4 &&
                (raw[0].toInt() and 255) == 0x1A &&
                (raw[1].toInt() and 255) == 0x45 &&
                (raw[2].toInt() and 255) == 0xDF &&
                (raw[3].toInt() and 255) == 0xA3
            audio = if (isWebm) {
                Log.i("RootTube-VOT", "stage=sourceAudio native-WebM no conversion needed")
                raw
            } else {
                Log.i("RootTube-VOT", "stage=sourceAudio conversion=WebM/Opus started")
                cacheDir?.let {
                    VotAudioTranscoder.convertToWebmOpus(raw, it, MAX_AUDIO_BYTES)
                }
            }
            if (audio != null) {
                Log.i("RootTube-VOT", "stage=sourceAudio selected candidate=${index + 1}")
                break
            }
            Log.i("RootTube-VOT", "stage=sourceAudio candidate=${index + 1} unusable")
        }
        Log.i("RootTube-VOT", "stage=sourceAudio download-finished bytes=${audio?.size ?: 0}")
        onProgress(if (audio == null) "Пробуем запасной способ перевода…" else
            "Отправляем аудиофрагмент для перевода…")
        val fileId = if (audio == null) {
            "fallback-empty-audio:video-translation:$videoId"
        } else {
            "random-web_abr-${uuid()}"
        }
        Log.i(
            "RootTube-VOT",
            "audio upload: mode=${if (audio == null) "empty-fallback" else "complete-source"} " +
                "bytes=${audio?.size ?: 0} host=$host"
        )
        request(
            "/video-translation/audio",
            VotProtocol.audioRequest(url, translationId, fileId, audio ?: ByteArray(0)),
            active,
            "PUT"
        )
        Log.i("RootTube-VOT", "stage=audio-upload accepted host=$host")
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
                                    val raw = body.byteStream().use { input ->
                                        val output = ByteArrayOutputStream()
                                        val chunk = ByteArray(8192)
                                        while (output.size() <= MAX_SUBTITLE_BYTES) {
                                            val n = input.read(
                                                chunk, 0,
                                                minOf(chunk.size, MAX_SUBTITLE_BYTES + 1 - output.size())
                                            )
                                            if (n < 0) break
                                            if (n > 0) output.write(chunk, 0, n)
                                        }
                                        output.toByteArray()
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
        directAudioCandidates: List<AudioSource> = emptyList(),
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
        var consecutiveServerFailures = 0
        var expiredSessionRetries = 0

        // Wait for genuine in-progress translations, but stop repeating after
        // every relay has returned the same hard failure.
        translationLoop@ for (attempt in 0 until 35) {
            currentCoroutineContext().ensureActive()
            try {
                val active = session()
                val data = request(
                    "/video-translation/translate",
                    VotProtocol.translationRequest(videoUrl, durationSeconds, title),
                    active
                )
                val result = VotProtocol.translationResponse(data)
                // Host diagnostics do not contain URLs, IDs, request bodies or tokens.
                Log.i("RootTube-VOT", "host=$host status=${result.status} remaining=${result.remainingSeconds} hasAudio=${!result.audioUrl.isNullOrBlank()}")
                if (result.translationId.isNotBlank()) translationId = result.translationId
                if (result.ready) {
                    val audioUrl = result.audioUrl!!
                    require(audioUrl.startsWith("https://")) { "Unexpected VOT audio URL" }
                    return@withContext audioUrl
                }

                when {
                    result.audioRequested && !audioSent -> {
                        consecutiveServerFailures = 0
                        expiredSessionRetries = 0
                        // Set the flag only once the server has accepted the audio PUT.
                        onProgress("Сервер запросил исходное аудио…")
                        provideSourceAudio(
                            videoUrl, videoId, translationId, directAudioCandidates, active, onProgress
                        )
                        audioSent = true
                        stuckCounter = 0
                        delay(2000L)
                    }
                    result.status == 7 -> {
                        // Status 7 means the Yandex session must be renewed. A
                        // different proxy cannot fix an expired cached session.
                        lastError = IllegalStateException("Сервис запросил новую сессию (7)")
                        session = null
                        translationId = ""
                        audioSent = false
                        stuckCounter = 0
                        if (++expiredSessionRetries >= 2) {
                            expiredSessionRetries = 0
                            consecutiveServerFailures++
                            rotateHost()
                            onProgress("Сессия не принята, проверяем другой сервер…")
                            if (consecutiveServerFailures >= hosts.size + 1) break@translationLoop
                        } else {
                            onProgress("Обновляем сессию перевода…")
                        }
                        delay(1000L)
                    }
                    result.status == 0 -> {
                        lastError = IllegalStateException(
                            result.message?.take(120)
                                ?: "VOT отказал в обработке этого ролика (status 0)"
                        )
                        Log.w(
                            "RootTube-VOT",
                            "host=$host videoProcessingRejected status=0 " +
                                "message=${result.message?.replace(10.toChar(), ' ')?.take(160) ?: "<none>"}"
                        )
                        consecutiveServerFailures++
                        expiredSessionRetries = 0
                        rotateHost()
                        translationId = ""
                        audioSent = false
                        stuckCounter = 0
                        if (consecutiveServerFailures >= hosts.size + 1) break@translationLoop
                        onProgress("Видео не обработано, пробуем другой VOT-сервер…")
                        delay(1200L)
                    }
                    else -> {
                        consecutiveServerFailures = 0
                        expiredSessionRetries = 0
                        // In-progress translations must stay on the original
                        // host/session; otherwise the job may restart from zero.
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
                val failedHost = host
                Log.w("RootTube-VOT", "host=$failedHost failure=${error.javaClass.simpleName}: ${error.message?.take(120)}")
                rotateHost()
                audioSent = false
                translationId = ""
                stuckCounter = 0
                expiredSessionRetries = 0
                consecutiveServerFailures++
                if (consecutiveServerFailures >= hosts.size + 1) break@translationLoop
                val detail = error.message?.take(45).orEmpty()
                onProgress("VOT: $failedHost недоступен ($detail), ищем другой…")
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
