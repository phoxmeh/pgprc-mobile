package net.packetradio.mobile.model

/** Address book entry for a base callsign, grouping all known SSIDs under it. */
data class CallsignEntry(
    val baseCallsign: String,
    val name: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val ssids: List<SsidEntry> = emptyList(),
)
