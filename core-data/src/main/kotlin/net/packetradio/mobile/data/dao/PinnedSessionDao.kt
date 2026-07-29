package net.packetradio.mobile.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.packetradio.mobile.data.entity.PinnedSessionEntity

@Dao
interface PinnedSessionDao {
    @Query("SELECT * FROM pinned_sessions")
    fun observeAll(): Flow<List<PinnedSessionEntity>>

    @Query("SELECT * FROM pinned_sessions")
    suspend fun getAll(): List<PinnedSessionEntity>

    @Upsert
    suspend fun upsert(session: PinnedSessionEntity)

    @Query("DELETE FROM pinned_sessions WHERE portId = :portId AND remote = :remote AND unproto = :unproto")
    suspend fun delete(portId: String, remote: String, unproto: Boolean)
}
