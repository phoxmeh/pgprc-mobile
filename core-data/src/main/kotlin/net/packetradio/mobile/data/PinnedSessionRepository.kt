package net.packetradio.mobile.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.packetradio.mobile.data.db.PacketRadioDatabase
import net.packetradio.mobile.data.entity.toDomain
import net.packetradio.mobile.data.entity.toEntity
import net.packetradio.mobile.model.PinnedSession

/** Domain-level wrapper over [PacketRadioDatabase]'s pinned-sessions table — the desktop's `pinned_sessions.toml`. */
class PinnedSessionRepository(private val db: PacketRadioDatabase) {

    fun observeAll(): Flow<List<PinnedSession>> =
        db.pinnedSessionDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun getAll(): List<PinnedSession> = db.pinnedSessionDao().getAll().map { it.toDomain() }

    suspend fun pin(session: PinnedSession) = db.pinnedSessionDao().upsert(session.toEntity())

    suspend fun unpin(session: PinnedSession) =
        db.pinnedSessionDao().delete(session.portId, session.remote, session.unproto)
}
