package com.github.libretube.voiceover

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Protocol Buffers wire encoder/decoder for VOT.
 * Adapted from VOT Player (MIT, Copyright 2026 Alexandr Motologa).
 * The unofficial wire format can change without notice.
 */
internal object VotProtocol {
    data class Translation(
        val audioUrl: String?,
        val status: Int,
        val remainingSeconds: Int,
        val translationId: String,
        val message: String?
    ) {
        val ready: Boolean get() = status == 1 && !audioUrl.isNullOrBlank()
        val audioRequested: Boolean get() = status == 6
    }

    private fun varint(out: ByteArrayOutputStream, number: Long) {
        var value = number
        while (value and 0x7f.inv().toLong() != 0L) {
            out.write(((value and 0x7f) or 0x80).toInt())
            value = value ushr 7
        }
        out.write(value.toInt())
    }

    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) =
        varint(out, ((field shl 3) or wire).toLong())

    private fun string(out: ByteArrayOutputStream, field: Int, value: String) {
        if (value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        tag(out, field, 2)
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun bool(out: ByteArrayOutputStream, field: Int, value: Boolean) {
        if (!value) return
        tag(out, field, 0)
        varint(out, 1L)
    }

    fun sessionRequest(uuid: String): ByteArray = ByteArrayOutputStream().apply {
        string(this, 1, uuid)
        string(this, 2, "video-translation")
    }.toByteArray()

    fun translationRequest(url: String, durationSeconds: Double, title: String): ByteArray =
        ByteArrayOutputStream().apply {
            string(this, 3, url)
            bool(this, 5, true)
            tag(this, 6, 1)
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putDouble(if (durationSeconds > 0.0) durationSeconds else 300.0).array())
            bool(this, 7, true)
            string(this, 8, "en")
            string(this, 14, "ru")
            bool(this, 15, true)
            tag(this, 16, 0)
            varint(this, 2L)
            string(this, 19, title)
        }.toByteArray()

    /**
     * Uploads a bounded source-audio fragment when the server returns status 6.
     * Never includes YouTube cookies or user credentials.
     */
    fun audioRequest(
        url: String,
        translationId: String,
        fileId: String,
        audioBytes: ByteArray = ByteArray(0)
    ): ByteArray = ByteArrayOutputStream().apply {
        string(this, 1, translationId)
        string(this, 2, url)
        val inner = ByteArrayOutputStream()
        string(inner, 1, fileId)
        if (audioBytes.isNotEmpty()) {
            tag(inner, 2, 2)
            varint(inner, audioBytes.size.toLong())
            inner.write(audioBytes)
        }
        val bytes = inner.toByteArray()
        tag(this, 6, 2)
        varint(this, bytes.size.toLong())
        write(bytes)
    }.toByteArray()

    data class SubtitleEntry(
        val language: String,
        val url: String,
        val translatedLanguage: String,
        val translatedUrl: String
    )

    fun subtitlesRequest(url: String): ByteArray = ByteArrayOutputStream().apply {
        string(this, 1, url)
        string(this, 2, "en")
    }.toByteArray()

    /** Minimal wire decoder for the Yandex VOT /video-subtitles endpoint. */
    fun subtitlesResponse(bytes: ByteArray): List<SubtitleEntry> {
        val input = ByteArrayInputStream(bytes)
        val entries = mutableListOf<SubtitleEntry>()
        while (input.available() > 0) {
            val outer = readVarint(input).toInt()
            if (outer ushr 3 == 2 && outer and 7 == 2) {
                val item = ByteArrayInputStream(nextBytes(input))
                var lang = ""
                var url = ""
                var targetLang = ""
                var targetUrl = ""
                while (item.available() > 0) {
                    val field = readVarint(item).toInt()
                    when {
                        field ushr 3 == 1 && field and 7 == 2 ->
                            lang = nextBytes(item).toString(Charsets.UTF_8)
                        field ushr 3 == 2 && field and 7 == 2 ->
                            url = nextBytes(item).toString(Charsets.UTF_8)
                        field ushr 3 == 4 && field and 7 == 2 ->
                            targetLang = nextBytes(item).toString(Charsets.UTF_8)
                        field ushr 3 == 5 && field and 7 == 2 ->
                            targetUrl = nextBytes(item).toString(Charsets.UTF_8)
                        else -> skip(item, field and 7)
                    }
                }
                if (url.isNotBlank() || targetUrl.isNotBlank()) {
                    entries += SubtitleEntry(lang, url, targetLang, targetUrl)
                }
            } else {
                skip(input, outer and 7)
            }
        }
        return entries
    }

    private fun readVarint(input: ByteArrayInputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 70) {
            val v = input.read()
            require(v >= 0) { "Invalid protobuf varint" }
            result = result or ((v.toLong() and 127L) shl shift)
            if (v and 128 == 0) return result
            shift += 7
        }
        error("Protobuf varint too long")
    }

    private fun nextBytes(input: ByteArrayInputStream): ByteArray {
        val len = readVarint(input)
        require(len in 0..input.available().toLong()) { "Invalid protobuf length" }
        return ByteArray(len.toInt()).also {
            require(input.read(it) == it.size) { "Truncated protobuf" }
        }
    }

    private fun skip(input: ByteArrayInputStream, wire: Int) {
        when (wire) {
            0 -> readVarint(input)
            1 -> require(input.skip(8L) == 8L) { "Truncated protobuf double" }
            2 -> nextBytes(input)
            5 -> require(input.skip(4L) == 4L) { "Truncated protobuf float" }
            else -> error("Unsupported protobuf wire type: $wire")
        }
    }

    fun sessionResponse(bytes: ByteArray): Pair<String, Int> {
        val input = ByteArrayInputStream(bytes)
        var secret = ""
        var ttl = 0
        while (input.available() > 0) {
            val fieldTag = readVarint(input).toInt()
            when (fieldTag ushr 3) {
                1 -> if (fieldTag and 7 == 2) secret = nextBytes(input).toString(Charsets.UTF_8)
                    else skip(input, fieldTag and 7)
                2 -> if (fieldTag and 7 == 0) ttl = readVarint(input).toInt()
                    else skip(input, fieldTag and 7)
                else -> skip(input, fieldTag and 7)
            }
        }
        return secret to ttl
    }

    fun translationResponse(bytes: ByteArray): Translation {
        val input = ByteArrayInputStream(bytes)
        var url: String? = null
        var status = 0
        var remaining = -1
        var translationId = ""
        var message: String? = null
        while (input.available() > 0) {
            val fieldTag = readVarint(input).toInt()
            val field = fieldTag ushr 3
            val wire = fieldTag and 7
            when {
                field == 1 && wire == 2 -> url = nextBytes(input).toString(Charsets.UTF_8)
                field == 4 && wire == 0 -> status = readVarint(input).toInt()
                field == 5 && wire == 0 -> remaining = readVarint(input).toInt()
                field == 7 && wire == 2 -> translationId = nextBytes(input).toString(Charsets.UTF_8)
                field == 9 && wire == 2 -> message = nextBytes(input).toString(Charsets.UTF_8)
                else -> skip(input, wire)
            }
        }
        return Translation(url, status, remaining, translationId, message)
    }
}
