package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.NotifiedPacketEntity

@Dao
interface NotifiedPacketDao {
    @Query("SELECT * FROM notified_packets ORDER BY id DESC")
    fun observeAll(): Flow<List<NotifiedPacketEntity>>

    /** Returns the generated row id. */
    @Insert
    suspend fun insert(packet: NotifiedPacketEntity): Long

    @Delete
    suspend fun delete(packet: NotifiedPacketEntity)
}
