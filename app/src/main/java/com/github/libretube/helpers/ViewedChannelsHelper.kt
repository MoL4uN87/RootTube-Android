package com.github.libretube.helpers

import com.github.libretube.db.DatabaseHolder
import com.github.libretube.db.obj.ViewedChannel
import com.github.libretube.extensions.toID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ViewedChannelsHelper {
    private val channelIdPattern = Regex("UC[A-Za-z0-9_-]{22}")

    /** Only canonical channel IDs are safe as durable, unique keys. */
    fun normalizeChannelId(channelUrlOrId: String?): String? {
        if (channelUrlOrId.isNullOrBlank()) return null
        val id = channelUrlOrId.trim().toID()
            .removePrefix("/").removeSuffix("/")
        return id.takeIf { channelIdPattern.matches(it) }
    }

    fun merge(existing: ViewedChannel?, observed: ViewedChannel): ViewedChannel {
        if (existing == null) return observed.copy(
            name = observed.name.ifBlank { observed.channelId },
            subscriberCount = observed.subscriberCount.coerceAtLeast(-1)
        )
        require(existing.channelId == observed.channelId)

        return existing.copy(
            name = observed.name.ifBlank { existing.name },
            avatarUrl = observed.avatarUrl?.takeIf { it.isNotBlank() } ?: existing.avatarUrl,
            subscriberCount = observed.subscriberCount.takeIf { it > 0 }
                ?: existing.subscriberCount.takeIf { it > 0 }
                ?: observed.subscriberCount.coerceAtLeast(existing.subscriberCount),
            lastOpenedAt = maxOf(existing.lastOpenedAt, observed.lastOpenedAt)
        )
    }

    fun rank(channels: List<ViewedChannel>): List<ViewedChannel> = channels.sortedWith(
        compareByDescending<ViewedChannel> { it.subscriberCount }
            .thenByDescending { it.lastOpenedAt }
            .thenBy { it.channelId }
    )

    suspend fun record(
        channelUrlOrId: String?,
        name: String?,
        avatarUrl: String?,
        subscriberCount: Long,
        openedAt: Long = System.currentTimeMillis()
    ) {
        val channelId = normalizeChannelId(channelUrlOrId) ?: return
        val snapshot = ViewedChannel(
            channelId = channelId,
            name = name.orEmpty(),
            avatarUrl = avatarUrl,
            subscriberCount = subscriberCount,
            lastOpenedAt = openedAt
        )
        withContext(Dispatchers.IO) {
            DatabaseHolder.Database.viewedChannelDao().record(snapshot)
        }
    }
}
