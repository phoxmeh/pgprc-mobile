package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * A pinned tab shell recreated (disconnected, never auto-connecting) at next
 * app launch. Ported from `pr-core::PinnedSession`. Identity is
 * (portId, remote, unproto) — connected-mode and unproto traffic to the same
 * node on the same port are pinned independently.
 */
@Serializable
data class PinnedSession(
    val portId: String,
    val remote: String,
    val via: String = "",
    val unproto: Boolean = false,
)
