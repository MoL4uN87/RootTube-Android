package com.github.libretube.voiceover

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Retrieves a *pre-existing* YouTube AI video summary, if the public watch
 * response exposes one, and translates it to Russian using on-device ML Kit.
 *
 * No user cookies, YouTube account tokens or Alice data are sent. ML Kit may
 * download its language model on first use; subsequent translations run locally.
 * No fabricated summary is produced if the source is absent.
 */
internal class YouTubeAiSummaryClient {
    data class Summary(val russianText: String, val wasTranslated: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun getRussianSummary(videoId: String): Summary? {
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(videoId)) return null
        val englishOrRussian = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .header("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123.0 Safari/537.36")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = readLimited(response.body) ?: return@use null
                    YouTubeAiSummaryParser.extractFromWatchHtml(body)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
        } ?: return null

        // Do not return a foreign-language summary if the translation model
        // cannot be downloaded or is unavailable on this handset.
        val russianLetters = englishOrRussian.count {
            it in 'А'..'я' || it == 'ё' || it == 'Ё'
        }
        val latinLetters = englishOrRussian.count {
            it in 'A'..'Z' || it in 'a'..'z'
        }
        if (russianLetters > latinLetters) {
            return Summary(englishOrRussian, false)
        }

        val languageId = LanguageIdentification.getClient()
        val sourceLanguage = try {
            val detected = languageId.identifyLanguage(englishOrRussian).awaitTask()
            TranslateLanguage.fromLanguageTag(detected)
                ?.takeUnless { it == TranslateLanguage.RUSSIAN }
                ?: TranslateLanguage.ENGLISH
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            TranslateLanguage.ENGLISH
        } finally {
            languageId.close()
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )
        return try {
            translator.downloadModelIfNeeded().awaitTask()
            val result = translator.translate(englishOrRussian).awaitTask().trim()
            val translatedRussianLetters = result.count {
                it in 'А'..'я' || it == 'ё' || it == 'Ё'
            }
            if (result.length < 25 || translatedRussianLetters < 12) null
            else Summary(result, true)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } finally {
            translator.close()
        }
    }

    private fun readLimited(body: okhttp3.ResponseBody?): String? {
        if (body == null || body.contentLength() > MAX_HTML_BYTES) return null
        return body.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() <= MAX_HTML_BYTES) {
                val size = minOf(buffer.size, MAX_HTML_BYTES + 1 - out.size())
                if (size <= 0) break
                val count = input.read(buffer, 0, size)
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            if (out.size() > MAX_HTML_BYTES) null
            else out.toString(Charsets.UTF_8.name())
        }
    }

    private suspend fun <T> Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel() }
        }

    companion object {
        private const val MAX_HTML_BYTES = 8 * 1024 * 1024
    }
}
