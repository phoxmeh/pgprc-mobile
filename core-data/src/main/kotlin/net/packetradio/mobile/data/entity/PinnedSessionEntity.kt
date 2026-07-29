package net.packetradio.mobile.data.entity

import androidx.room.Entity
import net.packetradio.mobile.model.PinnedSession

@Entity(tableName = "pinned_sessions", primaryKeys = ["portId", "remote", "unproto"])
data class PinnedSessionEntity(
    val portId: String,
    val remote: String,
    val via: String,
    val unproto: Boolean,
)

fun PinnedSession.toEntity(): PinnedSessionEntity =
    PinnedSessionEntity(portId = portId, remote = remote, via = via, unproto = unproto)

fun PinnedSessionEntity.toDomain(): PinnedSession =
    PinnedSession(portId = portId, remote = remote, via = via, unproto = unproto)
