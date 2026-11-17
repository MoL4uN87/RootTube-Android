package com.github.libretube.ui.comments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeCommentWebLinksTest {

    @Test
    fun createsVideoUrlOnOfficialYouTubeHost() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            YouTubeCommentWebLinks.video("dQw4w9WgXcQ")
        )
    }

    @Test
    fun createsCommentDeepLink() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&lc=Ugy_ab-123.XYZ",
            YouTubeCommentWebLinks.comment("dQw4w9WgXcQ", "Ugy_ab-123.XYZ")
        )
    }

    @Test
    fun encodesCommentQuerySeparatorsRatherThanInjectingOtherParameters() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&lc=Ugz%26next%3Dhttps%3A%2F%2Fevil.invalid",
            YouTubeCommentWebLinks.comment("dQw4w9WgXcQ", "Ugz&next=https://evil.invalid")
        )
    }

    @Test
    fun rejectsInvalidVideoIds() {
        assertNull(YouTubeCommentWebLinks.video(""))
        assertNull(YouTubeCommentWebLinks.video("https://bad.co"))
        assertNull(YouTubeCommentWebLinks.video("abcdefghijk&redirect=evil"))
        assertNull(YouTubeCommentWebLinks.comment("../../../../bad", "Ugx123"))
    }

    @Test
    fun rejectsBlankOversizedAndControlCharacterCommentIds() {
        assertNull(YouTubeCommentWebLinks.comment("dQw4w9WgXcQ", ""))
        assertNull(YouTubeCommentWebLinks.comment("dQw4w9WgXcQ", "  "))
        assertNull(YouTubeCommentWebLinks.comment("dQw4w9WgXcQ", "x".repeat(513)))
        assertNull(YouTubeCommentWebLinks.comment("dQw4w9WgXcQ", "Ugx\nabc"))
    }
}
