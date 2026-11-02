package com.github.libretube.voiceover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AliceEmojiCodecTest {
    @Test
    fun preservesBasicEmoji() {
        val source = "Haha 😂 what a joke! 🔥"
        val encoded = AliceEmojiCodec.encode(source)
        assertEquals(listOf("😂", "🔥"), encoded.emojis)
        assertFalse(encoded.text.contains("😂"))
        assertTrue(encoded.text.contains("СМАЙЛ1"))
        assertTrue(encoded.text.contains("СМАЙЛ2"))
        assertEquals(
            "Ха-ха 😂 какая шутка! 🔥",
            AliceEmojiCodec.restore("Ха-ха СМАЙЛ1 какая шутка! СМАЙЛ2", encoded.emojis)
        )
    }

    @Test
    fun preservesCombinedEmojiAndFlags() {
        val encoded = AliceEmojiCodec.encode("Cool 👩‍💻 👍🏽 🇷🇺 1️⃣")
        assertEquals(listOf("👩‍💻", "👍🏽", "🇷🇺", "1️⃣"), encoded.emojis)
        assertEquals(
            "Круто 👩‍💻 👍🏽 🇷🇺 1️⃣",
            AliceEmojiCodec.restore(
                "Круто СМАЙЛ1 СМАЙЛ2 СМАЙЛ3 СМАЙЛ4", encoded.emojis
            )
        )
    }

    @Test
    fun retainsEmojiWhenAliceDropsMarker() {
        val encoded = AliceEmojiCodec.encode("Well done 😄")
        assertEquals("Молодец 😄", AliceEmojiCodec.restore("Молодец", encoded.emojis))
    }

    @Test
    fun doesNotDuplicateEmojiAlreadyInCopiedResponse() {
        val encoded = AliceEmojiCodec.encode("Well done 😄")
        assertEquals(
            "Молодец 😄",
            AliceEmojiCodec.restore("Молодец 😄", encoded.emojis)
        )
    }

    @Test
    fun leavesPlainTextUntouched() {
        val encoded = AliceEmojiCodec.encode("Just some words.")
        assertTrue(encoded.emojis.isEmpty())
        assertEquals("Просто слова.", AliceEmojiCodec.restore("Просто слова.", encoded.emojis))
    }
}
