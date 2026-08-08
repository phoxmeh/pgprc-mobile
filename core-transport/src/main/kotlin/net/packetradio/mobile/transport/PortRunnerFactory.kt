package net.packetradio.mobile.transport

import android.content.Context
import net.packetradio.mobile.model.PortConfig
import net.packetradio.mobile.model.PortRunner

/** Maps a [PortConfig] to the [PortRunner] that knows how to speak it. */
object PortRunnerFactory {
    fun create(config: PortConfig, context: Context): PortRunner = when (config) {
        is PortConfig.Agwpe -> AgwpeRunner(config)
        is PortConfig.KissTcp -> KissTcpRunner(config)
        is PortConfig.BluetoothKiss -> BluetoothKissRunner(config)
        is PortConfig.UsbSerialKiss -> throw UnsupportedOperationException(
            "USB-serial KISS transport not implemented yet",
        )
        is PortConfig.UsbAudio -> UsbAudioRunner(config, context)
        is PortConfig.Telnet -> TelnetRunner(config)
        is PortConfig.Ssh -> throw UnsupportedOperationException("SSH transport not implemented yet")
    }
}
