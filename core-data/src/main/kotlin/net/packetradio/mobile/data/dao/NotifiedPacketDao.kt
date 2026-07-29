package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.NotifiedPacketEntity

@Dao
interface NotifiedPacketDao {
    @Query("SELECT * FROM notified_packets ORDER BY id DESC")
    fun observeAll(): Flow<List<NotifiedPacketEntity>>

    @Upsert
    suspend fun upsert(packet: NotifiedPacketEntity)

    @Delete
    suspend fun delete(packet: NotifiedPacketEntity)
}
