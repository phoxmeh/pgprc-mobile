package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.Beacon

@Entity(tableName = "beacons")
data class BeaconEntity(
    @PrimaryKey val id: String,
    val portId: String,
    val dest: String,
    val via: String,
    val message: String,
    val intervalSecs: Int,
    val enabled: Boolean,
)

fun Beacon.toEntity(): BeaconEntity = BeaconEntity(
    id = id, portId = portId, dest = dest, via = via, message = message,
    intervalSecs = intervalSecs, enabled = enabled,
)

fun BeaconEntity.toDomain(): Beacon = Beacon(
    id = id, portId = portId, dest = dest, via = via, message = message,
    intervalSecs = intervalSecs, enabled = enabled,
)
