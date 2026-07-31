package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/** Master on/off switch for notifications. Ported from `pr-core::NotifyPrefs`. */
@Serializable
data class NotifyPrefs(
    val enabled: Boolean = false,
)

/** A packet that raised a notification, kept for the Notified Packets screen. */
@Serializable
data class NotifiedPacket(
    val id: Long = 0,
    val portId: String,
    val line: String,
    val timestamp: String,
)

/**
 * A destination callsign the user wants watched for unproto traffic (e.g. "MAIL", a weather-alert
 * ID) — matching traffic becomes a [NotifiedPacket] and raises a system notification.
 * [enabled] lets the user pause a destination without losing/re-typing it.
 */
@Serializable
data class WatchedDestination(
    val id: String,
    val destination: String,
    val enabled: Boolean = true,
)
