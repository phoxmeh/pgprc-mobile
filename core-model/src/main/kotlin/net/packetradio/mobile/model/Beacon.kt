package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * A scheduled unproto beacon, fired on an interval while its port is
 * connected. Ported from `pr-core::Beacon`.
 */
@Serializable
data class Beacon(
    val id: String,
    val portId: String,
    val dest: String,
    val via: String = "",
    val message: String,
    val intervalSecs: Int,
    val enabled: Boolean = true,
)
