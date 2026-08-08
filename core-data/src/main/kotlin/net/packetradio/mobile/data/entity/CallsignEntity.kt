package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.CallsignEntry

@Entity(tableName = "callsigns")
data class CallsignEntity(
    @PrimaryKey val baseCallsign: String,
    val name: String?,
    val location: String?,
    val notes: String?,
)

fun CallsignEntity.toDomain(ssids: List<SsidEntity>): CallsignEntry = CallsignEntry(
    baseCallsign = baseCallsign,
    name = name,
    location = location,
    notes = notes,
    ssids = ssids.map { it.toDomain() },
)
