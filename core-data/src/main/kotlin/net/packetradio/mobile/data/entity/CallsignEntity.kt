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
    val firstName: String? = null,
    val lastName: String? = null,
    val address: String? = null,
    val email: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

fun CallsignEntity.toDomain(ssids: List<SsidEntity>): CallsignEntry = CallsignEntry(
    baseCallsign = baseCallsign,
    name = name,
    location = location,
    notes = notes,
    firstName = firstName,
    lastName = lastName,
    address = address,
    email = email,
    lat = lat,
    lon = lon,
    ssids = ssids.map { it.toDomain() },
)
