package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.NotifiedPacket

@Entity(tableName = "notified_packets")
data class NotifiedPacketEntity(
    @PrimaryKey val id: Long,
    val portId: String,
    val line: String,
    val timestamp: String,
)

fun NotifiedPacket.toEntity(): NotifiedPacketEntity =
    NotifiedPacketEntity(id = id, portId = portId, line = line, timestamp = timestamp)

fun NotifiedPacketEntity.toDomain(): NotifiedPacket =
    NotifiedPacket(id = id, portId = portId, line = line, timestamp = timestamp)
