package net.packetradio.mobile.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
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
 * Classic Bluetooth SPP KISS client (Mobilinkd/TNC3-style TNCs). Same bare-KISS command
 * handling as [KissTcpRunner], including connected mode via [KissConnectedModeDriver] — the
 * sole difference is the transport: an RFCOMM [android.bluetooth.BluetoothSocket] to the
 * well-known SPP UUID instead of a TCP socket. The device must already be paired (bonded) via
 * Android's own Bluetooth settings; this runner only opens a connection to it, it never scans
 * or pairs.
 */
class BluetoothKissRunner(private val config: PortConfig.BluetoothKiss) : PortRunner {

    override suspend fun run(commands: ReceiveChannel<PortCommand>, events: SendChannel<PortEvent>) {
        withContext(Dispatchers.IO) {
            // No Context is threaded through PortRunner/PortRunnerFactory, so the
            // deprecated static getter is the pragmatic choice here (still works
            // on every API level this app targets; only the newer
            // context.getSystemService(BluetoothManager::class.java) form is
            // actually removed-vs-deprecated, and that one needs a Context).
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                events.send(PortEvent.PortError("no Bluetooth adapter on this device"))
                return@withContext
            }

            events.send(PortEvent.PortLog("Connecting to ${config.deviceName} (${config.deviceAddress})…"))

            val connection = try {
                // No BLUETOOTH_SCAN dependency needed: this app only ever connects to an
                // already-paired device (see BluetoothDevicePicker), never discovers/pairs
                // one itself, so there's no discovery of our own to cancel here.
                val device = adapter.getRemoteDevice(config.deviceAddress)
                connectRfcomm(device)
            } catch (e: IOException) {
                events.send(PortEvent.PortError(e.message ?: "connection failed"))
                return@withContext
            } catch (e: SecurityException) {
                events.send(PortEvent.PortError(e.message ?: "Bluetooth permission not granted"))
                return@withContext
            }
            val socket = connection.socket
            events.send(PortEvent.PortConnected)
            events.send(PortEvent.PortLog(connection.describeForLog()))

            val output = socket.outputStream
            fun writeKiss(bytes: ByteArray) = synchronized(output) {
                output.write(bytes)
                output.flush()
            }

            sendKissParams(config.kissParams, ::writeKiss)
            describeKissParams(config.kissParams)?.let { events.send(PortEvent.PortLog("KISS params sent: $it")) }

            val myCallLabel = Ax25Address.parse(config.myCall).label()
            val driver = KissConnectedModeDriver(this, config.myCall, events, ::writeKiss, KISS_PORT)
            val framesIn = Channel<Ax25DecodedFrame>(Channel.UNLIMITED)

            val readerJob = launch {
                val decoder = KissDecoder()
                val buf = ByteArray(1024)
                try {
                    val input = socket.inputStream
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        for ((cmd, payload) in decoder.feed(buf.copyOf(n))) {
                            if (cmd and 0x0F != 0) continue // only interested in type-0 data frames
                            val frame = Ax25.decodeFrame(payload) ?: continue
                            events.send(PortEvent.StationHeard(frame.source.label()))
                            val ui = frame.content as? Ax25FrameContent.UnnumberedInformation
                            events.send(PortEvent.Monitor(Ax25.describeFrame(frame), ui?.let { frame.destination.label() }))
                            if (ui != null) {
                                events.send(PortEvent.UnprotoReceived(frame.source.label(), frame.destination.label(), ui.pid, ui.info))
                            }
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
                                            "${config.myCall} > ${command.dest}$viaSuffix [UI TX]: ${String(command.bytes)}",
                                            command.dest,
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
                        driver.t3FiredEvents.onReceive { id -> driver.onT3Fired(id) }
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

    /**
     * `createRfcommSocketToServiceRecord(uuid)` relies on the peer answering
     * an SDP query for that UUID — plenty of embedded serial-over-Bluetooth
     * boards (this class of TNC included) don't implement SDP properly, so
     * `connect()` can report success while zero bytes actually reach the
     * peer (found via live testing: the app connected cleanly and wrote
     * frames with no exception, but the TNC's own data LED never lit).
     * `createRfcommSocket(channel)` — a hidden but long-standing API,
     * reached via reflection — opens the RFCOMM channel directly instead of
     * going through SDP, which is the standard workaround for this class of
     * device; channel 1 is what virtually all of them listen on. Falls back
     * to the UUID-based method if the hidden API is ever unavailable.
     */
    /** Which of [connectRfcomm]'s two paths actually got used — surfaced as a [PortEvent.PortLog] line. */
    private class RfcommConnection(val socket: BluetoothSocket, val viaDirectChannel: Boolean, val directChannelError: String?) {
        fun describeForLog(): String = if (viaDirectChannel) {
            "Connected via direct RFCOMM channel $RFCOMM_CHANNEL"
        } else {
            val fallback = "Connected via SDP service record lookup"
            if (directChannelError != null) "$fallback (direct channel $RFCOMM_CHANNEL failed: $directChannelError)" else fallback
        }
    }

    private fun connectRfcomm(device: BluetoothDevice): RfcommConnection {
        val direct = try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
        } catch (e: ReflectiveOperationException) {
            null
        }
        if (direct != null) {
            try {
                direct.connect()
                return RfcommConnection(direct, viaDirectChannel = true, directChannelError = null)
            } catch (e: IOException) {
                direct.close()
                val fallback = device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
                return RfcommConnection(fallback, viaDirectChannel = false, directChannelError = e.message)
            }
        }
        val fallback = device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        return RfcommConnection(fallback, viaDirectChannel = false, directChannelError = null)
    }

    private fun sendKissParams(params: KissParams, write: (ByteArray) -> Unit) {
        params.txDelay?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_TX_DELAY, it)) }
        params.persistence?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_PERSISTENCE, it)) }
        params.slotTime?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_SLOT_TIME, it)) }
        params.fullDuplex?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_FULL_DUPLEX, if (it) 1 else 0)) }
    }

    private fun describeKissParams(params: KissParams): String? {
        val parts = buildList {
            params.txDelay?.let { add("TXDELAY=$it") }
            params.persistence?.let { add("PERSIST=$it") }
            params.slotTime?.let { add("SLOTTIME=$it") }
            params.fullDuplex?.let { add("FULLDUP=$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    companion object {
        /** Hardcoded — a Bluetooth SPP TNC is single-channel. */
        const val KISS_PORT = 0

        /** Standard Serial Port Profile UUID — fallback path only, see [connectRfcomm]. */
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** The RFCOMM channel almost every embedded serial-over-Bluetooth device listens on. */
        private const val RFCOMM_CHANNEL = 1
    }
}
