package com.github.libretube.ui.comments

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Links to the official YouTube site; no authenticated requests are made by RootTube. */
object YouTubeCommentWebLinks {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    fun video(videoId: String): String? {
        if (!videoIdPattern.matches(videoId)) return null
        return "https://www.youtube.com/watch?v=$videoId"
    }

    fun comment(videoId: String, commentId: String): String? {
        val videoUrl = video(videoId) ?: return null
        if (commentId.isBlank() || commentId.length > 512 || commentId.any { Character.isISOControl(it) }) {
            return null
        }
        val encodedId = URLEncoder.encode(commentId, StandardCharsets.UTF_8.name())
        return "$videoUrl&lc=$encodedId"
    }
}
