package net.packetradio.mobile.transport

import java.io.IOException
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.packetradio.mobile.model.KissParams
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.model.PortRunner
import net.packetradio.mobile.protocol.Ax25
import net.packetradio.mobile.protocol.Ax25Address
import net.packetradio.mobile.protocol.Ax25FrameContent
import net.packetradio.mobile.protocol.Kiss
import net.packetradio.mobile.protocol.KissDecoder

/**
 * Bare KISS-over-TCP client. Direct port of `pr-ax25::kiss_runner`'s TCP
 * path — connected mode is out of scope for bare KISS (same boundary as the
 * desktop app, which never implements the AX.25 ARQ state machine itself),
 * so only [PortCommand.SendUnproto] and [PortCommand.Disconnect] do
 * anything; everything else is silently ignored.
 *
 * A raw KISS TNC never echoes our own transmissions the way AGWPE does, so
 * a "TX" Monitor line is logged locally right after sending, exactly like
 * the desktop's `command_loop`.
 */
class KissTcpRunner(private val config: PortConfig.KissTcp) : PortRunner {

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
            fun writeKiss(bytes: ByteArray) = synchronized(output) {
                output.write(bytes)
                output.flush()
            }

            sendKissParams(config.kissParams, ::writeKiss)

            val readerJob = launch {
                val decoder = KissDecoder()
                val buf = ByteArray(1024)
                try {
                    val input = socket.getInputStream()
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        for ((cmd, payload) in decoder.feed(buf.copyOf(n))) {
                            if (cmd and 0x0F != 0) continue // only interested in type-0 data frames
                            val frame = Ax25.decodeFrame(payload) ?: continue
                            events.send(PortEvent.StationHeard(frame.source.label()))
                            val to = (frame.content as? Ax25FrameContent.UnnumberedInformation)?.let { frame.destination.label() }
                            events.send(PortEvent.Monitor(Ax25.describeFrame(frame), to))
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed from the command loop below — normal shutdown path.
                }
            }

            try {
                for (command in commands) {
                    when (command) {
                        is PortCommand.SendUnproto -> {
                            val source = Ax25Address.parse(config.myCall)
                            val destination = Ax25Address.parse(command.dest)
                            val digis = command.via.map { Ax25Address.parse(it) }
                            val frame = Ax25.encodeUiFrame(source, destination, digis, info = command.bytes)
                            writeKiss(Kiss.encodeDataFrame(KISS_PORT, frame))

                            val viaSuffix = if (command.via.isEmpty()) "" else " via ${command.via.joinToString(",")}"
                            events.send(
                                PortEvent.Monitor(
                                    "${config.myCall} > ${command.dest}$viaSuffix [unproto TX]: ${String(command.bytes)}",
                                    null,
                                ),
                            )
                        }
                        PortCommand.Disconnect -> break
                        else -> {} // connected-mode commands: out of scope for bare KISS
                    }
                }
            } finally {
                socket.close()
                readerJob.cancelAndJoin()
                events.send(PortEvent.PortDisconnected(null))
            }
        }
    }

    private fun sendKissParams(params: KissParams, write: (ByteArray) -> Unit) {
        params.txDelay?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_TX_DELAY, it)) }
        params.persistence?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_PERSISTENCE, it)) }
        params.slotTime?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_SLOT_TIME, it)) }
        params.fullDuplex?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_FULL_DUPLEX, if (it) 1 else 0)) }
    }

    companion object {
        /** Hardcoded — Direwolf and most raw-KISS TNC servers are single-channel. */
        const val KISS_PORT = 0
    }
}
