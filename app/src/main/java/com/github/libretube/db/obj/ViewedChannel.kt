package com.github.libretube.db.obj

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A snapshot of one channel that the user has opened, independently of subscriptions. */
@Entity(tableName = "viewedChannel")
data class ViewedChannel(
    @PrimaryKey val channelId: String,
    val name: String,
    val avatarUrl: String? = null,
    val subscriberCount: Long = -1,
    val lastOpenedAt: Long = 0
)
