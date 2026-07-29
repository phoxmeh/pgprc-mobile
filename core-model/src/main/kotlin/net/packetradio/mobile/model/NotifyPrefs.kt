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
    val id: Long,
    val portId: String,
    val line: String,
    val timestamp: String,
)
