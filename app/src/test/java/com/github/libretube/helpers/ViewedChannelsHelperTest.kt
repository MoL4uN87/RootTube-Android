package com.github.libretube.helpers

import com.github.libretube.db.obj.ViewedChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewedChannelsHelperTest {
    private val channelA = "UC" + "a".repeat(22)
    private val channelB = "UC" + "b".repeat(22)
    private val channelC = "UC" + "c".repeat(22)

    @Test
    fun normalizesCanonicalChannelLinksAndRejectsInvalidIdentifiers() {
        assertEquals(channelA, ViewedChannelsHelper.normalizeChannelId(channelA))
        assertEquals(
            channelA,
            ViewedChannelsHelper.normalizeChannelId("https://www.youtube.com/channel/$channelA")
        )
        assertNull(ViewedChannelsHelper.normalizeChannelId(""))
        assertNull(ViewedChannelsHelper.normalizeChannelId("/@handle"))
        assertNull(ViewedChannelsHelper.normalizeChannelId("UCinvalid?next=evil"))
    }

    @Test
    fun reopeningChannelUpdatesOneExistingRecord() {
        val old = ViewedChannel(channelA, "Old", null, 500, 100)
        val newer = ViewedChannel(channelA, "New", "https://img.example/a", 800, 200)

        assertEquals(
            ViewedChannel(channelA, "New", "https://img.example/a", 800, 200),
            ViewedChannelsHelper.merge(old, newer)
        )
    }

    @Test
    fun missingSubscriberCountAndAvatarDoNotEraseKnownDetails() {
        val old = ViewedChannel(channelA, "Known", "https://img.example/a", 900, 100)
        val sparse = ViewedChannel(channelA, "", null, -1, 200)

        assertEquals(
            ViewedChannel(channelA, "Known", "https://img.example/a", 900, 200),
            ViewedChannelsHelper.merge(old, sparse)
        )
        assertEquals(900, ViewedChannelsHelper.merge(old, sparse.copy(subscriberCount = 0)).subscriberCount)
    }

    @Test
    fun ranksBySubscribersThenRecentOpeningThenChannelId() {
        val rows = listOf(
            ViewedChannel(channelA, "A", null, 1000, 10),
            ViewedChannel(channelB, "B", null, 5000, 20),
            ViewedChannel(channelC, "C", null, 5000, 30)
        )

        assertEquals(
            listOf(channelC, channelB, channelA),
            ViewedChannelsHelper.rank(rows).map { it.channelId }
        )
    }

    @Test
    fun equalSubscriberAndOpeningCountsHaveDeterministicChannelOrder() {
        val rows = listOf(
            ViewedChannel(channelB, "B", null, 50, 10),
            ViewedChannel(channelA, "A", null, 50, 10)
        )
        assertEquals(listOf(channelA, channelB), ViewedChannelsHelper.rank(rows).map { it.channelId })
    }
}
