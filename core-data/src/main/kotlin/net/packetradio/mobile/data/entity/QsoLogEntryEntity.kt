package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.QsoLogEntry

/**
 * The domain [QsoLogEntry] has no id of its own (desktop matches an open
 * entry by portId+callsign+"no ended yet"); Room needs a primary key, so
 * this entity adds a surrogate autoGenerate [id] that the domain mapping
 * simply drops.
 */
@Entity(tableName = "qso_log")
data class QsoLogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callsign: String,
    val portId: String,
    val started: String,
    val ended: String?,
)

fun QsoLogEntry.toEntity(): QsoLogEntryEntity =
    QsoLogEntryEntity(callsign = callsign, portId = portId, started = started, ended = ended)

fun QsoLogEntryEntity.toDomain(): QsoLogEntry =
    QsoLogEntry(callsign = callsign, portId = portId, started = started, ended = ended)
