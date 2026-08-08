package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.NetRomNodeEntry

@Entity(
    tableName = "netrom_nodes",
    indices = [Index(value = ["portId", "callsign"], unique = true)],
)
data class NetRomNodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val portId: String,
    val callsign: String,
    val alias: String,
    val bestNeighborCallsign: String,
    val quality: Int,
    val obsolescenceCounter: Int,
)

fun NetRomNodeEntity.toDomain() = NetRomNodeEntry(
    id = id,
    portId = portId,
    callsign = callsign,
    alias = alias,
    bestNeighborCallsign = bestNeighborCallsign,
    quality = quality,
    obsolescenceCounter = obsolescenceCounter,
)
