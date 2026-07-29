package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.BeaconEntity

@Dao
interface BeaconDao {
    @Query("SELECT * FROM beacons ORDER BY rowid ASC")
    fun observeAll(): Flow<List<BeaconEntity>>

    @Query("SELECT * FROM beacons WHERE enabled = 1")
    suspend fun getEnabled(): List<BeaconEntity>

    @Upsert
    suspend fun upsert(beacon: BeaconEntity)

    @Delete
    suspend fun delete(beacon: BeaconEntity)
}
