package net.packetradio.mobile.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
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
import java.util.UUID

private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val NUS_RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val NUS_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
private val CCCD_UUID        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

/**
 * BLE KISS TNC runner — connects to a lora-kiss-tnc (or any NUS-based KISS TNC)
 * at [config].deviceAddress using the Nordic UART Service GATT profile.
 *
 * NUS RX char (0x0002): client writes KISS bytes here — write-without-response.
 * NUS TX char (0x0003): TNC sends KISS bytes here as notifications.
 *
 * The device must already be bonded via Android's Bluetooth settings.
 * Android handles pairing/re-encryption automatically on reconnect once bonded.
 *
 * GATT callbacks are bridged to coroutines via [CompletableDeferred] and [Channel].
 */
class BluetoothLeKissRunner(
    private val config: PortConfig.BluetoothLeKiss,
    private val context: Context,
) : PortRunner {

    override suspend fun run(commands: ReceiveChannel<PortCommand>, events: SendChannel<PortEvent>) {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                events.send(PortEvent.PortError("BLUETOOTH_CONNECT permission not granted — grant it in Settings > App permissions."))
                return@withContext
            }

            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                events.send(PortEvent.PortError("No Bluetooth adapter found on this device."))
                return@withContext
            }

            val device = try {
                adapter.getRemoteDevice(config.deviceAddress)
            } catch (e: IllegalArgumentException) {
                events.send(PortEvent.PortError("Invalid BLE address '${config.deviceAddress}': ${e.message}"))
                return@withContext
            }

            val displayName = config.deviceName.ifBlank { config.deviceAddress }
            events.send(PortEvent.PortLog("BLE: connecting to $displayName…"))

            val stateChanged = Channel<Int>(Channel.CONFLATED)
            val discovered   = CompletableDeferred<Boolean>()
            val mtuResult    = CompletableDeferred<Int>()
            val cccdResult   = CompletableDeferred<Boolean>()
            val notifyData   = Channel<ByteArray>(Channel.UNLIMITED)

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    stateChanged.trySend(newState)
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    mtuResult.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
                }
                override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
                    if (desc.uuid == CCCD_UUID) cccdResult.complete(status == BluetoothGatt.GATT_SUCCESS)
                }
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notifyData.trySend(char.value.copyOf())
                }
                override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray) {
                    notifyData.trySend(value)
                }
            }

            val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: run {
                    events.send(PortEvent.PortError("BLE: failed to initiate connection to $displayName."))
                    return@withContext
                }

            try {
                // ── Connection ──────────────────────────────────────────────────────────
                if (stateChanged.receive() != BluetoothProfile.STATE_CONNECTED) {
                    events.send(PortEvent.PortError("BLE: failed to connect to $displayName — ensure it is on, in range, and bonded."))
                    return@withContext
                }

                // ── MTU negotiation — larger ATT payloads reduce per-packet overhead ──
                gatt.requestMtu(517)
                val mtu = mtuResult.await()
                val chunkSize = mtu - 3  // 3-byte ATT header

                // ── Service & characteristic discovery ──────────────────────────────────
                gatt.discoverServices()
                if (!discovered.await()) {
                    events.send(PortEvent.PortError("BLE: service discovery failed for $displayName."))
                    return@withContext
                }

                val service = gatt.getService(NUS_SERVICE_UUID) ?: run {
                    events.send(PortEvent.PortError("BLE: NUS service not found on $displayName — is this a lora-kiss-tnc device?"))
                    return@withContext
                }
                val rxChar = service.getCharacteristic(NUS_RX_CHAR_UUID) ?: run {
                    events.send(PortEvent.PortError("BLE: NUS RX characteristic not found.")); return@withContext
                }
                val txChar = service.getCharacteristic(NUS_TX_CHAR_UUID) ?: run {
                    events.send(PortEvent.PortError("BLE: NUS TX characteristic not found.")); return@withContext
                }

                // ── Subscribe to TX notifications ───────────────────────────────────────
                gatt.setCharacteristicNotification(txChar, true)
                val cccd = txChar.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                    if (!cccdResult.await()) {
                        events.send(PortEvent.PortError("BLE: failed to enable TX notifications on $displayName."))
                        return@withContext
                    }
                }

                events.send(PortEvent.PortConnected)
                events.send(PortEvent.PortLog("BLE: connected to $displayName — MTU $mtu, write chunk ${chunkSize}B."))

                // ── Write helper — chunks KISS bytes across BLE ATT packets ────────────
                fun writeKiss(bytes: ByteArray) {
                    var offset = 0
                    while (offset < bytes.size) {
                        val end = minOf(offset + chunkSize, bytes.size)
                        val chunk = bytes.copyOfRange(offset, end)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(rxChar, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        } else {
                            @Suppress("DEPRECATION")
                            rxChar.value = chunk
                            @Suppress("DEPRECATION")
                            rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(rxChar)
                        }
                        offset = end
                    }
                }

                sendKissParams(config.kissParams, ::writeKiss)
                describeKissParams(config.kissParams)?.let {
                    events.send(PortEvent.PortLog("BLE: KISS params sent: $it"))
                }

                val myCallLabel = Ax25Address.parse(config.myCall).label()
                val driver      = KissConnectedModeDriver(this, config.myCall, events, ::writeKiss, KISS_PORT)
                val framesIn    = Channel<Ax25DecodedFrame>(Channel.UNLIMITED)

                // ── RX: decode KISS from BLE notifications ──────────────────────────────
                val readerJob = launch {
                    val decoder = KissDecoder()
                    for (chunk in notifyData) {
                        for ((cmd, payload) in decoder.feed(chunk)) {
                            if (cmd and 0x0F != 0) continue
                            val frame = Ax25.decodeFrame(payload) ?: continue
                            events.send(PortEvent.StationHeard(frame.source.label()))
                            val ui = frame.content as? Ax25FrameContent.UnnumberedInformation
                            events.send(PortEvent.Monitor(Ax25.describeFrame(frame), ui?.let { frame.destination.label() }))
                            if (ui != null) {
                                events.send(PortEvent.UnprotoReceived(frame.source.label(), frame.destination.label(), ui.pid, ui.info))
                            }
                            if (frame.content !is Ax25FrameContent.UnnumberedInformation && frame.destination.label() == myCallLabel) {
                                framesIn.send(frame)
                            }
                        }
                    }
                }

                // ── Command / event select loop ─────────────────────────────────────────
                var shouldStop = false
                var disconnectReason: String? = null
                try {
                    while (!shouldStop) {
                        select<Unit> {
                            stateChanged.onReceive { newState ->
                                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                    disconnectReason = "BLE device disconnected"
                                    shouldStop = true
                                }
                            }
                            commands.onReceiveCatching { result ->
                                val command = result.getOrNull()
                                    ?: run { shouldStop = true; return@onReceiveCatching }
                                when (command) {
                                    is PortCommand.SendUnproto -> {
                                        val frame = Ax25.encodeUiFrame(
                                            source      = Ax25Address.parse(config.myCall),
                                            destination = Ax25Address.parse(command.dest),
                                            digipeaters = command.via.map { Ax25Address.parse(it) },
                                            info        = command.bytes,
                                        )
                                        writeKiss(Kiss.encodeDataFrame(KISS_PORT, frame))
                                        val viaSuffix = if (command.via.isEmpty()) "" else " via ${command.via.joinToString(",")}"
                                        events.send(PortEvent.Monitor(
                                            "${config.myCall} > ${command.dest}$viaSuffix [UI TX]: ${String(command.bytes)}",
                                            command.dest,
                                        ))
                                    }
                                    PortCommand.Probe          -> writeKiss(byteArrayOf(Kiss.FEND.toByte()))
                                    is PortCommand.OpenConnection  -> driver.openConnection(command.remote, command.via)
                                    is PortCommand.Send            -> driver.send(command.id, command.bytes)
                                    is PortCommand.CloseConnection -> driver.closeConnection(command.id)
                                    PortCommand.Disconnect         -> shouldStop = true
                                    else                           -> {}
                                }
                            }
                            framesIn.onReceive { frame -> driver.frameReceived(frame) }
                            driver.timerFiredEvents.onReceive { id -> driver.onTimerFired(id) }
                            driver.t3FiredEvents.onReceive    { id -> driver.onT3Fired(id) }
                        }
                    }
                } finally {
                    driver.shutdown()
                    notifyData.close()
                    readerJob.cancelAndJoin()
                    framesIn.close()
                    events.send(PortEvent.PortDisconnected(disconnectReason))
                }
            } finally {
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close()       } catch (_: Exception) {}
            }
        }
    }

    private fun sendKissParams(params: KissParams, write: (ByteArray) -> Unit) {
        params.txDelay?.let     { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_TX_DELAY,    it)) }
        params.persistence?.let { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_PERSISTENCE, it)) }
        params.slotTime?.let    { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_SLOT_TIME,   it)) }
        params.fullDuplex?.let  { write(Kiss.encodeParamFrame(KISS_PORT, Kiss.CMD_FULL_DUPLEX, if (it) 1 else 0)) }
    }

    private fun describeKissParams(params: KissParams): String? {
        val parts = buildList {
            params.txDelay?.let     { add("TXDELAY=$it") }
            params.persistence?.let { add("PERSIST=$it") }
            params.slotTime?.let    { add("SLOTTIME=$it") }
            params.fullDuplex?.let  { add("FULLDUP=$it") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    companion object {
        private const val KISS_PORT = 0
    }
}
