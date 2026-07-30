package net.packetradio.mobile.transport

import java.io.IOException
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
import net.packetradio.mobile.model.KissParams
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.model.PortRunner
import net.packetradio.mobile.protocol.Ax25
import net.packetradio.mobile.protocol.Ax25Address
import net.packetradio.mobile.protocol.Ax25DecodedFrame
import net.packetradio.mobile.protocol.Ax25FrameContent
import net.packetradio.mobile.protocol.Kiss
import net.packetradio.mobile.protocol.KissDecoder

/**
 * Bare KISS-over-TCP client. Direct port of `pr-ax25::kiss_runner`'s TCP path for
 * [PortCommand.SendUnproto]; connected mode ([PortCommand.OpenConnection]/[PortCommand.Send]/
 * [PortCommand.CloseConnection]) is driven by [KissConnectedModeDriver] — this app's own AX.25
 * ARQ client, since a bare KISS TNC has no such state machine built in (unlike AGWPE, which
 * offloads it to the host software).
 *
 * A raw KISS TNC never echoes our own transmissions the way AGWPE does, so a "TX" Monitor line
 * is logged locally right after sending, exactly like the desktop's `command_loop`.
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

            val myCallLabel = Ax25Address.parse(config.myCall).label()
            val driver = KissConnectedModeDriver(this, config.myCall, events, ::writeKiss, KISS_PORT)
            val framesIn = Channel<Ax25DecodedFrame>(Channel.UNLIMITED)

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
                            // Only frames actually addressed to us, and never UI (that's Monitor's job
                            // above, promiscuously) — anything else here would mean answering on behalf
                            // of some other station's QSO we merely overheard on the shared channel.
                            if (frame.content !is Ax25FrameContent.UnnumberedInformation && frame.destination.label() == myCallLabel) {
                                framesIn.send(frame)
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Socket closed from the command loop below — normal shutdown path.
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
                                PortCommand.Connect -> {} // already connected at construction
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
                                // A lone FEND is a no-op frame delimiter per the KISS spec — every TNC
                                // just discards it, nothing is transmitted over RF. Only here to make the
                                // periodic PortManager watchdog's write actually touch the socket.
                                PortCommand.Probe -> writeKiss(byteArrayOf(Kiss.FEND.toByte()))
                                is PortCommand.OpenConnection -> driver.openConnection(command.remote, command.via)
                                is PortCommand.Send -> driver.send(command.id, command.bytes)
                                is PortCommand.CloseConnection -> driver.closeConnection(command.id)
                                PortCommand.Disconnect -> shouldStop = true
                            }
                        }
                        framesIn.onReceive { frame -> driver.frameReceived(frame) }
                        driver.timerFiredEvents.onReceive { id -> driver.onTimerFired(id) }
                    }
                }
            } catch (e: IOException) {
                disconnectReason = e.message ?: "connection lost"
            } finally {
                driver.shutdown()
                socket.close()
                readerJob.cancelAndJoin()
                events.send(PortEvent.PortDisconnected(disconnectReason))
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
