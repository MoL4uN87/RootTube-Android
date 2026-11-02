package com.github.libretube.voiceover

/**
 * Tesseract is a text recognizer and cannot reliably recognize emoji glyphs.
 * Replace original emoji clusters with plain-text markers before asking Alice;
 * restore the exact original Unicode sequences after OCR.
 */
object AliceEmojiCodec {
    data class Encoded(val text: String, val emojis: List<String>)

    fun encode(text: String): Encoded {
        val out = StringBuilder()
        val emojis = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            val length = Character.charCount(cp)
            val keycap = (cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code) &&
                isKeycapAfter(text, i + length)
            if (!isEmojiBase(cp) && !keycap) {
                out.appendCodePoint(cp)
                i += length
                continue
            }
            val start = i
            i += length
            if (cp in 0x1F1E6..0x1F1FF && i < text.length) {
                val second = Character.codePointAt(text, i)
                if (second in 0x1F1E6..0x1F1FF) i += Character.charCount(second)
            }
            i = consumeEmojiModifiers(text, i)
            while (i < text.length && Character.codePointAt(text, i) == 0x200D) {
                val nextStart = i + 1
                if (nextStart >= text.length) break
                val next = Character.codePointAt(text, nextStart)
                if (!isEmojiBase(next)) break
                i = consumeEmojiModifiers(text, nextStart + Character.charCount(next))
            }
            val sequence = text.substring(start, i)
            emojis.add(sequence)
            out.append(" СМАЙЛ").append(emojis.size).append(' ')
        }
        return Encoded(out.toString().replace(Regex(" {2,}"), " "), emojis)
    }

    fun restore(translation: String, emojis: List<String>): String {
        if (emojis.isEmpty()) return translation.trim()
        var result = translation
        val represented = BooleanArray(emojis.size)
        // Letter variants and extra spaces accommodate common OCR mistakes.
        val token = Regex(
            "(?iu)(?<![\\p{L}\\p{N}])(?:СМАЙЛ|СМАИЛ|CМAЙЛ|CМАЙЛ|СМAЙЛ|СМАЙJI)\\s*[:№#_-]?\\s*(\\d{1,2})(?![\\p{L}\\p{N}])"
        )
        result = token.replace(result) { match ->
            val number = match.groupValues[1].toIntOrNull()
            if (number != null && number in 1..emojis.size) {
                represented[number - 1] = true
                emojis[number - 1]
            } else match.value
        }
        val missing = emojis.indices.filter { !represented[it] && !result.contains(emojis[it]) }
        if (missing.isNotEmpty()) {
            result = result.trimEnd() + " " + missing.joinToString(" ") { emojis[it] }
        }
        return result.replace(Regex(" {2,}"), " ").trim()
    }

    private fun isKeycapAfter(text: String, offset: Int): Boolean {
        if (offset >= text.length) return false
        val cp = Character.codePointAt(text, offset)
        if (cp == 0x20E3) return true
        return cp == 0xFE0F && offset + 1 < text.length &&
            Character.codePointAt(text, offset + 1) == 0x20E3
    }

    private fun consumeEmojiModifiers(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 ||
                cp in 0x1F3FB..0x1F3FF
            ) i += Character.charCount(cp)
            else break
        }
        return i
    }

    private fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C ||
            cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp == 0x2934 || cp == 0x2935 || cp == 0x3030 ||
            cp == 0x303D || cp == 0x3297 || cp == 0x3299
}
