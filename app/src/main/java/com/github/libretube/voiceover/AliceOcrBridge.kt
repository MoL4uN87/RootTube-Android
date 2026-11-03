package com.github.libretube.voiceover

/**
 * One in-memory, short-lived OCR request, created only by an explicit tap on
 * "Перевести Алисой AI". No screenshots or translation bodies are persisted.
 *
 * The bridge never exposes the screen of any non-Alice app to OCR.
 */
object AliceOcrBridge {
    private const val MAX_AGE_MS = 75_000L

    private var marker: String? = null
    private var started: Long = 0L
    private var answer: String? = null

    @Synchronized
    fun arm(requestMarker: String) {
        marker = requestMarker
        started = android.os.SystemClock.elapsedRealtime()
        answer = null
    }

    @Synchronized
    fun isArmed(): Boolean {
        if (marker == null) return false
        if (android.os.SystemClock.elapsedRealtime() - started > MAX_AGE_MS) {
            clear()
            return false
        }
        return answer == null
    }

    @Synchronized
    fun currentMarker(): String? = if (isArmed()) marker else null

    @Synchronized
    fun deliver(requestMarker: String, translation: String): Boolean {
        if (!isArmed() || marker != requestMarker) return false
        answer = translation
        return true
    }

    @Synchronized
    fun consume(requestMarker: String): String? {
        if (marker != requestMarker) return null
        val value = answer
        if (value != null) clear()
        return value
    }

    @Synchronized
    fun clear() {
        marker = null
        answer = null
        started = 0L
    }
}

/**
 * Parse only Alice's marked translation; never append its UI controls,
 * citations or OCR guesses from the toolbar underneath the response.
 *
 * The new prompt requests an explicit КОНЕЦ marker. Older/partial replies
 * without it may be accepted only if a recognizable answer footer is visible.
 */
object AliceOcrParser {
    private val answerFooter = Regex(
        """(?iu)(?:Источники|Источник|Sources|Ссылки|Поделиться|Копировать|Слушать|Спросите о чём угодно|Спросите о чем угодно|Интеллект:|Новый чат|Алиса AI)"""
    )
    private val sourceFooter = Regex("""(?iu)(?:Источники|Источник|Sources|Ссылки)""")
    // OCR may read thumbs-up/copy/share pictograms as nonsense ("ОФ «< ФФ").
    // Only trim this *after* a source/footer; never touch ordinary prose.
    private val toolbarNoise = Regex(
        """\s+[А-ЯЁA-Z]{1,3}\s+[«<©][^\n]*$"""
    )

    fun extract(screenText: String, marker: String): String? {
        if (!marker.matches(Regex("[0-9]{8}"))) return null
        val normalized = screenText.replace('\r', '\n')
        val begin = Regex(
            """(?m)^\s*(?:КОД|CODE)\s*[:#-]?\s*""" +
                Regex.escape(marker) + """\s*[.:—-]?\s*$""",
            RegexOption.IGNORE_CASE
        )
        val matchingStarts = begin.findAll(normalized).toList()
        // A marker occurring inside the prompt's instruction is never valid.
        if (matchingStarts.size != 1) return null
        val end = Regex(
            """^\s*(?:КОНЕЦ|СТОП|END)\s*[:#-]?\s*""" +
                Regex.escape(marker) + """\s*[.:—-]?\s*$""",
            RegexOption.IGNORE_CASE
        )
        val lines = normalized
            .substring(matchingStarts[0].range.last + 1)
            .lineSequence()
            .map { it.trim() }
            .dropWhile { it.isBlank() }
        val translated = ArrayList<String>()
        var hasEndMarker = false
        var hasFooter = false

        for (line in lines) {
            if (end.matches(line)) {
                hasEndMarker = true
                break
            }
            val footer = answerFooter.find(line)
            if (footer != null) {
                // Source labels sometimes appear on the same OCR line as the
                // end of the translation: keep only preceding words.
                val before = line.substring(0, footer.range.first).trim()
                if (before.isNotBlank()) translated.add(before)
                hasFooter = true
                break
            }
            if (translated.size >= 15) return null
            if (line.isNotBlank()) translated.add(line)
        }

        // Do not publish a streaming partial answer before Alice has supplied
        // the requested end marker or a recognizable finished-response footer.
        if (!hasEndMarker && !hasFooter) return null

        var answer = translated.joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (!hasEndMarker && hasFooter) {
            answer = answer.replace(toolbarNoise, "").trim()
        }
        if (answer.length !in 8..2500) return null
        val russianLetters = answer.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        if (russianLetters < 4 || russianLetters * 3 < answer.count { it.isLetter() }) return null
        if (answer.contains(marker) ||
            answer.contains("Переведи следующий комментарий", ignoreCase = true) ||
            answer.contains("Напиши только перевод", ignoreCase = true) ||
            answer.contains("Начни ответ", ignoreCase = true) ||
            sourceFooter.containsMatchIn(answer)
        ) return null
        return answer
    }
}
