package com.github.libretube.voiceover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AliceOcrParserTest {
    private val code = "48391274"

    @Test
    fun extractsOnlyTheReplyAfterTwoMarkers() {
        val screen = """
            алиса ai
            Переведи комментарий. Начни ответ с КОД $code
            This lecture is very useful.
            КОД $code
            Эта лекция очень полезная.
            Спросите о чём угодно
            Интеллект: Авто
        """.trimIndent()
        assertEquals("Эта лекция очень полезная.", AliceOcrParser.extract(screen, code))
    }

    @Test
    fun doesNotReadAnUnansweredPrompt() {
        assertNull(AliceOcrParser.extract("Начни ответ с КОД $code", code))
    }

    @Test
    fun neverReadsAnotherJob() {
        val screen = "КОД 12345678\nКОД 12345678\nРусский текст"
        assertNull(AliceOcrParser.extract(screen, code))
    }

    @Test
    fun rejectsTextThatIsNotRussian() {
        assertNull(AliceOcrParser.extract(
            "КОД $code\nКОД $code\nHello world", code
        ))
    }

    @Test
    fun rejectsTooManyMarkersInLongConversation() {
        assertNull(AliceOcrParser.extract(
            "КОД $code\nКОД $code\nКОД $code\nПеревод на русский", code
        ))
    }
}
