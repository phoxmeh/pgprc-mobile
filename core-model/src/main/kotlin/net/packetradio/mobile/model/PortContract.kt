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
     * One line of raw port/frame activity for the Monitor view.
     * [to] is the destination callsign when this frame is "directed" (e.g. a
     * UI/unproto frame or an incoming connection) and `null` otherwise —
     * connected-mode traffic and our own TX echoes never set it. Consumed by
     * the notification-eligibility check the same way as the desktop app.
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
}

/**
 * Commands sent to a running port, direct port of `pr-core::PortCommand`.
 */
sealed interface PortCommand {
    data object Connect : PortCommand
    data object Disconnect : PortCommand
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
