package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.HeardBeaconEntity

@Dao
interface HeardBeaconDao {
    @Query("SELECT * FROM heard_beacons WHERE callsign = :callsign ORDER BY id DESC")
    fun observeForCallsign(callsign: String): Flow<List<HeardBeaconEntity>>

    @Query("SELECT * FROM heard_beacons WHERE callsign = :callsign ORDER BY id DESC LIMIT 1")
    suspend fun mostRecent(callsign: String): HeardBeaconEntity?

    @Insert
    suspend fun insert(packet: HeardBeaconEntity)

    /** Keeps only the newest [keep] rows for [callsign] — "last N unique" enforcement. */
    @Query(
        "DELETE FROM heard_beacons WHERE callsign = :callsign AND id NOT IN " +
            "(SELECT id FROM heard_beacons WHERE callsign = :callsign ORDER BY id DESC LIMIT :keep)",
    )
    suspend fun pruneOldest(callsign: String, keep: Int)

    @Query("DELETE FROM heard_beacons WHERE callsign = :callsign")
    suspend fun deleteForCallsign(callsign: String)
}
