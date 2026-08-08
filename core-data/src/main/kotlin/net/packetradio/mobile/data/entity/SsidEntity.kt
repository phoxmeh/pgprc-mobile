package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import net.packetradio.mobile.model.SsidEntry

@Entity(
    tableName = "ssids",
    indices = [Index(value = ["baseCallsign", "ssidNumber"], unique = true)],
)
data class SsidEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val baseCallsign: String,
    val ssidNumber: Int,
    val userAlias: String?,
    val tag: String?,
    /** Pipe-separated list of AX.25 via paths, e.g. "KD3BFP-1|W3XYZ,RELAY2". Empty string = no saved vias. */
    val viaPathsRaw: String,
    val autoAlias: String?,
    val lastHeard: String?,
    val heardCount: Int,
    val heardDirectly: Boolean,
    val currentId: String?,
)

fun SsidEntity.toDomain(): SsidEntry = SsidEntry(
    id = id,
    baseCallsign = baseCallsign,
    ssidNumber = ssidNumber,
    userAlias = userAlias,
    tag = tag,
    viaPaths = if (viaPathsRaw.isBlank()) emptyList() else viaPathsRaw.split("|"),
    autoAlias = autoAlias,
    lastHeard = lastHeard,
    heardCount = heardCount,
    heardDirectly = heardDirectly,
    currentId = currentId,
)

fun SsidEntry.toEntity(): SsidEntity = SsidEntity(
    id = id,
    baseCallsign = baseCallsign,
    ssidNumber = ssidNumber,
    userAlias = userAlias,
    tag = tag,
    viaPathsRaw = viaPaths.joinToString("|"),
    autoAlias = autoAlias,
    lastHeard = lastHeard,
    heardCount = heardCount,
    heardDirectly = heardDirectly,
    currentId = currentId,
)
