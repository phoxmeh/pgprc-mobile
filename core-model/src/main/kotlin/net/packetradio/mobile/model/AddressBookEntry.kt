package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/** Ported from `pr-core::AddressBookEntry`. [callsign] is always uppercase. */
@Serializable
data class AddressBookEntry(
    val callsign: String,
    val name: String? = null,
    val alias: String? = null,
    val location: String? = null,
    val notes: String? = null,
    /** "YYYY-MM-DD HH:MM:SS", local time. */
    val lastHeard: String? = null,
    val heardCount: Int = 0,
    /** Comma/space-separated digipeater path, same convention as [Beacon.via]. */
    val via: String = "",
)
