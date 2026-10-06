package net.packetradio.mobile.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import net.packetradio.mobile.model.PortCommand
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortEvent
import net.packetradio.mobile.model.PortRunner
import net.packetradio.mobile.model.PttMethod
import net.packetradio.mobile.modem.AfskModem
import net.packetradio.mobile.modem.toAfskConfig
import net.packetradio.mobile.protocol.Ax25
import net.packetradio.mobile.protocol.Ax25Address
import net.packetradio.mobile.protocol.Ax25DecodedFrame
import net.packetradio.mobile.protocol.Ax25FrameContent
import net.packetradio.mobile.protocol.Kiss
import net.packetradio.mobile.protocol.KissDecoder
import java.io.IOException

private const val ACTION_USB_AUDIO_RUNNER_PERMISSION = "net.packetradio.mobile.USB_AUDIO_RUNNER_PERMISSION"

/**
 * USB Audio/PTT transport (Digirig and compatible interfaces).
 *
 * Opens the USB serial port for RTS PTT control, then runs the [AfskModem] against
 * the selected USB audio device.  Received AX.25 frames from the modem are forwarded
 * as [PortEvent.Monitor] and [PortEvent.StationHeard] events; outgoing frames are
 * built from [PortCommand.SendUnproto] and connected-mode commands, then queued to the
 * modem's TX channel.
 *
 * Connected-mode AX.25 (SABM/UA/I-frames/DISC) is driven by [KissConnectedModeDriver],
 * following the same pattern as [KissTcpRunner].
 *
 * PTT authority belongs exclusively to this class — the modem calls back to [setPtt],
 * which toggles RTS on the serial port.
 */
class UsbAudioRunner(
    private val config: PortConfig.UsbAudio,
    private val context: Context,
) : PortRunner {

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean {
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_AUDIO_RUNNER_PERMISSION) return
                result.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(ACTION_USB_AUDIO_RUNNER_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // FLAG_MUTABLE is required so UsbManager can fill in EXTRA_PERMISSION_GRANTED.
        // On API < 31 (where FLAG_MUTABLE didn't exist) intents were mutable by default,
        // so we only apply the flag where it's needed and defined.
        val mutabilityFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_AUDIO_RUNNER_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
        )
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.requestPermission(device, pi)
        return try {
            result.await()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    override suspend fun run(commands: ReceiveChannel<PortCommand>, events: SendChannel<PortEvent>) {
        withContext(Dispatchers.IO) {
            // ── USB serial for PTT ──────────────────────────────────────────────
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

            val serialDevice = usbManager.deviceList.values.firstOrNull {
                it.vendorId == config.serialVendorId && it.productId == config.serialProductId
            }
            if (serialDevice == null) {
                events.send(PortEvent.PortError(
                    "USB serial device (VID 0x${config.serialVendorId.toString(16)}, " +
                        "PID 0x${config.serialProductId.toString(16)}) not found.",
                ))
                return@withContext
            }
            if (!usbManager.hasPermission(serialDevice)) {
                val granted = requestUsbPermission(serialDevice)
                if (!granted) {
                    events.send(PortEvent.PortError(
                        "USB permission denied for ${serialDevice.productName ?: "serial port"}.",
                    ))
                    return@withContext
                }
            }

            val driver = UsbSerialProber.getDefaultProber().probeDevice(serialDevice)
            if (driver == null || driver.ports.isEmpty()) {
                events.send(PortEvent.PortError(
                    "No USB serial driver for ${serialDevice.productName ?: "device"}. " +
                        "Supported chips: CP210x, FTDI, CH34x, CDC ACM, Prolific.",
                ))
                return@withContext
            }
            if (config.serialPortIndex >= driver.ports.size) {
                events.send(PortEvent.PortError(
                    "Port ${config.serialPortIndex} not found on ${serialDevice.productName ?: "device"} " +
                        "(${driver.ports.size} port(s) available).",
                ))
                return@withContext
            }

            val connection = usbManager.openDevice(serialDevice) ?: run {
                events.send(PortEvent.PortError("Failed to open USB device connection."))
                return@withContext
            }
            val serialPort = driver.ports[config.serialPortIndex]
            try {
                serialPort.open(connection)
                serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                when (config.pttMethod) {
                    PttMethod.RTS -> {
                        // DTR must be held high so that interfaces that gate the RTS PTT line
                        // on DTR (Digirig, some homebrew interfaces) work correctly.
                        serialPort.dtr = true
                        serialPort.rts = false
                    }
                    PttMethod.DTR -> {
                        serialPort.dtr = false  // PTT off initially
                        serialPort.rts = false
                    }
                    PttMethod.CAT -> {
                        serialPort.dtr = true
                        serialPort.rts = false
                        serialPort.write("RX;".toByteArray(Charsets.US_ASCII), 200)
                    }
                }
            } catch (e: IOException) {
                events.send(PortEvent.PortError("Failed to open USB serial port: ${e.message}"))
                connection.close()
                return@withContext
            }

            fun setPtt(transmitting: Boolean) {
                try {
                    when (config.pttMethod) {
                        PttMethod.RTS -> serialPort.rts = transmitting
                        PttMethod.DTR -> serialPort.dtr = transmitting
                        PttMethod.CAT -> serialPort.write(
                            (if (transmitting) "TX;" else "RX;").toByteArray(Charsets.US_ASCII),
                            200,
                        )
                    }
                } catch (e: Exception) {
                    events.trySend(PortEvent.PortLog("PTT (${config.pttMethod}) error: ${e.message}"))
                }
            }

            // Signal the select loop when the USB device is physically removed.
            val usbDetached = Channel<Unit>(Channel.CONFLATED)
            val detachReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device?.vendorId == config.serialVendorId && device?.productId == config.serialProductId) {
                        usbDetached.trySend(Unit)
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, detachReceiver,
                IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                ContextCompat.RECEIVER_EXPORTED,
            )

            events.send(PortEvent.PortConnected)
            events.send(PortEvent.PortLog(
                "USB serial open: ${serialDevice.productName ?: "USB serial"} — PTT via ${config.pttMethod}.",
            ))

            // ── Modem TX/RX channels ────────────────────────────────────────────
            val txToModem = Channel<ByteArray>(64)
            val rxFromModem = Channel<ByteArray>(64)

            val afskConfig = config.modemMode.toAfskConfig()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val modem = AfskModem(
                config          = afskConfig,
                settings        = config.afskSettings,
                audioManager    = audioManager,
                audioDeviceName = config.audioProductName,
                txFrames        = txToModem,
                rxFrames        = rxFromModem,
                log             = { msg -> events.trySend(PortEvent.PortLog(msg)) },
                ptt             = ::setPtt,
            )

            val modemJob = launch { modem.run() }

            // ── Connected-mode driver ───────────────────────────────────────────
            // Frames from Ax25LinkSession.Effect.Transmit arrive KISS-wrapped; strip
            // the KISS header/trailer to recover the raw AX.25 bytes for the modem.
            val txKissDecoder = KissDecoder()
            fun writeKiss(kissBytes: ByteArray) {
                for ((cmd, payload) in txKissDecoder.feed(kissBytes)) {
                    if (cmd and 0x0F == 0) txToModem.trySend(payload)
                }
            }

            val myCallLabel = Ax25Address.parse(config.myCall).label()
            val connDriver = KissConnectedModeDriver(this, config.myCall, events, ::writeKiss, 0)

            // Non-UI frames addressed to us are routed here so the select loop can
            // hand them to connDriver.frameReceived() from the command-processing coroutine
            // (KissConnectedModeDriver requires single-coroutine access — see its kdoc).
            val framesIn = Channel<Ax25DecodedFrame>(Channel.UNLIMITED)

            // ── RX forwarding ───────────────────────────────────────────────────
            val rxForwardJob = launch(Dispatchers.IO) {
                for (frameBytes in rxFromModem) {
                    val decoded = Ax25.decodeFrame(frameBytes) ?: continue
                    val from = decoded.source.label()
                    val to   = decoded.destination.label()
                    val info = when (val c = decoded.content) {
                        is Ax25FrameContent.UnnumberedInformation -> c.info
                        is Ax25FrameContent.Information           -> c.info
                        else                                      -> byteArrayOf()
                    }
                    val text = Ax25.kissTextFromBytes(info)
                    events.trySend(PortEvent.Monitor(line = "$from>$to: $text", to = to))
                    events.trySend(PortEvent.StationHeard(from))
                    if (decoded.content !is Ax25FrameContent.UnnumberedInformation && to == myCallLabel) {
                        framesIn.trySend(decoded)
                    }
                }
            }

            // ── Command / event select loop ─────────────────────────────────────
            var shouldStop = false
            try {
                while (!shouldStop && isActive) {
                    select<Unit> {
                        commands.onReceiveCatching { result ->
                            val command = result.getOrNull()
                                ?: run { shouldStop = true; return@onReceiveCatching }
                            when (command) {
                                is PortCommand.Disconnect -> shouldStop = true

                                // No-op: unlike TCP/Bluetooth, a real USB unplug is already caught
                                // by the ACTION_USB_DEVICE_DETACHED receiver above. A zero-length
                                // write here was observed to periodically knock a composite USB
                                // device (CDC-ACM serial + USB Audio sharing one USB peripheral
                                // core) off the bus entirely, dropping the audio interface with it.
                                is PortCommand.Probe -> {}

                                is PortCommand.SendUnproto -> {
                                    val viaSuffix = if (command.via.isEmpty()) "" else " via ${command.via.joinToString(",")}"
                                    events.trySend(PortEvent.Monitor(
                                        line = "${config.myCall} > ${command.dest}$viaSuffix [UI TX]: ${String(command.bytes)}",
                                        to = command.dest,
                                    ))
                                    val frame = Ax25.encodeUiFrame(
                                        source      = Ax25Address.parse(config.myCall),
                                        destination = Ax25Address.parse(command.dest),
                                        digipeaters = command.via.map { Ax25Address.parse(it) },
                                        pid         = command.pid,
                                        info        = command.bytes,
                                    )
                                    txToModem.trySend(frame)
                                }

                                is PortCommand.OpenConnection  -> connDriver.openConnection(command.remote, command.via)
                                is PortCommand.Send            -> connDriver.send(command.id, command.bytes)
                                is PortCommand.CloseConnection -> connDriver.closeConnection(command.id)

                                else -> {}
                            }
                        }
                        framesIn.onReceive { frame -> connDriver.frameReceived(frame) }
                        connDriver.timerFiredEvents.onReceive { id -> connDriver.onTimerFired(id) }
                        connDriver.t3FiredEvents.onReceive    { id -> connDriver.onT3Fired(id) }
                        usbDetached.onReceive {
                            events.send(PortEvent.PortDisconnected("USB device disconnected."))
                            shouldStop = true
                        }
                    }
                }
            } finally {
                context.unregisterReceiver(detachReceiver)
                usbDetached.close()
                connDriver.shutdown()
                modemJob.cancel()
                rxForwardJob.cancelAndJoin()
                txToModem.close()
                rxFromModem.close()
                framesIn.close()
                try { serialPort.rts = false } catch (_: Exception) {}
                try { serialPort.close() } catch (_: Exception) {}
                connection.close()
                events.send(PortEvent.PortDisconnected(null))
            }
        }
    }
}
