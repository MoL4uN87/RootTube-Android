package com.github.libretube.voiceover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AliceOcrParserTest {
    private val code = "48391274"

    @Test
    fun extractsOnlyTheReplyAfterPromptAndAnswerMarkers() {
        val screen = """
            Алиса AI
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
    fun acceptsAnswerWhenOriginalPromptIsNoLongerVisible() {
        val screen = "Алиса AI\nКОД $code\nЭто действительно отличный курс!\nСпросите о чём угодно"
        assertEquals("Это действительно отличный курс!", AliceOcrParser.extract(screen, code))
    }

    @Test
    fun doesNotAcceptCodeMentionedOnlyInPrompt() {
        val screen = "Начни ответ с отдельной строки КОД $code, затем переведи\nПривет, мир"
        assertNull(AliceOcrParser.extract(screen, code))
    }

    @Test
    fun doesNotReadAnotherRequest() {
        assertNull(AliceOcrParser.extract("КОД 12345678\nПеревод на русский", code))
    }

    @Test
    fun rejectsNonRussianResponse() {
        assertNull(AliceOcrParser.extract("КОД $code\nHello world", code))
    }

    @Test
    fun rejectsTwoStandaloneAnswerMarkers() {
        assertNull(AliceOcrParser.extract(
            "КОД $code\nЭто русский перевод\nКОД $code\nДругой русский перевод", code
        ))
    }

    @Test
    fun rejectsMarkerEmbeddedInOtherText() {
        assertNull(AliceOcrParser.extract(
            "Некоторый текст КОД $code\nЭто точно не проверенный перевод", code
        ))
    }
    @Test
    fun ignoresSourcesAndUiTextAfterExplicitEndMarker() {
        val screen = """
            Алиса AI
            КОД $code
            Старый мир умирает, а новый с трудом пробивается на свет.
            Сейчас время чудовищ.
            КОНЕЦ $code
            Источники  3
            Копировать
            © с сайта
        """.trimIndent()
        assertEquals(
            "Старый мир умирает, а новый с трудом пробивается на свет. Сейчас время чудовищ.",
            AliceOcrParser.extract(screen, code)
        )
    }

    @Test
    fun trimsRealWorldOcrGarbageBeforeInlineSources() {
        val screen =
            "КОД $code\nСтарый мир умирает, а новый с трудом пробивается на свет.\n" +
            "Сейчас время чудовищ ОФ «< ФФ ФФ ©)Э Источники --\nКопировать"
        assertEquals(
            "Старый мир умирает, а новый с трудом пробивается на свет. Сейчас время чудовищ",
            AliceOcrParser.extract(screen, code)
        )
    }

    @Test
    fun rejectsStreamingPartialResponseWithoutBoundary() {
        assertNull(AliceOcrParser.extract(
            "КОД $code\nСтарый мир умирает, а новый с трудом", code
        ))
    }

    @Test
    fun extractsEmojiPlaceholderOnlyWithinMarkedBlock() {
        val screen =
            "КОД $code\nКакая смешная история СМАЙЛ1\nКОНЕЦ $code\nИсточники 2"
        assertEquals(
            "Какая смешная история СМАЙЛ1",
            AliceOcrParser.extract(screen, code)
        )
    }

    @Test
    fun doesNotAcceptOnlySourcesAfterReplyMarker() {
        assertNull(AliceOcrParser.extract(
            "КОД $code\nИсточники\nКопировать", code
        ))
    }

    @Test
    fun doesNotAcceptSourceButtonTextAsTranslation() {
        assertNull(AliceOcrParser.extract(
            "КОД $code\nКопировать\nСпросите о чём угодно", code
        ))
    }

}
