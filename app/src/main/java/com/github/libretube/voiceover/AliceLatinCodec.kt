package com.github.libretube.voiceover

/**
 * Names and abbreviations are intentionally not translated. Russian OCR can
 * otherwise turn TPUSA into Cyrillic look-alikes. Protect each acronym or
 * mixed-case brand before sending a comment to Alice, then put the original
 * Latin spelling back after screen recognition.
 */
object AliceLatinCodec {
    data class Encoded(val text: String, val terms: List<String>)

    private val word = Regex("""(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9]*)(?![A-Za-z0-9])""")
    private val marker = Regex(
        """(?iu)(?<![\p{L}\p{N}])(?:СЛОВО|СЛОВ0|CЛOBO|CЛОВO|СЛOВO)\s*[:#_-]?\s*(\d{1,2})(?![\p{L}\p{N}])"""
    )

    fun encode(text: String): Encoded {
        val terms = ArrayList<String>()
        val encoded = word.replace(text) { match ->
            val candidate = match.groupValues[1]
            val letters = candidate.filter { it in 'A'..'Z' || it in 'a'..'z' }
            val allCaps = letters.length >= 2 && letters.all { it in 'A'..'Z' }
            val mixedCaseBrand = letters.length >= 3 &&
                letters.any { it in 'a'..'z' } &&
                letters.drop(1).any { it in 'A'..'Z' }
            if ((!allCaps && !mixedCaseBrand) || terms.size >= 30) {
                candidate
            } else {
                terms.add(candidate)
                " СЛОВО${terms.size} "
            }
        }
        return Encoded(encoded.replace(Regex(" {2,}"), " "), terms)
    }

    /**
     * Refuse a damaged brand name rather than silently inserting gibberish.
     * A missing marker is acceptable only if the reply already contains the
     * original Latin term intact.
     */
    fun restore(text: String, terms: List<String>): String? {
        if (terms.isEmpty()) return text.trim()
        val present = BooleanArray(terms.size)
        var output = marker.replace(text) { match ->
            val index = match.groupValues[1].toIntOrNull()?.minus(1)
            if (index != null && index in terms.indices) {
                present[index] = true
                terms[index]
            } else match.value
        }
        for (index in terms.indices) {
            if (present[index]) continue
            val exact = Regex(
                """(?i)(?<![A-Za-z0-9])""" +
                    Regex.escape(terms[index]) +
                    """(?![A-Za-z0-9])"""
            )
            if (!exact.containsMatchIn(output)) return null
        }
        output = output.replace(Regex(" {2,}"), " ").trim()
        return output
    }
}
