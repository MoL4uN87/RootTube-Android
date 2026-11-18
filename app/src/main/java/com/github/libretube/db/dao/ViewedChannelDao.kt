package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.github.libretube.db.obj.ViewedChannel
import com.github.libretube.helpers.ViewedChannelsHelper
import kotlinx.coroutines.flow.Flow

@Dao
interface ViewedChannelDao {
    @Query("SELECT * FROM viewedChannel")
    fun observeAll(): Flow<List<ViewedChannel>>

    @Query("SELECT * FROM viewedChannel WHERE channelId = :channelId LIMIT 1")
    suspend fun getById(channelId: String): ViewedChannel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ViewedChannel)

    /** Merge and insert inside one transaction so simultaneous openings do not race. */
    @Transaction
    suspend fun record(item: ViewedChannel) {
        insert(ViewedChannelsHelper.merge(getById(item.channelId), item))
    }
}
