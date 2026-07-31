package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * A heard station. Ported from `pr-core::AddressBookEntry`, extended for the Heard Stations
 * feature. [callsign] is always uppercase.
 */
@Serializable
data class AddressBookEntry(
    val callsign: String,
    val name: String? = null,
    /** User-editable, used for routing when dialing this node. Takes precedence over [autoAlias]. */
    val userAlias: String? = null,
    /** Learned from this station's own NET/ROM NODES broadcast (`decodeNetRomNodes` in core-protocol). */
    val autoAlias: String? = null,
    val location: String? = null,
    val notes: String? = null,
    /** "YYYY-MM-DD HH:MM:SS", local time. */
    val lastHeard: String? = null,
    val heardCount: Int = 0,
    /** Comma/space-separated digipeater path, same convention as [Beacon.via]. */
    val via: String = "",
    /** False if this station has only ever been seen mentioned in someone else's NODES broadcast. */
    val heardDirectly: Boolean = true,
) {
    /** What to display/dial with — the user's own alias if they set one, else the learned one. */
    val displayAlias: String? get() = userAlias ?: autoAlias
}
