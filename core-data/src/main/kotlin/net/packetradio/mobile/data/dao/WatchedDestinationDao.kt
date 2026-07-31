package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.WatchedDestinationEntity

@Dao
interface WatchedDestinationDao {
    @Query("SELECT * FROM watched_destinations ORDER BY destination ASC")
    fun observeAll(): Flow<List<WatchedDestinationEntity>>

    @Query("SELECT * FROM watched_destinations WHERE enabled = 1")
    suspend fun getEnabled(): List<WatchedDestinationEntity>

    @Query("SELECT * FROM watched_destinations WHERE id = :id")
    suspend fun getById(id: String): WatchedDestinationEntity?

    @Upsert
    suspend fun upsert(entry: WatchedDestinationEntity)

    @Delete
    suspend fun delete(entry: WatchedDestinationEntity)
}
