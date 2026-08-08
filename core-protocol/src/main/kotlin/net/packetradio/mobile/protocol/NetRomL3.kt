package net.packetradio.mobile.protocol

/**
 * NET/ROM Layer 3 packet encoding and decoding for connected-mode sessions.
 *
 * L3 Header layout (18 bytes):
 *   Bytes  0-6:  Origin callsign (AX.25-shifted 7-byte field)
 *   Bytes  7-13: Destination callsign (AX.25-shifted 7-byte field)
 *   Byte  14:    TTL
 *   Byte  15:    Packet type
 *   Byte  16:    Circuit index
 *   Byte  17:    Circuit ID
 *
 * INFO frames additionally carry:
 *   Byte  18: TX sequence (bits 3-1) | more-data flag (bit 0)
 *   Byte  19: RX sequence (bits 3-1) | NAK flag (bit 0)
 *   Bytes 20+: payload data
 *
 * INFO ACK:
 *   Byte  18: RX sequence (bits 3-1) | NAK flag (bit 0)
 *
 * CONNECT REQUEST:
 *   Byte  18: Proposed window size
 *
 * CONNECT ACK:
 *   Byte  18: Remote circuit index
 *   Byte  19: Remote circuit ID
 *   Byte  20: Accepted window size
 */

const val NETROM_DEFAULT_TTL = 7
const val NETROM_DEFAULT_WINDOW = 4

private const val HEADER_LEN = 18
private const val CALLSIGN_FIELD_LEN = 7
private const val TYPE_CONNECT_REQUEST = 0x01
private const val TYPE_CONNECT_ACK = 0x02
private const val TYPE_DISCONNECT_REQUEST = 0x07
private const val TYPE_DISCONNECT_ACK = 0x0E
private const val TYPE_INFO = 0x00
private const val TYPE_INFO_ACK = 0x05

sealed class NetRomL3Packet {
    abstract val origin: String
    abstract val destination: String
    abstract val ttl: Int
    abstract val circuitIndex: Int
    abstract val circuitId: Int

    data class ConnectRequest(
        override val origin: String,
        override val destination: String,
        override val ttl: Int = NETROM_DEFAULT_TTL,
        override val circuitIndex: Int,
        override val circuitId: Int,
        val proposedWindowSize: Int = NETROM_DEFAULT_WINDOW,
    ) : NetRomL3Packet()

    data class ConnectAck(
        override val origin: String,
        override val destination: String,
        override val ttl: Int,
        override val circuitIndex: Int,
        override val circuitId: Int,
        val remoteCircuitIndex: Int,
        val remoteCircuitId: Int,
        val acceptedWindowSize: Int,
    ) : NetRomL3Packet()

    class Info(
        override val origin: String,
        override val destination: String,
        override val ttl: Int,
        override val circuitIndex: Int,
        override val circuitId: Int,
        val txSeq: Int,
        val rxSeq: Int,
        val moreData: Boolean = false,
        val data: ByteArray,
    ) : NetRomL3Packet() {
        override fun equals(other: Any?) = other is Info && origin == other.origin && destination == other.destination &&
            circuitIndex == other.circuitIndex && circuitId == other.circuitId &&
            txSeq == other.txSeq && rxSeq == other.rxSeq && data.contentEquals(other.data)
        override fun hashCode() = listOf(origin, destination, circuitIndex, circuitId, txSeq, rxSeq).hashCode()
    }

    data class InfoAck(
        override val origin: String,
        override val destination: String,
        override val ttl: Int,
        override val circuitIndex: Int,
        override val circuitId: Int,
        val rxSeq: Int,
        val nak: Boolean = false,
    ) : NetRomL3Packet()

    data class DisconnectRequest(
        override val origin: String,
        override val destination: String,
        override val ttl: Int,
        override val circuitIndex: Int,
        override val circuitId: Int,
    ) : NetRomL3Packet()

    data class DisconnectAck(
        override val origin: String,
        override val destination: String,
        override val ttl: Int,
        override val circuitIndex: Int,
        override val circuitId: Int,
    ) : NetRomL3Packet()
}

fun NetRomL3Packet.encode(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(encodeCallsignField(origin))
    out.write(encodeCallsignField(destination))
    out.write(ttl and 0xFF)
    when (this) {
        is NetRomL3Packet.ConnectRequest -> {
            out.write(TYPE_CONNECT_REQUEST)
            out.write(circuitIndex and 0xFF)
            out.write(circuitId and 0xFF)
            out.write(proposedWindowSize and 0xFF)
        }
        is NetRomL3Packet.ConnectAck -> {
            out.write(TYPE_CONNECT_ACK)
            out.write(circuitIndex and 0xFF)
            out.write(circuitId and 0xFF)
            out.write(remoteCircuitIndex and 0xFF)
            out.write(remoteCircuitId and 0xFF)
            out.write(acceptedWindowSize and 0xFF)
        }
        is NetRomL3Packet.Info -> {
            out.write(TYPE_INFO or (if (moreData) 0x20 else 0x00))
            out.write(circuitIndex and 0xFF)
            out.write(circuitId and 0xFF)
            out.write(((txSeq and 0x07) shl 1))
            out.write(((rxSeq and 0x07) shl 1))
            out.write(data)
        }
        is NetRomL3Packet.InfoAck -> {
            out.write(TYPE_INFO_ACK)
            out.write(circuitIndex and 0xFF)
            out.write(circuitId and 0xFF)
            out.write(((rxSeq and 0x07) shl 1) or (if (nak) 0x01 else 0x00))
        }
        is NetRomL3Packet.DisconnectRequest -> {
            out.write(TYPE_DISCONNECT_REQUEST)
            out.write(circuitIndex and 0xFF)
            out.write(circuitId and 0xFF)
        }
        is NetRomL3Packet.DisconnectAck -> {
            out.write(TYPE_DISCONNECT_ACK)
            out.write(circuitIndex and 0xFF)
            out.write(circuitId and 0xFF)
        }
    }
    return out.toByteArray()
}

fun decodeNetRomL3(payload: ByteArray): NetRomL3Packet? {
    if (payload.size < HEADER_LEN) return null
    val origin = Ax25.decodeCallsignField(payload, 0).label()
    val destination = Ax25.decodeCallsignField(payload, CALLSIGN_FIELD_LEN).label()
    val ttl = payload[14].toInt() and 0xFF
    val typeByte = payload[15].toInt() and 0xFF
    val type = typeByte and 0x1F
    val circuitIndex = payload[16].toInt() and 0xFF
    val circuitId = payload[17].toInt() and 0xFF

    return when (type) {
        TYPE_CONNECT_REQUEST -> {
            if (payload.size < HEADER_LEN + 1) return null
            NetRomL3Packet.ConnectRequest(
                origin = origin, destination = destination, ttl = ttl,
                circuitIndex = circuitIndex, circuitId = circuitId,
                proposedWindowSize = payload[18].toInt() and 0xFF,
            )
        }
        TYPE_CONNECT_ACK -> {
            if (payload.size < HEADER_LEN + 3) return null
            NetRomL3Packet.ConnectAck(
                origin = origin, destination = destination, ttl = ttl,
                circuitIndex = circuitIndex, circuitId = circuitId,
                remoteCircuitIndex = payload[18].toInt() and 0xFF,
                remoteCircuitId = payload[19].toInt() and 0xFF,
                acceptedWindowSize = payload[20].toInt() and 0xFF,
            )
        }
        TYPE_INFO -> {
            if (payload.size < HEADER_LEN + 2) return null
            val txByte = payload[18].toInt() and 0xFF
            val rxByte = payload[19].toInt() and 0xFF
            val moreData = (typeByte and 0x20) != 0
            NetRomL3Packet.Info(
                origin = origin, destination = destination, ttl = ttl,
                circuitIndex = circuitIndex, circuitId = circuitId,
                txSeq = (txByte shr 1) and 0x07,
                rxSeq = (rxByte shr 1) and 0x07,
                moreData = moreData,
                data = payload.copyOfRange(HEADER_LEN + 2, payload.size),
            )
        }
        TYPE_INFO_ACK -> {
            if (payload.size < HEADER_LEN + 1) return null
            val rxByte = payload[18].toInt() and 0xFF
            NetRomL3Packet.InfoAck(
                origin = origin, destination = destination, ttl = ttl,
                circuitIndex = circuitIndex, circuitId = circuitId,
                rxSeq = (rxByte shr 1) and 0x07,
                nak = (rxByte and 0x01) != 0,
            )
        }
        TYPE_DISCONNECT_REQUEST -> NetRomL3Packet.DisconnectRequest(
            origin = origin, destination = destination, ttl = ttl,
            circuitIndex = circuitIndex, circuitId = circuitId,
        )
        TYPE_DISCONNECT_ACK -> NetRomL3Packet.DisconnectAck(
            origin = origin, destination = destination, ttl = ttl,
            circuitIndex = circuitIndex, circuitId = circuitId,
        )
        else -> null
    }
}

private fun encodeCallsignField(callsignWithSsid: String): ByteArray {
    val addr = Ax25Address.parse(callsignWithSsid)
    val call = addr.callsign.uppercase().take(6).padEnd(6)
    val out = ByteArray(7)
    for (i in 0 until 6) out[i] = ((call[i].code shl 1) and 0xFF).toByte()
    out[6] = (0x60 or ((addr.ssid and 0x0F) shl 1)).toByte()
    return out
}
