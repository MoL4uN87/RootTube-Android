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
 * Pull only the Russian reply below the *second* occurrence of a random code:
 * the first belongs to our prompt bubble, the second to Alice's answer.
 * Never accept text from unrelated screens or a still-unanswered request.
 */
object AliceOcrParser {
    fun extract(screenText: String, marker: String): String? {
        if (!marker.matches(Regex("[0-9]{8}"))) return null
        val normalized = screenText.replace('\r', '\n')
        val indices = Regex(Regex.escape(marker)).findAll(normalized).toList()
        if (indices.size != 2) return null
        val after = normalized.substring(indices[1].range.last + 1)
            .trimStart(' ', ':', '.', '-', '—', '\n', '\t')
        val answer = after.lineSequence()
            .takeWhile { line ->
                val t = line.trim()
                !t.startsWith("Спросите о чём угодно", ignoreCase = true) &&
                    !t.startsWith("Спросите о чем угодно", ignoreCase = true) &&
                    !t.startsWith("Интеллект:", ignoreCase = true) &&
                    !t.startsWith("Ответить", ignoreCase = true)
            }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (answer.length !in 8..2500) return null
        if (answer.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' } < 4) return null
        if (answer.contains(marker) || answer.contains("Переведи следующий комментарий")) return null
        return answer
    }
}
