package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.CallsignEntity

@Dao
interface CallsignDao {
    @Query("SELECT * FROM callsigns ORDER BY baseCallsign ASC")
    fun observeAll(): Flow<List<CallsignEntity>>

    @Query("SELECT * FROM callsigns WHERE baseCallsign = :baseCallsign")
    suspend fun getByBase(baseCallsign: String): CallsignEntity?

    @Upsert
    suspend fun upsert(entry: CallsignEntity)

    @Delete
    suspend fun delete(entry: CallsignEntity)

    @Query(
        "UPDATE callsigns SET firstName = :firstName, lastName = :lastName, " +
            "address = :address, email = :email, lat = :lat, lon = :lon " +
            "WHERE baseCallsign = :baseCallsign",
    )
    suspend fun updateQrzData(
        baseCallsign: String,
        firstName: String?,
        lastName: String?,
        address: String?,
        email: String?,
        lat: Double?,
        lon: Double?,
    )
}
