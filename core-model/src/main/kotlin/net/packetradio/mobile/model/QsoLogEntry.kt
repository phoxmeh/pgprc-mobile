package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * One real connected-mode QSO, logged on connect/disconnect for
 * connect-capable ports only — distinct from the address book's "heard"
 * tracking, which includes any monitored traffic. Ported from
 * `pr-core::QsoLogEntry`; feeds ADIF export.
 */
@Serializable
data class QsoLogEntry(
    val callsign: String,
    val portId: String,
    /** "YYYY-MM-DD HH:MM:SS". */
    val started: String,
    val ended: String? = null,
)
