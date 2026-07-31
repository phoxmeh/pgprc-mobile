package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.HeardBeaconPacket

@Entity(tableName = "heard_beacons")
data class HeardBeaconEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callsign: String,
    val text: String,
    val timestamp: String,
)

fun HeardBeaconPacket.toEntity(): HeardBeaconEntity =
    HeardBeaconEntity(id = id, callsign = callsign, text = text, timestamp = timestamp)

fun HeardBeaconEntity.toDomain(): HeardBeaconPacket =
    HeardBeaconPacket(id = id, callsign = callsign, text = text, timestamp = timestamp)
