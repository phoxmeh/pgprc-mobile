package net.packetradio.mobile.model

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

/** Identifies one open connection within a port, e.g. one connected-mode session. */
typealias ConnectionId = Long

enum class ConnState {
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
}

/**
 * Events a running port emits, direct port of `pr-core::PortEvent`
 * (`pr-core/src/port.rs`).
 */
sealed interface PortEvent {
    data object PortConnected : PortEvent
    data class PortDisconnected(val reason: String?) : PortEvent
    data class PortError(val message: String) : PortEvent

    /**
     * A free-form, non-error debugging detail about the port's own connection process (which
     * RFCOMM method was used, KISS params actually sent, etc.) — routed to the Log tab like
     * [PortConnected]/[PortDisconnected], but for the "how did it get there" detail those don't
     * carry, without misrepresenting it as an error via [PortError].
     */
    data class PortLog(val message: String) : PortEvent

    /**
     * One line of raw port/frame activity for the Monitor view.
     * [to] is the destination callsign for a UI/unproto frame — both received and our own sent
     * echoes — and `null` for connected-mode traffic (I/S/U frames belonging to a dialed
     * session). Doubles as the Monitor screen's "UI" filter signal (`to != null`, see
     * [net.packetradio.mobile.ui.session.SessionViewModel.unprotoOnly]) and is meant to also
     * back a self-notification-eligibility check later, the same way the desktop app's does.
     */
    data class Monitor(val line: String, val to: String?) : PortEvent

    data class ConnectionOpened(val id: ConnectionId, val label: String) : PortEvent
    data class ConnectionClosed(val id: ConnectionId) : PortEvent
    data class ConnStateChanged(val id: ConnectionId, val state: ConnState) : PortEvent

    // ByteArray breaks a plain data class's generated equals()/hashCode() (it's
    // reference equality, not content equality) — overridden explicitly so
    // comparing/testing events doesn't silently misbehave.
    class Data(val id: ConnectionId, val bytes: ByteArray) : PortEvent {
        override fun equals(other: Any?): Boolean =
            other is Data && id == other.id && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
        override fun toString(): String = "Data(id=$id, bytes=${bytes.size}B)"
    }

    data class StationHeard(val callsign: String) : PortEvent

    /**
     * Companion to [Monitor] for a UI/unproto frame specifically — carries the raw PID and
     * payload [Monitor]'s formatted [Monitor.line] text doesn't, for consumers that need to
     * parse the payload themselves (NET/ROM NODES-broadcast alias learning, the BEACON packet
     * log, and notification-destination matching — see `StationTracker`). Always emitted
     * alongside a [Monitor] for the same frame, never instead of it.
     */
    class UnprotoReceived(val from: String, val to: String, val pid: Int, val data: ByteArray) : PortEvent {
        override fun equals(other: Any?): Boolean =
            other is UnprotoReceived && from == other.from && to == other.to && pid == other.pid && data.contentEquals(other.data)

        override fun hashCode(): Int = listOf(from, to, pid).hashCode() * 31 + data.contentHashCode()
        override fun toString(): String = "UnprotoReceived(from=$from, to=$to, pid=$pid, data=${data.size}B)"
    }
}

/**
 * Commands sent to a running port, direct port of `pr-core::PortCommand`.
 */
sealed interface PortCommand {
    data object Connect : PortCommand
    data object Disconnect : PortCommand

    /**
     * A periodic, silent liveness check on the underlying transport (see
     * [net.packetradio.mobile.service.PortManager]) — never anything that
     * would key up a transmitter or reach the remote station. Each runner
     * answers with whatever no-op write its transport supports; an
     * [java.io.IOException] out of that write means the link (USB/Bluetooth/
     * TCP socket to the TNC) is actually gone, not just radio-silent.
     */
    data object Probe : PortCommand

    data class OpenConnection(val remote: String, val via: List<String>) : PortCommand
    data class CloseConnection(val id: ConnectionId) : PortCommand

    // See PortEvent.Data — same ByteArray-in-a-data-class equality gotcha.
    class Send(val id: ConnectionId, val bytes: ByteArray) : PortCommand {
        override fun equals(other: Any?): Boolean =
            other is Send && id == other.id && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
        override fun toString(): String = "Send(id=$id, bytes=${bytes.size}B)"
    }

    class SendUnproto(val dest: String, val via: List<String>, val bytes: ByteArray) : PortCommand {
        override fun equals(other: Any?): Boolean =
            other is SendUnproto && dest == other.dest && via == other.via && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = (31 * dest.hashCode() + via.hashCode()) * 31 + bytes.contentHashCode()
        override fun toString(): String = "SendUnproto(dest=$dest, via=$via, bytes=${bytes.size}B)"
    }
}

/**
 * A running transport backend (AGWPE, KISS-TCP, Bluetooth KISS, USB-serial
 * KISS, Telnet, SSH). One coroutine per connected port runs [run] until the
 * command channel closes or a [PortCommand.Disconnect] is received.
 */
interface PortRunner {
    suspend fun run(commands: ReceiveChannel<PortCommand>, events: SendChannel<PortEvent>)
}
