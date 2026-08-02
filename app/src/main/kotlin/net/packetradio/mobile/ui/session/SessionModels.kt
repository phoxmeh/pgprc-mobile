package net.packetradio.mobile.ui.session

import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.ConnectionId
import net.packetradio.mobile.model.PortEntry

/**
 * One dialed session tab's state. Identity (`portId`/`node`/`via`) is fixed
 * at creation by [SessionViewModel.dialTab] and never mutated afterward —
 * redialing a different destination means a new tab, not editing this one
 * (mirrors the desktop's `SessionTab`, where the same tab is only ever
 * reused across repeated connect/disconnect cycles of that one fixed
 * destination). Ephemeral (in-memory only) for now — persisting pinned tabs
 * across a relaunch is a later phase; [pinned] only controls in-session
 * drawer display (pin icon, kept above unpinned tabs).
 */
data class SessionTabState(
    val id: String,
    val portId: String? = null,
    val node: String = "",
    val via: String = "",
    val pinned: Boolean = false,
    /** Set once this tab's own `OpenConnection` is acknowledged by `ConnectionOpened`. */
    val connectionId: ConnectionId? = null,
    val connState: ConnState? = null,
    /** `System.currentTimeMillis()` when [connState] last became `CONNECTED` — drives the status bar's duration timer. */
    val connectedSinceMillis: Long? = null,
    val lines: List<String> = emptyList(),
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val inputText: String = "",
    /** True when the user sent CloseConnection — distinguishes a clean disconnect from an unexpected drop. */
    val initiatedClose: Boolean = false,
    /** True once this connection reached CONNECTED at least once — distinguishes a remote DISC (graceful) from a failed SABM. */
    val wasConnected: Boolean = false,
)

/** Whether this tab has a live, acknowledged two-way connection right now. */
fun SessionTabState.isLive(): Boolean = connState == ConnState.CONNECTED

/**
 * `n:<node>` (or just `n` with no node yet) where `n` is the tab's port's
 * position in [ports] (0-based) — the numbering the Ports drawer also shows,
 * so a tab's name always tells you which drawer row it's talking to.
 */
fun SessionTabState.tabName(ports: List<PortEntry>): String {
    val index = ports.indexOfFirst { it.id == portId }
    val label = if (index >= 0) index.toString() else "?"
    return if (node.isBlank()) label else "$label:$node"
}

/** A port's connection state as shown by its toggle button in the Ports drawer. */
enum class PortStatus { OFF, CONNECTING, CONNECTED, ERROR, TIMEOUT }

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
