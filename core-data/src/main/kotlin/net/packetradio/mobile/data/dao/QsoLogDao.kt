package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.QsoLogEntryEntity

@Dao
interface QsoLogDao {
    @Query("SELECT * FROM qso_log ORDER BY id ASC")
    fun observeAll(): Flow<List<QsoLogEntryEntity>>

    @Query("SELECT * FROM qso_log ORDER BY id ASC")
    suspend fun getAll(): List<QsoLogEntryEntity>

    /** The still-open entry for this (port, callsign), if any — mirrors the
     *  desktop's "find-and-fill the matching open entry's ended on close". */
    @Query("SELECT * FROM qso_log WHERE portId = :portId AND callsign = :callsign AND ended IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun findOpen(portId: String, callsign: String): QsoLogEntryEntity?

    @Upsert
    suspend fun upsert(entry: QsoLogEntryEntity)
}
