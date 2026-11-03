package com.github.libretube.voiceover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliceLatinCodecTest {
    @Test
    fun protectsTpusaAndOtherAcronymsButNotOrdinaryEnglish() {
        val encoded = AliceLatinCodec.encode(
            "TPUSA and NASA are mentioned in this comment about USA."
        )
        assertEquals(listOf("TPUSA", "NASA", "USA"), encoded.terms)
        assertFalse(encoded.text.contains("TPUSA"))
        assertTrue(encoded.text.contains("СЛОВО1"))
        assertTrue(encoded.text.contains("СЛОВО2"))
        assertTrue(encoded.text.contains("СЛОВО3"))
        assertTrue(encoded.text.contains("and"))
        assertEquals(
            "Организации TPUSA и NASA упоминаются в США: USA.",
            AliceLatinCodec.restore(
                "Организации СЛОВО1 и СЛОВО2 упоминаются в США: СЛОВО3.",
                encoded.terms
            )
        )
    }

    @Test
    fun preservesCamelCaseProductNames() {
        val encoded = AliceLatinCodec.encode(
            "I watch YouTube and use iPhone, OpenAI and Android."
        )
        assertEquals(listOf("YouTube", "iPhone", "OpenAI"), encoded.terms)
        assertEquals(
            "Я смотрю YouTube с iPhone и OpenAI.",
            AliceLatinCodec.restore(
                "Я смотрю СЛОВО1 с СЛОВО2 и СЛОВО3.", encoded.terms
            )
        )
    }

    @Test
    fun acceptsTypicalMixedLatinCyrillicOcrMarker() {
        val encoded = AliceLatinCodec.encode("TPUSA")
        assertEquals(
            "Обсуждают TPUSA.",
            AliceLatinCodec.restore("Обсуждают CЛOBO1.", encoded.terms)
        )
    }

    @Test
    fun rejectsMissingMarkerRatherThanReplacingWithRussianLookalikes() {
        assertNull(AliceLatinCodec.restore(
            "Упоминается ТПУСА в комментарии.",
            listOf("TPUSA")
        ))
    }

    @Test
    fun acceptsExactOriginalNameWithoutPlaceholder() {
        assertEquals(
            "Речь о TPUSA.",
            AliceLatinCodec.restore("Речь о TPUSA.", listOf("TPUSA"))
        )
    }

    @Test
    fun leavesOrdinaryEnglishWordsForTranslation() {
        val encoded = AliceLatinCodec.encode("This is a great video!")
        assertTrue(encoded.terms.isEmpty())
        assertEquals("This is a great video!", encoded.text)
        assertEquals("Это отличное видео!", AliceLatinCodec.restore(
            "Это отличное видео!", encoded.terms
        ))
    }

    @Test
    fun supportsEmojiEncodingInSameSentence() {
        val emoji = AliceEmojiCodec.encode("I love TPUSA 😂")
        val latin = AliceLatinCodec.encode(emoji.text)
        val recognized = "Я люблю СЛОВО1 СМАЙЛ1"
        val names = AliceLatinCodec.restore(recognized, latin.terms)
        assertEquals("Я люблю TPUSA 😂", AliceEmojiCodec.restore(
            names!!, emoji.emojis
        ))
    }
}
