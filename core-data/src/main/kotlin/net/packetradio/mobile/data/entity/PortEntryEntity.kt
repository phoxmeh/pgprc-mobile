package net.packetradio.mobile.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEntry

/**
 * Room row for one configured port. [kind] + [configJson] together replace
 * the desktop's `#[serde(tag = "kind")]` internally-tagged enum: [kind]
 * picks which [PortConfig] subtype to deserialize [configJson] as (rather
 * than a self-describing polymorphic JSON blob), since it also doubles as an
 * indexable/filterable column.
 */
@Entity(tableName = "ports")
data class PortEntryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val configJson: String,
    val autoconnect: Boolean,
    /** Explicit display/reorder order — every port dropdown elsewhere in the
     *  app iterates this order, same as the desktop's Vec<PortEntry> order. */
    val sortOrder: Int = 0,
)

private const val KIND_TELNET = "telnet"
private const val KIND_SSH = "ssh"
private const val KIND_AGWPE = "agwpe"
private const val KIND_KISS_TCP = "kiss_tcp"
private const val KIND_BLUETOOTH_KISS = "bluetooth_kiss"
private const val KIND_USB_SERIAL_KISS = "usb_serial_kiss"

private val json = Json { ignoreUnknownKeys = true }

fun PortConfig.toKindAndJson(): Pair<String, String> = when (this) {
    is PortConfig.Telnet -> KIND_TELNET to json.encodeToString(PortConfig.Telnet.serializer(), this)
    is PortConfig.Ssh -> KIND_SSH to json.encodeToString(PortConfig.Ssh.serializer(), this)
    is PortConfig.Agwpe -> KIND_AGWPE to json.encodeToString(PortConfig.Agwpe.serializer(), this)
    is PortConfig.KissTcp -> KIND_KISS_TCP to json.encodeToString(PortConfig.KissTcp.serializer(), this)
    is PortConfig.BluetoothKiss -> KIND_BLUETOOTH_KISS to json.encodeToString(PortConfig.BluetoothKiss.serializer(), this)
    is PortConfig.UsbSerialKiss -> KIND_USB_SERIAL_KISS to json.encodeToString(PortConfig.UsbSerialKiss.serializer(), this)
}

fun portConfigFromKindAndJson(kind: String, configJson: String): PortConfig = when (kind) {
    KIND_TELNET -> json.decodeFromString(PortConfig.Telnet.serializer(), configJson)
    KIND_SSH -> json.decodeFromString(PortConfig.Ssh.serializer(), configJson)
    KIND_AGWPE -> json.decodeFromString(PortConfig.Agwpe.serializer(), configJson)
    KIND_KISS_TCP -> json.decodeFromString(PortConfig.KissTcp.serializer(), configJson)
    KIND_BLUETOOTH_KISS -> json.decodeFromString(PortConfig.BluetoothKiss.serializer(), configJson)
    KIND_USB_SERIAL_KISS -> json.decodeFromString(PortConfig.UsbSerialKiss.serializer(), configJson)
    else -> error("Unknown port kind '$kind' in stored config — was a port added without a matching toKindAndJson() case?")
}

fun PortEntry.toEntity(sortOrder: Int = 0): PortEntryEntity {
    val (kind, configJson) = config.toKindAndJson()
    return PortEntryEntity(
        id = id,
        name = name,
        kind = kind,
        configJson = configJson,
        autoconnect = autoconnect,
        sortOrder = sortOrder,
    )
}

fun PortEntryEntity.toDomain(): PortEntry = PortEntry(
    id = id,
    name = name,
    config = portConfigFromKindAndJson(kind, configJson),
    autoconnect = autoconnect,
)
