package com.github.libretube.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingCategoryTest {

    @Test
    fun keepsOriginalKiosksAheadOfSearchTopics() {
        assertEquals(
            listOf("GAMING", "TRAILERS", "PODCASTS", "MUSIC", "LIVE"),
            TrendingCategory.entries.take(5).map { it.name }
        )
        assertTrue(TrendingCategory.entries.take(5).all { it.searchQuery == null })
    }

    @Test
    fun hasSixVideoSearchCategoriesAndOnePersonalTab() {
        assertEquals(
            listOf("SPORTS", "TECHNOLOGY", "CARS", "NEWS", "EDUCATION", "HUMOR"),
            TrendingCategory.entries.filter { it.searchQuery != null }.map { it.name }
        )
        assertTrue(TrendingCategory.entries.filter { it.searchQuery != null }.all {
            it.searchQuery!!.isNotBlank() && !it.isPersonalRanking
        })
        assertEquals(12, TrendingCategory.entries.size)
        assertEquals(TrendingCategory.TOP_MY_CHANNELS, TrendingCategory.entries.last())
        assertTrue(TrendingCategory.TOP_MY_CHANNELS.isPersonalRanking)
        assertFalse(TrendingCategory.GAMING.isPersonalRanking)
    }
}
