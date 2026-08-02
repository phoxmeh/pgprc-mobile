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
    is PortConfig.UsbSerialKiss -> "KISS (USB)"
}

/**
 * Whether this port kind supports opening a connected-mode session by node callsign. AGWPE
 * offloads the AX.25 ARQ state machine to the host software (Direwolf, UZ7HO SoundModem);
 * KISS-TCP and Bluetooth KISS drive it themselves client-side (see
 * `net.packetradio.mobile.protocol.Ax25LinkSession`). USB-serial KISS isn't wired up to either
 * yet — its `PortRunner` doesn't exist at all — so it stays excluded until that lands.
 */
fun PortConfig.supportsConnect(): Boolean = when (this) {
    is PortConfig.Agwpe, is PortConfig.KissTcp, is PortConfig.BluetoothKiss, is PortConfig.Telnet -> true
    is PortConfig.Ssh, is PortConfig.UsbSerialKiss -> false
}

/** Whether this port kind can send one-shot unconnected (UI) frames. */
fun PortConfig.supportsUnproto(): Boolean = when (this) {
    is PortConfig.Agwpe, is PortConfig.KissTcp, is PortConfig.BluetoothKiss, is PortConfig.UsbSerialKiss -> true
    is PortConfig.Telnet, is PortConfig.Ssh -> false
}

/** Whether this port kind has a "node/destination callsign" concept at all. */
fun PortConfig.needsNode(): Boolean = supportsConnect() || supportsUnproto()
