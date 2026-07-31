package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/** One of a station's last 5 unique packets addressed to "BEACON" — see the Heard Stations detail screen. */
@Serializable
data class HeardBeaconPacket(
    val id: Long = 0,
    val callsign: String,
    val text: String,
    /** "YYYY-MM-DD HH:MM:SS", local time. */
    val timestamp: String,
)
