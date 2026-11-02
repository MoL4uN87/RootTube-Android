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
 * Extract a translation only after the Alice reply's dedicated marker line.
 * Unlike the previous implementation, the original prompt need not be on
 * screen: it can be scrolled away. Never accept a marker embedded in the
 * prompt instruction or a reply that does not contain the marker line.
 */
object AliceOcrParser {
    private val footerPrefixes = listOf(
        "Спросите о чём угодно",
        "Спросите о чем угодно",
        "Интеллект:",
        "Ответить",
        "Поделиться",
        "Копировать",
        "Слушать",
        "Новый чат",
        "Алиса AI"
    )

    fun extract(screenText: String, marker: String): String? {
        if (!marker.matches(Regex("[0-9]{8}"))) return null
        val normalized = screenText.replace('\r', '\n')
        // Only a stand-alone code line belongs to a completed response.
        // The user's prompt mentions the code inside an instruction sentence.
        val codeLine = Regex(
            "(?m)^\\s*(?:КОД|Код|код|CODE)\\s*[:#-]?\\s*" +
                Regex.escape(marker) + "\\s*[.:—-]?\\s*$"
        )
        val matches = codeLine.findAll(normalized).toList()
        if (matches.size != 1) return null

        val lines = normalized.substring(matches[0].range.last + 1)
            .lineSequence()
            .map { it.trim() }
            .dropWhile { it.isBlank() }
            .takeWhile { line ->
                footerPrefixes.none { line.startsWith(it, ignoreCase = true) }
            }
            .take(10)
            .filter { it.isNotBlank() }
            .toList()
        val answer = lines.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (answer.length !in 8..2500) return null
        val russianLetters = answer.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        if (russianLetters < 4 || russianLetters * 3 < answer.count { it.isLetter() }) return null
        if (answer.contains(marker) ||
            answer.contains("Переведи следующий комментарий", ignoreCase = true) ||
            answer.contains("Напиши только перевод", ignoreCase = true) ||
            answer.contains("Начни ответ", ignoreCase = true)
        ) return null
        return answer
    }
}
