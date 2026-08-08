package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.NetRomNodeEntity

@Dao
interface NetRomNodeDao {

    @Query("SELECT * FROM netrom_nodes ORDER BY quality DESC")
    fun observeAll(): Flow<List<NetRomNodeEntity>>

    @Query("SELECT * FROM netrom_nodes WHERE portId = :portId ORDER BY quality DESC")
    fun observeForPort(portId: String): Flow<List<NetRomNodeEntity>>

    @Query("SELECT * FROM netrom_nodes WHERE portId = :portId ORDER BY quality DESC")
    suspend fun getAllForPort(portId: String): List<NetRomNodeEntity>

    @Query("SELECT * FROM netrom_nodes WHERE portId = :portId AND callsign = :callsign LIMIT 1")
    suspend fun getByPortAndCallsign(portId: String, callsign: String): NetRomNodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NetRomNodeEntity)

    @Update
    suspend fun update(entity: NetRomNodeEntity)

    @Query(
        "UPDATE netrom_nodes SET obsolescenceCounter = obsolescenceCounter - 1 " +
            "WHERE portId = :portId AND callsign NOT IN (:excludeCallsigns)",
    )
    suspend fun decrementObsolescenceExcluding(portId: String, excludeCallsigns: List<String>)

    @Query("UPDATE netrom_nodes SET obsolescenceCounter = obsolescenceCounter - 1 WHERE portId = :portId")
    suspend fun decrementObsolescenceAll(portId: String)

    @Query("DELETE FROM netrom_nodes WHERE portId = :portId AND obsolescenceCounter <= 0")
    suspend fun deleteStale(portId: String)

    @Query("DELETE FROM netrom_nodes WHERE portId = :portId")
    suspend fun clearForPort(portId: String)
}
