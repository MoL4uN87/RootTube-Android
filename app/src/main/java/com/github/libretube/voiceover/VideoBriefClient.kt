package com.github.libretube.voiceover

import android.text.Html
import com.github.libretube.api.obj.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Key-moments digest taken from real subtitle cues, not a generative AI model.
 * Selected foreign-language excerpts are optionally translated to Russian by
 * an unofficial public Google Translate endpoint. Never uploads audio or cookies.
 */
internal class VideoBriefClient {
    data class Brief(val text: String, val source: String, val language: String)
    private data class Cue(val ms: Long, val text: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun create(
        videoId: String,
        tracks: List<Subtitle>,
        durationSeconds: Long
    ): Brief = withContext(Dispatchers.IO) {
        var captions: List<Cue> = emptyList()
        var source = ""
        var isRussian = false

        // Prefer the Russian captions supplied by the current YouTube backend.
        val preferred = tracks.filter { it.code?.startsWith("ru", true) == true }
        val other = tracks.filter { it.code?.startsWith("ru", true) != true }
        for (track in preferred) {
            currentCoroutineContext().ensureActive()
            captions = download(track.url)
            if (captions.size >= MIN_CUES) {
                source = "русские субтитры видео"
                isRussian = true
                break
            }
        }

        // VOT can provide already translated subtitles for some English videos.
        if (captions.size < MIN_CUES) {
            currentCoroutineContext().ensureActive()
            try {
                VotClient().russianSubtitles(videoId)?.let { raw ->
                    val parsed = parse(raw)
                    if (parsed.size >= MIN_CUES) {
                        captions = parsed
                        source = "русские субтитры VOT"
                        isRussian = true
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // VOT subtitles are optional; normal YouTube tracks are next.
            }
        }

        if (captions.size < MIN_CUES) {
            for (track in other.sortedBy {
                if (it.code?.startsWith("en", true) == true) 0 else 1
            }) {
                currentCoroutineContext().ensureActive()
                captions = download(track.url)
                if (captions.size >= MIN_CUES) {
                    source = "субтитры видео (${track.code ?: "исходный язык"})"
                    isRussian = false
                    break
                }
            }
        }

        if (captions.size < MIN_CUES) {
            // Independent fallback: neither the current video backend nor VOT
            // needs to be available for YouTube's published caption tracks.
            val direct = directYouTubeTracks(videoId)
            for ((url, language) in direct.sortedBy { if (it.second.startsWith("ru")) 0 else 1 }) {
                currentCoroutineContext().ensureActive()
                captions = download(url)
                if (captions.size < MIN_CUES && !url.contains("fmt=")) {
                    val suffix = if (url.contains("?")) "&fmt=json3" else "?fmt=json3"
                    captions = download(url + suffix)
                }
                if (captions.size >= MIN_CUES) {
                    isRussian = language.startsWith("ru")
                    source = "субтитры напрямую с YouTube ($language)"
                    break
                }
            }
        }

        if (captions.size < MIN_CUES) {
            throw IllegalStateException(
                "Нет доступной дорожки субтитров: источник видео, VOT и " +
                    "прямой запрос YouTube не вернули текст. Если в ролике " +
                    "нет субтитров, для пересказа потребуется распознавание речи."
            )
        }

        val points = selectMoments(captions, durationSeconds)
        val momentLines = mutableListOf<String>()
        var translatedCount = 0
        for ((position, cue) in points.withIndex()) {
            currentCoroutineContext().ensureActive()
            val translation = if (isRussian) cue.text else translateIntoRussian(cue.text)
            if (!isRussian && translation != null) translatedCount++
            val displayed = translation ?: cue.text
            momentLines += "${position + 1}. ${formatTimestamp(cue.ms)} — ${displayed.trim()}"
        }

        val langLabel = when {
            isRussian -> "Русский"
            translatedCount == points.size -> "Русский (машинный перевод)"
            translatedCount > 0 -> "Смешанный (часть перевода недоступна)"
            else -> "Оригинальный язык (перевод недоступен)"
        }
        val preamble = "Ключевые моменты по субтитрам:\n\n"
        val footer = "\n\nИсточник: $source. Язык: $langLabel.\n" +
            "Это автоматическая подборка фрагментов речи, а не генеративный AI-пересказ. " +
            "Таймкоды приблизительные; в субтитрах могут быть ошибки."

        Brief(preamble + momentLines.joinToString("\n\n") + footer, source, langLabel)
    }

    /**
     * Independently discovers signed caption tracks. This deliberately does
     * not send YouTube cookies or require the Yandex VOT server.
     */
    private fun directYouTubeTracks(videoId: String): List<Pair<String, String>> {
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) return emptyList()
        try {
            val payload = JSONObject()
                .put("videoId", videoId)
                .put("context", JSONObject().put("client", JSONObject()
                    .put("clientName", "ANDROID_VR")
                    .put("clientVersion", "1.60.19")
                    .put("deviceMake", "Oculus")
                    .put("deviceModel", "Quest 3")
                    .put("osName", "Android")
                    .put("osVersion", "12")
                    .put("hl", "en")
                    .put("gl", "US")))
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player")
                .header("User-Agent", "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12)")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val tracks = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else {
                    val json = limitedBody(response.body)
                    if (json != null) JSONObject(json)
                        .optJSONObject("captions")
                        ?.optJSONObject("playerCaptionsTracklistRenderer")
                        ?.optJSONArray("captionTracks")
                    else null
                }
            }
            if (tracks != null && tracks.length() > 0) {
                return captionUrls(tracks)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Fallback below: public video HTML.
        }

        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123.0 Safari/537.36")
                .get().build()
            http.newCall(request).execute().use { response ->
                val html = if (response.isSuccessful) limitedBody(response.body, 4 * MAX_BYTES) else null
                val found = html?.let { jsonArrayAfter(it, "\\"captionTracks\\":") }
                if (found == null) emptyList() else captionUrls(JSONArray(found))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun captionUrls(array: JSONArray): List<Pair<String, String>> = buildList {
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val url = t.optString("baseUrl")
            if (url.isBlank()) continue
            val lang = t.optString("languageCode", "und").lowercase()
            add(url to lang)
        }
    }

    /** Extract an array from an embedded player JSON without greedy regex. */
    private fun jsonArrayAfter(html: String, key: String): String? {
        val keyIndex = html.indexOf(key)
        if (keyIndex < 0) return null
        val begin = html.indexOf('[', keyIndex + key.length)
        if (begin < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in begin until minOf(html.length, begin + MAX_BYTES)) {
            val ch = html[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\\\') escaped = true
                else if (ch == '"') quoted = false
            } else {
                when (ch) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return html.substring(begin, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun limitedBody(body: okhttp3.ResponseBody?, maxBytes: Int = MAX_BYTES): String? {
        if (body == null || body.contentLength() > maxBytes) return null
        return body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= maxBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
                if (n < 0) break
                if (n > 0) output.write(buffer, 0, n)
            }
            if (output.size() > maxBytes) null else output.toString(Charsets.UTF_8.name())
        }
    }

    private fun download(rawUrl: String?): List<Cue> {
        val uri = rawUrl?.toHttpUrlOrNull() ?: return emptyList()
        if (!uri.isHttps || uri.host == "localhost" ||
            uri.host.startsWith("127.") || uri.host.startsWith("192.168.") ||
            uri.host.startsWith("10.") || uri.host.endsWith(".local")) {
            return emptyList()
        }
        return try {
            http.newCall(Request.Builder().url(uri).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body ?: return@use emptyList()
                if (body.contentLength() > MAX_BYTES) return@use emptyList()
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (output.size() <= MAX_BYTES) {
                        val count = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES + 1 - output.size()))
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(chunk, 0, count)
                    }
                }
                if (output.size() > MAX_BYTES) return@use emptyList()
                parse(output.toString(Charsets.UTF_8.name()))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parse(raw: String): List<Cue> {
        val trimmed = raw.trimStart('\uFEFF', ' ', '\n', '\r')
        if (trimmed.isBlank()) return emptyList()
        val result = when {
            trimmed.startsWith("{") -> parseJson(JSONObject(trimmed))
            trimmed.startsWith("[") -> parseArray(JSONArray(trimmed))
            trimmed.startsWith("<") -> parseXml(trimmed)
            else -> parseVtt(trimmed)
        }
        return result
            .filter { it.ms >= 0 && it.text.length >= 2 }
            .sortedBy { it.ms }
            .distinctBy { (it.ms / 1000L) to it.text }
    }

    private fun parseJson(root: JSONObject): List<Cue> {
        val subtitleArray = root.optJSONArray("subtitles")
        if (subtitleArray != null) return parseArray(subtitleArray)
        val events = root.optJSONArray("events") ?: return emptyList()
        return buildList {
            for (i in 0 until events.length()) {
                val item = events.optJSONObject(i) ?: continue
                val parts = item.optJSONArray("segs") ?: continue
                val text = buildString {
                    for (index in 0 until parts.length()) {
                        append(parts.optJSONObject(index)?.optString("utf8").orEmpty())
                    }
                }
                val cleaned = clean(text)
                if (cleaned.isNotBlank()) add(Cue(item.optLong("tStartMs"), cleaned))
            }
        }
    }

    private fun parseArray(array: JSONArray): List<Cue> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val start = when {
                item.has("startMs") -> item.optDouble("startMs")
                item.has("startTimeMs") -> item.optDouble("startTimeMs")
                item.has("start") -> item.optDouble("start") * 1000.0
                else -> 0.0
            }
            val text = clean(item.optString("text"))
            if (text.isNotBlank()) add(Cue(start.toLong(), text))
        }
    }

    private fun parseXml(xml: String): List<Cue> {
        val regex = Regex("<(p|text)\\b([^>]*)>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL))
        val attr = Regex("(t|start)=[\"']([0-9.]+)[\"']")
        return regex.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[2]
            val m = attr.find(attrs) ?: return@mapNotNull null
            val number = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val timestamp = if (m.groupValues[1] == "start") number * 1000 else number
            val text = clean(match.groupValues[3])
            if (text.isBlank()) null else Cue(timestamp.toLong(), text)
        }.toList()
    }

    private fun parseVtt(raw: String): List<Cue> {
        val lines = raw.lines()
        val result = mutableListOf<Cue>()
        var cursor = 0
        while (cursor < lines.size) {
            val index = lines[cursor].indexOf("-->")
            if (index < 0) { cursor++; continue }
            val start = timestampMs(lines[cursor].substring(0, index).trim())
            cursor++
            val text = mutableListOf<String>()
            while (cursor < lines.size && lines[cursor].isNotBlank()) {
                text += lines[cursor++].trim()
            }
            val cleaned = clean(text.joinToString(" "))
            if (start != null && cleaned.isNotEmpty()) result += Cue(start, cleaned)
            cursor++
        }
        return result
    }

    private fun timestampMs(value: String): Long? {
        val parts = value.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        return try {
            val sec = parts.last().toDouble()
            val min = parts[parts.size - 2].toLong()
            val h = if (parts.size == 3) parts[0].toLong() else 0L
            ((h * 3600 + min * 60 + sec) * 1000).toLong()
        } catch (_: Exception) { null }
    }

    private fun clean(raw: String): String {
        val noMarkup = raw.replace(Regex("<[^>]*>"), " ")
        @Suppress("DEPRECATION")
        val decoded = Html.fromHtml(noMarkup, Html.FROM_HTML_MODE_LEGACY).toString()
        return decoded.replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\[(music|applause|музыка|аплодисменты)\\]"), "")
            .trim()
    }

    /**
     * Samples the beginning, middle and end rather than silently truncating
     * long videos to their first minutes. No generated facts are added.
     */
    private fun selectMoments(cues: List<Cue>, durationSeconds: Long): List<Cue> {
        val end = max(durationSeconds * 1000L, cues.last().ms + 1000L)
        val buckets = if (end > 45 * 60_000L) 10 else 7
        val result = mutableListOf<Cue>()
        for (bucket in 0 until buckets) {
            val start = bucket * end / buckets
            val finish = (bucket + 1) * end / buckets
            val available = cues.filter { it.ms in start until finish && it.text.isNotBlank() }
            if (available.isEmpty()) continue
            val offset = available.size / 5
            val selected = available.drop(offset).ifEmpty { available }
            val paragraph = StringBuilder()
            val startMs = selected.first().ms
            var last = ""
            for (cue in selected) {
                val phrase = cue.text.trim()
                if (phrase == last || phrase.length < 3) continue
                val normalized = when {
                    phrase.startsWith(last) && last.length > 10 ->
                        phrase.substring(last.length).trim()
                    last.startsWith(phrase) -> ""
                    else -> phrase
                }
                if (normalized.isNotBlank()) {
                    if (paragraph.isNotEmpty()) paragraph.append(" ")
                    paragraph.append(normalized)
                }
                last = phrase
                if (paragraph.length >= 190) break
            }
            val excerpt = paragraph.toString().take(310).trim()
            if (excerpt.length >= 30) result += Cue(startMs, excerpt)
        }
        return result.ifEmpty { listOf(cues.first()) }
    }

    private fun formatTimestamp(ms: Long): String {
        val seconds = ms / 1000L
        val minutes = seconds / 60L
        val hour = minutes / 60L
        return if (hour > 0) "%d:%02d:%02d".format(hour, minutes % 60, seconds % 60)
        else "%02d:%02d".format(minutes, seconds % 60)
    }

    /** Unofficial public translation service: may be unavailable/rate-limited. */
    private fun translateIntoRussian(phrase: String): String? {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("client", "gtx")
            ?.addQueryParameter("sl", "auto")
            ?.addQueryParameter("tl", "ru")
            ?.addQueryParameter("dt", "t")
            ?.addQueryParameter("q", phrase)
            ?.build() ?: return null
        return try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.take(MAX_BYTES) ?: return null
                val outer = JSONArray(body).optJSONArray(0) ?: return null
                buildString {
                    for (i in 0 until outer.length()) {
                        append(outer.optJSONArray(i)?.optString(0).orEmpty())
                    }
                }.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MIN_CUES = 8
    }
}
