package net.packetradio.mobile.transport

import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import net.packetradio.mobile.model.ConnState
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.model.PortRunner

/**
 * Raw TCP Telnet client for BPQ32 and similar node software. The TCP connection itself is the
 * session — there is no AX.25 framing. [PortCommand.OpenConnection] maps immediately to
 * [PortEvent.ConnStateChanged]([ConnState.CONNECTED]); [PortCommand.Send] writes bytes to the
 * socket; [PortCommand.CloseConnection] tears down the session and the TCP connection.
 *
 * IAC negotiation sequences (RFC 854) are stripped from received data and answered: WILL options
 * get DONT responses, DO options get WONT responses, refusing everything and keeping the byte
 * stream clean for display. Only one session per port is supported — a second
 * [PortCommand.OpenConnection] while one is active is immediately rejected.
 */
class TelnetRunner(private val config: PortConfig.Telnet) : PortRunner {

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
            var connectionId: Long? = null
            var nextId = 1L
            val iacState = IacState()

            val dataIn = Channel<ByteArray>(Channel.UNLIMITED)
            val readerJob = launch {
                val buf = ByteArray(1024)
                try {
                    val input = socket.getInputStream()
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val stripped = iacState.process(buf.copyOf(n), output)
                        if (stripped.isNotEmpty()) dataIn.send(stripped)
                    }
                } catch (_: IOException) {
                    // socket closed from command loop or dropped by server — dataIn.close() notifies main loop
                } finally {
                    dataIn.close()
                }
            }

            var shouldStop = false
            var disconnectReason: String? = null
            try {
                while (!shouldStop) {
                    select<Unit> {
                        commands.onReceiveCatching { result ->
                            val command = result.getOrNull() ?: run { shouldStop = true; return@onReceiveCatching }
                            when (command) {
                                PortCommand.Connect -> {}
                                is PortCommand.OpenConnection -> {
                                    if (connectionId != null) {
                                        // Only one telnet session per port — fail the new attempt immediately.
                                        val id = nextId++
                                        events.send(PortEvent.ConnectionOpened(id, config.host))
                                        events.send(PortEvent.ConnStateChanged(id, ConnState.DISCONNECTED))
                                        events.send(PortEvent.ConnectionClosed(id))
                                    } else {
                                        val id = nextId++
                                        connectionId = id
                                        events.send(PortEvent.ConnectionOpened(id, config.host))
                                        events.send(PortEvent.ConnStateChanged(id, ConnState.CONNECTED))
                                    }
                                }
                                is PortCommand.Send -> {
                                    val id = connectionId ?: return@onReceiveCatching
                                    if (command.id == id) synchronized(output) {
                                        output.write(command.bytes)
                                        output.flush()
                                    }
                                }
                                is PortCommand.CloseConnection -> {
                                    val id = connectionId
                                    if (id != null && command.id == id) {
                                        connectionId = null
                                        events.send(PortEvent.ConnStateChanged(id, ConnState.DISCONNECTED))
                                        events.send(PortEvent.ConnectionClosed(id))
                                        shouldStop = true
                                    }
                                }
                                PortCommand.Probe -> synchronized(output) {
                                    // IAC NOP — touches the socket to surface dead links without any visible effect
                                    output.write(byteArrayOf(IAC, NOP))
                                    output.flush()
                                }
                                PortCommand.Disconnect -> shouldStop = true
                                is PortCommand.SendUnproto -> {}
                            }
                        }
                        dataIn.onReceiveCatching { result ->
                            val data = result.getOrNull() ?: run { shouldStop = true; return@onReceiveCatching }
                            val id = connectionId ?: return@onReceiveCatching
                            events.send(PortEvent.Data(id, data))
                        }
                    }
                }
            } catch (e: IOException) {
                disconnectReason = e.message ?: "connection lost"
            } finally {
                val id = connectionId
                if (id != null) {
                    events.send(PortEvent.ConnStateChanged(id, ConnState.DISCONNECTED))
                    events.send(PortEvent.ConnectionClosed(id))
                }
                socket.close()
                readerJob.cancelAndJoin()
                events.send(PortEvent.PortDisconnected(disconnectReason))
            }
        }
    }

    /**
     * Stateful IAC sequence parser — tracks state across [process] calls so negotiation
     * sequences that span buffer boundaries are handled correctly.
     */
    private class IacState {
        private var state = St.NORMAL
        private var iacCmd: Byte = 0

        private enum class St { NORMAL, IAC, OPTION, SB, SB_IAC }

        fun process(data: ByteArray, output: OutputStream): ByteArray {
            val result = mutableListOf<Byte>()
            for (b in data) {
                when (state) {
                    St.NORMAL -> if (b == IAC) state = St.IAC else result.add(b)
                    St.IAC -> when (b) {
                        IAC -> { result.add(IAC); state = St.NORMAL }  // escaped IAC → literal 0xFF
                        WILL, WONT, DO, DONT -> { iacCmd = b; state = St.OPTION }
                        SB -> state = St.SB
                        else -> state = St.NORMAL
                    }
                    St.OPTION -> {
                        val response: Byte? = when (iacCmd) {
                            WILL -> DONT
                            DO -> WONT
                            else -> null
                        }
                        if (response != null) synchronized(output) {
                            output.write(byteArrayOf(IAC, response, b))
                            output.flush()
                        }
                        state = St.NORMAL
                    }
                    St.SB -> if (b == IAC) state = St.SB_IAC
                    St.SB_IAC -> state = if (b == SE) St.NORMAL else St.SB
                }
            }
            return result.toByteArray()
        }
    }

    private companion object {
        val IAC: Byte = 255.toByte()
        val WILL: Byte = 251.toByte()
        val WONT: Byte = 252.toByte()
        val DO: Byte = 253.toByte()
        val DONT: Byte = 254.toByte()
        val SB: Byte = 250.toByte()
        val SE: Byte = 240.toByte()
        val NOP: Byte = 241.toByte()
    }
}
