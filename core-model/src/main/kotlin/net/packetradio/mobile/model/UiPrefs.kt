package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * General scalar preferences, backed by a Preferences DataStore rather than
 * Room (nothing here is a list). Ported from `pr-core::UiPrefs`.
 */
@Serializable
data class UiPrefs(
    val showMonitor: Boolean = true,
    val font: String? = null,
    val showTimestamps: Boolean = true,
    val operatorName: String? = null,
    val defaultCall: String? = null,
    val location: String? = null,
    /**
     * Your own packet-BBS hierarchical routing address (e.g.
     * `N0CALL@WB1GOF.#EMA.MA.USA.NOAM`) — informational only for now, not
     * used to prefill anything (a network host and a BBS address are
     * different namespaces).
     */
    val homeServer: String? = null,
    val qrzUsername: String? = null,
    val qrzPassword: String? = null,
    /** Max scrollback lines per (port, node) tail-read as history preview. */
    val historyLines: Int = 1000,
    /** Max raw lines kept in the Monitor view for re-render on filter change. */
    val monitorBufferLines: Int = 5000,
)
