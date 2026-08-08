package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.SsidEntity

@Dao
interface SsidDao {
    @Query("SELECT * FROM ssids WHERE baseCallsign = :baseCallsign ORDER BY ssidNumber ASC")
    fun observeForBase(baseCallsign: String): Flow<List<SsidEntity>>

    @Query("SELECT * FROM ssids ORDER BY baseCallsign ASC, ssidNumber ASC")
    fun observeAll(): Flow<List<SsidEntity>>

    @Query("SELECT * FROM ssids WHERE baseCallsign = :baseCallsign AND ssidNumber = :ssidNumber")
    suspend fun getByBaseAndSsid(baseCallsign: String, ssidNumber: Int): SsidEntity?

    @Query("SELECT * FROM ssids WHERE baseCallsign = :baseCallsign")
    suspend fun getAllForBase(baseCallsign: String): List<SsidEntity>

    @Insert
    suspend fun insert(entry: SsidEntity): Long

    @Update
    suspend fun update(entry: SsidEntity)

    @Delete
    suspend fun delete(entry: SsidEntity)

    @Query("DELETE FROM ssids WHERE baseCallsign = :baseCallsign")
    suspend fun deleteAllForBase(baseCallsign: String)
}
