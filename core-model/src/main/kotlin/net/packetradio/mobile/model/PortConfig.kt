package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * One configured port's connection details. Ported from `pr-core::PortConfig`
 * (`pr-core/src/config.rs`) minus the raw AX.25 kernel-socket variant (no
 * `AF_AX25` on Android) and the local-process concerns that don't apply to a
 * remote-modem client, plus two variants the desktop app never needed:
 * [PortConfig.BluetoothKiss] (Mobilinkd/TNC3-style SPP TNCs) and
 * [PortConfig.UsbSerialKiss] (OTG-attached TNCs).
 */
@Serializable
sealed interface PortConfig {

    @Serializable
    data class Telnet(
        val host: String,
        val port: Int,
    ) : PortConfig

    @Serializable
    data class Ssh(
        val host: String,
        val port: Int,
        val user: String,
    ) : PortConfig

    @Serializable
    data class Agwpe(
        val host: String,
        val port: Int,
        val radioPort: Int,
        val myCall: String,
        val login: AgwpeLogin? = null,
    ) : PortConfig

    @Serializable
    data class KissTcp(
        val host: String,
        val port: Int,
        val myCall: String,
        val kissParams: KissParams = KissParams(),
    ) : PortConfig

    /** Classic Bluetooth SPP KISS TNC (Mobilinkd, TNC3, and similar). */
    @Serializable
    data class BluetoothKiss(
        val deviceAddress: String,
        val deviceName: String,
        val myCall: String,
        val kissParams: KissParams = KissParams(),
    ) : PortConfig

    /**
     * BLE KISS TNC using the Nordic UART Service (NUS) GATT profile, e.g.
     * lora-kiss-tnc. The device must already be bonded via Android's own
     * Bluetooth settings; this app only connects, it never scans or pairs.
     */
    @Serializable
    data class BluetoothLeKiss(
        val deviceAddress: String,
        val deviceName: String = "",
        val myCall: String,
        val kissParams: KissParams = KissParams(),
    ) : PortConfig

    /**
     * USB-serial KISS TNC over OTG. Identified by vendor/product id rather
     * than a device path — Android has no stable `/dev/ttyUSBx`-equivalent
     * exposed to apps, so the actual [android.hardware.usb.UsbDevice] is
     * re-resolved from [android.hardware.usb.UsbManager] at connect time.
     */
    @Serializable
    data class UsbSerialKiss(
        val usbVendorId: Int,
        val usbProductId: Int,
        val baud: Int,
        val myCall: String,
        val kissParams: KissParams = KissParams(),
    ) : PortConfig

    /**
     * USB sound card + serial PTT interface (Digirig, QRP Labs QDX/QMX, and similar).
     * PTT is controlled by asserting RTS on the USB serial port ([serialVendorId] /
     * [serialProductId]); audio is routed to/from the USB audio device matched by
     * [audioProductName] (from [android.media.AudioDeviceInfo.productName]).
     *
     * The AFSK modem lives in [net.packetradio.mobile.transport.UsbAudioRunner]
     * and converts between raw [android.media.AudioRecord] / [android.media.AudioTrack]
     * streams and AX.25 frames.
     */
    @Serializable
    data class UsbAudio(
        val serialVendorId: Int,
        val serialProductId: Int,
        /** Display name of the USB serial device; stored for UI only, not used to open the port. */
        val serialDeviceName: String = "",
        /** Product name of the USB audio device to use, or blank to auto-select the first one found. */
        val audioProductName: String = "",
        /** Zero-based index of the serial port to open for PTT (most radios expose port 1 for PTT). */
        val serialPortIndex: Int = 0,
        val myCall: String,
        val modemMode: ModemMode = ModemMode.BELL_202_1200,
        val afskSettings: AfskSettings = AfskSettings(),
    ) : PortConfig
}

@Serializable
data class AgwpeLogin(
    val username: String,
    val password: String,
)

/**
 * Bare-KISS TNC transmit parameters. `null` means "leave the TNC's own
 * default untouched" — ported verbatim from `pr-core::KissParams`.
 */
@Serializable
data class KissParams(
    /** Units of 10ms, e.g. 30 = 300ms. */
    val txDelay: Int? = null,
    /** KISS persistence, 0-255. */
    val persistence: Int? = null,
    /** Units of 10ms. */
    val slotTime: Int? = null,
    val fullDuplex: Boolean? = null,
)

@Serializable
data class PortEntry(
    val id: String,
    val name: String,
    val config: PortConfig,
    val autoconnect: Boolean = false,
)

fun PortConfig.kindLabel(): String = when (this) {
    is PortConfig.Telnet -> "Telnet"
    is PortConfig.Ssh -> "SSH"
    is PortConfig.Agwpe -> "AGWPE"
    is PortConfig.KissTcp -> "KISS (TCP)"
    is PortConfig.BluetoothKiss -> "KISS (Bluetooth)"
    is PortConfig.BluetoothLeKiss -> "KISS (BLE)"
    is PortConfig.UsbSerialKiss -> "KISS (USB)"
    is PortConfig.UsbAudio -> "USB Audio/PTT"
}

/**
 * Whether this port kind supports opening a connected-mode session by node callsign. AGWPE
 * offloads the AX.25 ARQ state machine to the host software (Direwolf, UZ7HO SoundModem);
 * KISS-TCP and Bluetooth KISS drive it themselves client-side (see
 * `net.packetradio.mobile.protocol.Ax25LinkSession`). USB-serial KISS isn't wired up to either
 * yet — its `PortRunner` doesn't exist at all — so it stays excluded until that lands.
 */
fun PortConfig.supportsConnect(): Boolean = when (this) {
    is PortConfig.Agwpe, is PortConfig.KissTcp, is PortConfig.BluetoothKiss,
    is PortConfig.BluetoothLeKiss, is PortConfig.UsbAudio -> true
    is PortConfig.Telnet, is PortConfig.Ssh, is PortConfig.UsbSerialKiss -> false
}

/** Telnet ports connect as a raw terminal session rather than via AX.25 — auto-open a tab on connect. */
fun PortConfig.isTerminalMode(): Boolean = this is PortConfig.Telnet

/** Whether this port kind can send one-shot unconnected (UI) frames. */
fun PortConfig.supportsUnproto(): Boolean = when (this) {
    is PortConfig.Agwpe, is PortConfig.KissTcp, is PortConfig.BluetoothKiss,
    is PortConfig.BluetoothLeKiss, is PortConfig.UsbSerialKiss, is PortConfig.UsbAudio -> true
    is PortConfig.Telnet, is PortConfig.Ssh -> false
}

/** Whether this port kind has a "node/destination callsign" concept at all. */
fun PortConfig.needsNode(): Boolean = supportsConnect() || supportsUnproto()

/** Whether the port's callsign is relevant to display/configure. */
fun PortConfig.hasCallsign(): Boolean = when (this) {
    is PortConfig.Telnet, is PortConfig.Ssh -> false
    else -> true
}
