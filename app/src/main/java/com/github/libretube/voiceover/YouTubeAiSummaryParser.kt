package com.github.libretube.voiceover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the AI video summary bundled in YouTube's initial /watch HTML.
 *
 * This is a best-effort parser for a non-public YouTube page data shape,
 * not an official API. It never mixes the video description or the generic
 * "AI can make mistakes" banner with the actual summary paragraphs.
 */
internal object YouTubeAiSummaryParser {
    private val rendererKey = Regex(""""expandableMetadataRenderer"\s*:"""")
    private const val MAX_RENDERER_CHARS = 320_000

    fun extractFromWatchHtml(html: String): String? {
        if (html.length > 10_000_000) return null
        for (match in rendererKey.findAll(html)) {
            val begin = html.indexOf('{', match.range.last + 1)
            if (begin < 0) break
            val block = balancedObject(html, begin) ?: continue
            val summary = runCatching {
                val renderer = Json.parseToJsonElement(block).jsonObject
                val view = renderer["expandedContent"]
                    ?.jsonObject
                    ?.get("videoSummaryContentViewModel")
                    ?.jsonObject
                    ?: return@runCatching null

                view["paragraphs"]?.jsonArray
                    ?.mapNotNull { item ->
                        item.jsonObject["videoSummaryParagraphViewModel"]
                            ?.jsonObject
                            ?.get("text")
                            ?.jsonObject
                            ?.get("content")
                            ?.jsonPrimitive
                            ?.content
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }
                    ?.joinToString("\n\n")
                    ?.trim()
            }.getOrNull() ?: continue

            if (summary.length in 40..9_000) return summary
        }
        return null
    }

    private fun balancedObject(source: String, start: Int): String? {
        if (start !in source.indices || source[start] != '{') return null
        var depth = 0
        var insideString = false
        var escaped = false
        val limit = minOf(source.length, start + MAX_RENDERER_CHARS)
        for (index in start until limit) {
            val char = source[index]
            if (insideString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> insideString = false
                }
            } else {
                when (char) {
                    '"' -> insideString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }
}
