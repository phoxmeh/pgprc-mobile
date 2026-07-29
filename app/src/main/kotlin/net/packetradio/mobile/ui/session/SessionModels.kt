package net.packetradio.mobile.ui.session

import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.ConnectionId
import net.packetradio.mobile.model.PortEntry
import net.packetradio.mobile.model.supportsConnect

/**
 * One open session tab's UI state. Ephemeral (in-memory only) for now —
 * persisting pinned tabs across a relaunch is a later phase; [pinned] only
 * controls in-session drawer display (pin icon, kept above unpinned tabs).
 */
data class SessionTabState(
    val id: String,
    val portId: String? = null,
    val node: String = "",
    val via: String = "",
    val unproto: Boolean = false,
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
)

/**
 * Whether this tab should be treated as "connected" for status-bar/control
 * purposes — connect-capable ports need their own acknowledged connection,
 * unproto-only ports just need the underlying port itself to be active
 * (mirrors the desktop's `SessionTab::is_live()`). [portConnected] is looked
 * up live from [SessionViewModel]'s `portStatuses` map rather than cached on
 * the tab, so a tab pointed at an already-connected port (a freshly created
 * tab, or one whose port dropdown was just switched) reflects that
 * immediately instead of waiting for the next `PortConnected` event.
 */
fun SessionTabState.isLive(port: PortEntry?, portConnected: Boolean): Boolean {
    val config = port?.config ?: return false
    return if (config.supportsConnect() && !unproto) connState == ConnState.CONNECTED else portConnected
}

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
enum class PortStatus { OFF, CONNECTED, ERROR }

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
