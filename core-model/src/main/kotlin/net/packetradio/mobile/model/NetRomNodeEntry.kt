package net.packetradio.mobile.model

/** One row in the per-port NET/ROM routing table — populated from NODES broadcasts. */
data class NetRomNodeEntry(
    val id: Long = 0,
    val portId: String,
    /** Callsign of the destination NET/ROM node. */
    val callsign: String,
    val alias: String,
    /** The directly-heard station we send packets to in order to reach [callsign]. */
    val bestNeighborCallsign: String,
    /** Path quality advertised by the broadcasting node (0–255). */
    val quality: Int,
    /** Decremented each broadcast cycle the node isn't heard; entry removed when it reaches 0. */
    val obsolescenceCounter: Int,
)
