package com.github.libretube.ui.models

import androidx.core.text.parseAsHtml
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.obj.Comment
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sheet-scoped translation state shared by the main comments list and replies.
 * Only comments loaded by Paging are processed, with a small concurrency limit.
 * Language models are downloaded on first use, then translation runs on-device.
 */
class CommentsTranslationViewModel : ViewModel() {
    sealed interface Result {
        data class Translated(val text: String) : Result
        data object Unchanged : Result
        data object Failed : Result
    }

    private val _enabled = MutableLiveData(false)
    val enabled: LiveData<Boolean> = _enabled

    private val _updatedCommentId = MutableLiveData<String>()
    val updatedCommentId: LiveData<String> = _updatedCommentId

    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val downloads = mutableMapOf<String, Deferred<Unit>>()
    private val semaphore = Semaphore(2)
    private val cache = LinkedHashMap<String, Result>()
    private val inProgress = mutableSetOf<String>()

    fun toggle() {
        val next = _enabled.value != true
        if (next) {
            // Allow a fresh attempt if a model download failed on the last try.
            cache.entries.removeAll { it.value == Result.Failed }
        }
        _enabled.value = next
    }

    private fun key(comment: Comment): String =
        "${comment.commentId}:${comment.commentText.orEmpty()}"

    fun resultFor(comment: Comment): Result? = cache[key(comment)]

    fun request(comment: Comment) {
        if (_enabled.value != true || comment.commentText.isNullOrBlank()) return
        val cacheKey = key(comment)
        if (cache.containsKey(cacheKey) || !inProgress.add(cacheKey)) return

        viewModelScope.launch {
            try {
                val result = semaphore.withPermit {
                    translate(comment.commentText.orEmpty().parseAsHtml().toString())
                }
                cache[cacheKey] = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cache[cacheKey] = Result.Failed
            } finally {
                inProgress.remove(cacheKey)
                while (cache.size > 300) {
                    cache.remove(cache.keys.first())
                }
                _updatedCommentId.value = comment.commentId
            }
        }
    }

    private suspend fun translate(text: String): Result {
        val clean = text.trim()
        if (clean.isBlank() || clean.none { it.isLetter() }) return Result.Unchanged

        val detected = languageIdentifier.identifyLanguage(clean).awaitResult()
        if (detected == "ru") return Result.Unchanged

        // Language identification is uncertain for short comments. For simple
        // Latin-script text, English is a useful fallback; otherwise keep original.
        val source = TranslateLanguage.fromLanguageTag(detected)
            ?: if (clean.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                TranslateLanguage.ENGLISH
            } else {
                return Result.Unchanged
            }
        if (source == TranslateLanguage.RUSSIAN) return Result.Unchanged

        val translator = translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(TranslateLanguage.RUSSIAN)
                    .build()
            )
        }
        val download = downloads.getOrPut(source) {
            viewModelScope.async {
                translator.downloadModelIfNeeded().awaitResult()
            }
        }
        try {
            download.await()
        } catch (error: Exception) {
            downloads.remove(source)
            throw error
        }
        val translated = translator.translate(clean).awaitResult().trim()
        return if (translated.isBlank()) Result.Failed else Result.Translated(translated)
    }

    override fun onCleared() {
        super.onCleared()
        languageIdentifier.close()
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener { continuation.cancel() }
        }
}
