package net.packetradio.mobile.model

/** One SSID entry under a base callsign in the address book. */
data class SsidEntry(
    val id: Long = 0,
    val baseCallsign: String,
    /** 0–15 per AX.25 spec. SSID 0 displays as bare callsign (no suffix). */
    val ssidNumber: Int,
    val userAlias: String? = null,
    val tag: String? = null,
    /** Saved AX.25 digipeater paths for this SSID — one path per entry, multiple entries for different locations. */
    val viaPaths: List<String> = emptyList(),
    /** Learned from a NET/ROM NODES broadcast — shown read-only, never user-editable. */
    val autoAlias: String? = null,
    val lastHeard: String? = null,
    val heardCount: Int = 0,
    val heardDirectly: Boolean = true,
    val currentId: String? = null,
) {
    val fullCallsign: String get() = if (ssidNumber == 0) baseCallsign else "$baseCallsign-$ssidNumber"
    val displayAlias: String? get() = userAlias ?: autoAlias
}
