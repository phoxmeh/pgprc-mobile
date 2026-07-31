package net.packetradio.mobile.protocol

/** One neighbor entry inside a [NetRomNodesBroadcast]. */
data class NetRomNeighbor(
    val callsign: Ax25Address,
    val alias: String,
    val bestNeighbor: Ax25Address,
    val quality: Int,
)

data class NetRomNodesBroadcast(val senderAlias: String, val neighbors: List<NetRomNeighbor>)

/**
 * Parses a NET/ROM NODES broadcast — a UI frame addressed to "NODES" with PID 0xCF (see
 * [pidLabel]) that a NET/ROM-capable node periodically sends to announce itself and every
 * neighbor it can reach, each tagged with a 6-character alias. This is the standard
 * packet-radio mechanism the Heard Stations feature uses to learn aliases and to add
 * neighbors it never directly heard on the air (see [Ax25LinkSession]'s sibling doc comments
 * for this app's general "keep it real" scope philosophy — this format is universal enough
 * across NET/ROM-compatible TNCs/nodes to implement directly rather than approximate).
 *
 * Payload layout: byte 0 is a `0xFF` marker, bytes 1-6 are the sender's own alias
 * (space-padded ASCII), then a run of 20-byte neighbor records: a 7-byte AX.25-shifted
 * callsign field (see [Ax25.decodeCallsignField] — same encoding as an AX.25 address, minus
 * the C-bit/last-bit, which don't carry meaning here), a 6-byte alias, a 7-byte
 * best-neighbor-to-reach-it-through callsign field, and a 1-byte quality.
 */
private const val MARKER = 0xFF
private const val ALIAS_LEN = 6
private const val CALLSIGN_FIELD_LEN = 7
private const val RECORD_LEN = CALLSIGN_FIELD_LEN + ALIAS_LEN + CALLSIGN_FIELD_LEN + 1
private const val HEADER_LEN = 1 + ALIAS_LEN

fun decodeNetRomNodes(payload: ByteArray): NetRomNodesBroadcast? {
    if (payload.size < HEADER_LEN || (payload[0].toInt() and 0xFF) != MARKER) return null

    val senderAlias = String(payload, 1, ALIAS_LEN, Charsets.US_ASCII).trim()

    val neighbors = mutableListOf<NetRomNeighbor>()
    var offset = HEADER_LEN
    while (offset + RECORD_LEN <= payload.size) {
        val callsign = Ax25.decodeCallsignField(payload, offset)
        val alias = String(payload, offset + CALLSIGN_FIELD_LEN, ALIAS_LEN, Charsets.US_ASCII).trim()
        val bestNeighbor = Ax25.decodeCallsignField(payload, offset + CALLSIGN_FIELD_LEN + ALIAS_LEN)
        val quality = payload[offset + CALLSIGN_FIELD_LEN + ALIAS_LEN + CALLSIGN_FIELD_LEN].toInt() and 0xFF
        neighbors += NetRomNeighbor(callsign, alias, bestNeighbor, quality)
        offset += RECORD_LEN
    }

    return NetRomNodesBroadcast(senderAlias, neighbors)
}
