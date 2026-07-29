package net.packetradio.mobile.transport

import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.ConnectionId
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.model.PortRunner
import net.packetradio.mobile.protocol.AgwFrame
import net.packetradio.mobile.protocol.FrameDecoder
import net.packetradio.mobile.protocol.pidSuffix

/**
 * AGWPE TCP client. Direct port of `pr-agwpe::client::AgwpeRunner` — same
 * connection lifecycle (connect, optional login, request port info, enable
 * monitoring, then dispatch commands until [PortCommand.Disconnect] or the
 * channel closes) and the same frame-kind → event mapping.
 */
class AgwpeRunner(private val config: PortConfig.Agwpe) : PortRunner {

    override suspend fun run(commands: ReceiveChannel<PortCommand>, events: SendChannel<PortEvent>) {
        withContext(Dispatchers.IO) {
            val socket = try {
                Socket(config.host, config.port)
            } catch (e: IOException) {
                events.send(PortEvent.PortError(e.message ?: "connection failed"))
                return@withContext
            }
            events.send(PortEvent.PortConnected)

            val output = socket.getOutputStream()
            fun writeFrame(frame: AgwFrame) = synchronized(output) {
                output.write(frame.encode())
                output.flush()
            }

            config.login?.let { writeFrame(AgwFrame.login(it.username, it.password)) }
            writeFrame(AgwFrame.create(config.radioPort, 'G', "", "", ByteArray(0)))
            writeFrame(AgwFrame.create(config.radioPort, 'm', "", "", ByteArray(0)))

            val connMap = ConnMap()
            val idCounter = AtomicLong(INCOMING_ID_BASE)

            val readerJob = launch {
                val decoder = FrameDecoder()
                val buf = ByteArray(4096)
                try {
                    val input = socket.getInputStream()
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        decoder.feed(buf.copyOf(n))
                        while (isActive) {
                            val frame = decoder.nextFrame() ?: break
                            handleFrame(frame, connMap, idCounter, events)
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed from the command loop below — normal shutdown path.
                }
            }

            try {
                for (command in commands) {
                    when (command) {
                        PortCommand.Connect -> {} // already connected at construction
                        PortCommand.Disconnect -> break
                        is PortCommand.OpenConnection -> {
                            val id = connMap.idFor(command.remote)
                                ?: idCounter.getAndIncrement().also { connMap.put(it, command.remote) }
                            events.send(PortEvent.ConnectionOpened(id, command.remote))
                            events.send(PortEvent.ConnStateChanged(id, ConnState.CONNECTING))
                            val frame = if (command.via.isEmpty()) {
                                AgwFrame.create(config.radioPort, 'C', config.myCall, command.remote, ByteArray(0))
                            } else {
                                AgwFrame.connectVia(config.radioPort, config.myCall, command.remote, command.via)
                            }
                            writeFrame(frame)
                        }
                        is PortCommand.Send -> {
                            val remote = connMap.callFor(command.id) ?: continue
                            writeFrame(AgwFrame.create(config.radioPort, 'D', config.myCall, remote, command.bytes))
                        }
                        is PortCommand.CloseConnection -> {
                            val remote = connMap.callFor(command.id) ?: continue
                            writeFrame(AgwFrame.create(config.radioPort, 'd', config.myCall, remote, ByteArray(0)))
                        }
                        is PortCommand.SendUnproto -> {
                            events.send(
                                PortEvent.Monitor(
                                    "${config.myCall} > ${command.dest} [unproto TX]: ${String(command.bytes)}",
                                    null,
                                ),
                            )
                            val frame = if (command.via.isEmpty()) {
                                AgwFrame.create(config.radioPort, 'M', config.myCall, command.dest, command.bytes)
                            } else {
                                AgwFrame.unprotoVia(config.radioPort, config.myCall, command.dest, command.via, command.bytes)
                            }
                            writeFrame(frame)
                        }
                    }
                }
            } finally {
                socket.close()
                readerJob.cancelAndJoin()
                events.send(PortEvent.PortDisconnected(null))
            }
        }
    }

    private suspend fun handleFrame(
        frame: AgwFrame,
        connMap: ConnMap,
        idCounter: AtomicLong,
        events: SendChannel<PortEvent>,
    ) {
        when (frame.dataKind) {
            'G', 'R', 'H', 'g' -> {
                events.send(PortEvent.Monitor("[${frame.dataKind}] ${AgwFrame.textFromBytes(frame.data)}", null))
            }
            'C' -> {
                val existingId = connMap.idFor(frame.callFrom)
                if (existingId == null) {
                    // Unsolicited incoming connection.
                    val id = idCounter.getAndIncrement()
                    connMap.put(id, frame.callFrom)
                    events.send(PortEvent.StationHeard(frame.callFrom))
                    events.send(PortEvent.ConnectionOpened(id, frame.callFrom))
                    events.send(PortEvent.ConnStateChanged(id, ConnState.CONNECTED))
                    if (frame.data.isNotEmpty()) {
                        events.send(PortEvent.Monitor(AgwFrame.textFromBytes(frame.data), null))
                    }
                } else {
                    // Direwolf's confirmation of a connection *we* initiated via OpenConnection —
                    // we already registered frame.callFrom in connMap when sending our own 'C'/'v'
                    // frame, so this is the transition out of CONNECTING, not a new connection.
                    events.send(PortEvent.ConnStateChanged(existingId, ConnState.CONNECTED))
                }
            }
            'd' -> {
                val id = connMap.remove(frame.callFrom)
                if (id != null) {
                    events.send(PortEvent.ConnStateChanged(id, ConnState.DISCONNECTED))
                    events.send(PortEvent.ConnectionClosed(id))
                }
            }
            'D' -> {
                val id = connMap.idFor(frame.callFrom)
                if (id != null) events.send(PortEvent.Data(id, frame.data))
            }
            'U', 'S', 'I', 'T' -> {
                val text = AgwFrame.textFromBytes(frame.data)
                events.send(PortEvent.StationHeard(frame.callFrom))
                val line = "${frame.callFrom} > ${frame.callTo} [${frame.dataKind}]${pidSuffix(frame.pid)}: $text"
                val to = if (frame.dataKind == 'U') frame.callTo else null
                events.send(PortEvent.Monitor(line, to))
            }
            else -> {}
        }
    }

    /** Bidirectional, mutex-guarded `ConnectionId` ↔ callsign map. */
    private class ConnMap {
        private val mutex = Mutex()
        private val idToCall = HashMap<ConnectionId, String>()
        private val callToId = HashMap<String, ConnectionId>()

        suspend fun idFor(call: String): ConnectionId? = mutex.withLock { callToId[call] }
        suspend fun callFor(id: ConnectionId): String? = mutex.withLock { idToCall[id] }

        suspend fun put(id: ConnectionId, call: String) = mutex.withLock {
            idToCall[id] = call
            callToId[call] = id
        }

        suspend fun remove(call: String): ConnectionId? = mutex.withLock {
            val id = callToId.remove(call)
            if (id != null) idToCall.remove(id)
            id
        }
    }

    companion object {
        /** Both peer-initiated and locally-opened connections mint ids from this same counter. */
        const val INCOMING_ID_BASE = 1L shl 32
    }
}
