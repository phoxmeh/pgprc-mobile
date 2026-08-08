package net.packetradio.mobile.model

/** Address book entry for a base callsign, grouping all known SSIDs under it. */
data class CallsignEntry(
    val baseCallsign: String,
    val name: String? = null,
    val location: String? = null,
    val notes: String? = null,
    /** First name from QRZ sync. */
    val firstName: String? = null,
    /** Last name from QRZ sync. */
    val lastName: String? = null,
    /** Mailing address from QRZ sync. */
    val address: String? = null,
    /** Email address from QRZ sync. */
    val email: String? = null,
    /** Latitude from QRZ sync (decimal degrees). */
    val lat: Double? = null,
    /** Longitude from QRZ sync (decimal degrees). */
    val lon: Double? = null,
    val ssids: List<SsidEntry> = emptyList(),
) {
    /** Display name: QRZ first+last if available, else the user-entered name field. */
    val operatorName: String?
        get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { name }
}
