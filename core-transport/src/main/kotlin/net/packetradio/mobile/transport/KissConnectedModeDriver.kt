package net.packetradio.mobile.transport

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.ConnectionId
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.protocol.Ax25Address
import net.packetradio.mobile.protocol.Ax25DecodedFrame
import net.packetradio.mobile.protocol.Ax25FrameContent
import net.packetradio.mobile.protocol.Ax25LinkSession
import net.packetradio.mobile.protocol.Kiss

/**
 * Drives AX.25 connected-mode (see [Ax25LinkSession]) for a bare-KISS transport — shared by
 * [KissTcpRunner] and [BluetoothKissRunner] since the two differ only in the underlying socket,
 * never in this orchestration. Owns every live [Ax25LinkSession] for the port, keyed by
 * [ConnectionId], applies each session's [Ax25LinkSession.Effect]s (writing to the wire,
 * scheduling/cancelling the real T1 delay, emitting [PortEvent]s), and routes inbound frames
 * addressed to us to the right session.
 *
 * Every public method here must only ever be called from the runner's single command-processing
 * coroutine (never concurrently from the reader coroutine) — that's what makes mutating
 * [sessions] and each [Ax25LinkSession] safe without any locking. [timerFired] is the channel a
 * `select` loop should also listen on so a fired T1 re-enters that same serialized point.
 */
internal class KissConnectedModeDriver(
    private val scope: CoroutineScope,
    myCallRaw: String,
    private val events: SendChannel<PortEvent>,
    private val writeKiss: (ByteArray) -> Unit,
    private val kissPort: Int,
) {
    private class SessionEntry(val session: Ax25LinkSession, val remote: String) {
        var t1Job: Job? = null
        var t3Job: Job? = null
    }

    private val myCall = Ax25Address.parse(myCallRaw)
    private val sessions = mutableMapOf<ConnectionId, SessionEntry>()
    private val remoteToId = mutableMapOf<String, ConnectionId>()
    private val idCounter = AtomicLong(1)

    private val t1Fired = Channel<ConnectionId>(Channel.UNLIMITED)
    private val t3Fired = Channel<ConnectionId>(Channel.UNLIMITED)

    /** A `select` loop should listen on this and call [onTimerFired] with whatever id it yields. */
    val timerFiredEvents: ReceiveChannel<ConnectionId> get() = t1Fired

    /** A `select` loop should listen on this and call [onT3Fired] with whatever id it yields. */
    val t3FiredEvents: ReceiveChannel<ConnectionId> get() = t3Fired

    suspend fun openConnection(remote: String, via: List<String>) {
        val id = remoteToId[remote] ?: run {
            val newId = idCounter.getAndIncrement()
            val session = Ax25LinkSession(myCall, Ax25Address.parse(remote), via.map { Ax25Address.parse(it) })
            sessions[newId] = SessionEntry(session, remote)
            remoteToId[remote] = newId
            events.send(PortEvent.ConnectionOpened(newId, remote))
            newId
        }
        val entry = sessions.getValue(id)
        apply(id, entry, entry.session.handle(Ax25LinkSession.Event.UserConnect))
    }

    suspend fun send(id: ConnectionId, bytes: ByteArray) {
        val entry = sessions[id] ?: return
        apply(id, entry, entry.session.handle(Ax25LinkSession.Event.UserSend(bytes)))
    }

    suspend fun closeConnection(id: ConnectionId) {
        val entry = sessions[id] ?: return
        apply(id, entry, entry.session.handle(Ax25LinkSession.Event.UserDisconnect))
    }

    /** Tells the link whether we can currently accept more I-frames — see [Ax25LinkSession.Event.SetLocalBusy]. */
    suspend fun setBusy(id: ConnectionId, busy: Boolean) {
        val entry = sessions[id] ?: return
        apply(id, entry, entry.session.handle(Ax25LinkSession.Event.SetLocalBusy(busy)))
    }

    suspend fun onTimerFired(id: ConnectionId) {
        val entry = sessions[id] ?: return
        apply(id, entry, entry.session.handle(Ax25LinkSession.Event.T1Expired))
    }

    suspend fun onT3Fired(id: ConnectionId) {
        val entry = sessions[id] ?: return
        apply(id, entry, entry.session.handle(Ax25LinkSession.Event.T3Expired))
    }

    /**
     * Routes one already-decoded frame addressed to [myCall] — the caller (the runner's reader
     * loop, via a channel back into the command coroutine, never called directly from the reader
     * itself) is responsible for that address filter and for excluding UI frames, which this
     * driver has no concept of.
     */
    suspend fun frameReceived(frame: Ax25DecodedFrame) {
        val remote = frame.source.label()
        val event = Ax25LinkSession.Event.FrameReceived(frame.content, frame.control, frame.trailingBytes.isNotEmpty())
        val existingId = remoteToId[remote]
        if (existingId != null) {
            val entry = sessions.getValue(existingId)
            apply(existingId, entry, entry.session.handle(event))
            return
        }
        if (frame.content != Ax25FrameContent.SetAsynchronousBalancedMode) {
            return // a stray S/I/response frame for a link we don't know about — not worth answering individually
        }
        // Unsolicited incoming connection.
        val id = idCounter.getAndIncrement()
        val session = Ax25LinkSession(myCall, frame.source, frame.digipeaters)
        val entry = SessionEntry(session, remote)
        sessions[id] = entry
        remoteToId[remote] = id
        events.send(PortEvent.StationHeard(remote))
        events.send(PortEvent.ConnectionOpened(id, remote))
        apply(id, entry, session.handle(event))
    }

    /** Cancels every session's T1/T3 jobs — call once from the runner's `finally`, socket already closing. */
    fun shutdown() {
        sessions.values.forEach {
            it.t1Job?.cancel()
            it.t3Job?.cancel()
        }
    }

    private suspend fun apply(id: ConnectionId, entry: SessionEntry, effects: List<Ax25LinkSession.Effect>) {
        for (effect in effects) {
            when (effect) {
                is Ax25LinkSession.Effect.Transmit -> writeKiss(Kiss.encodeDataFrame(kissPort, effect.bytes))
                is Ax25LinkSession.Effect.DataReceived -> events.send(PortEvent.Data(id, effect.bytes))
                is Ax25LinkSession.Effect.StateChanged -> {
                    val connState = when (effect.state) {
                        Ax25LinkSession.LinkState.CONNECTING -> ConnState.CONNECTING
                        Ax25LinkSession.LinkState.CONNECTED -> ConnState.CONNECTED
                        Ax25LinkSession.LinkState.DISCONNECTING -> ConnState.DISCONNECTING
                        Ax25LinkSession.LinkState.DISCONNECTED -> ConnState.DISCONNECTED
                    }
                    events.send(PortEvent.ConnStateChanged(id, connState))
                    if (effect.state == Ax25LinkSession.LinkState.DISCONNECTED) {
                        entry.t1Job?.cancel()
                        entry.t3Job?.cancel()
                        sessions.remove(id)
                        remoteToId.remove(entry.remote)
                        events.send(PortEvent.ConnectionClosed(id))
                    }
                }
                Ax25LinkSession.Effect.StartT1 -> {
                    entry.t1Job?.cancel()
                    entry.t1Job = scope.launch {
                        delay(T1_MS)
                        t1Fired.send(id)
                    }
                }
                Ax25LinkSession.Effect.StopT1 -> {
                    entry.t1Job?.cancel()
                    entry.t1Job = null
                }
                Ax25LinkSession.Effect.StartT3 -> {
                    entry.t3Job?.cancel()
                    entry.t3Job = scope.launch {
                        delay(T3_MS)
                        t3Fired.send(id)
                    }
                }
                Ax25LinkSession.Effect.StopT3 -> {
                    entry.t3Job?.cancel()
                    entry.t3Job = null
                }
            }
        }
    }

    private companion object {
        const val T1_MS = 3000L

        /** How long the link can stay idle before we send a status poll to check it's still there. */
        const val T3_MS = 180_000L
    }
}
