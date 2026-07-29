package net.packetradio.mobile.transport

import net.packetradio.mobile.model.PortConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class PortRunnerFactoryTest {

    @Test
    fun `creates an AgwpeRunner for Agwpe config`() {
        val runner = PortRunnerFactory.create(PortConfig.Agwpe("h", 1, 0, "C"))
        assertTrue(runner is AgwpeRunner)
    }

    @Test
    fun `creates a KissTcpRunner for KissTcp config`() {
        val runner = PortRunnerFactory.create(PortConfig.KissTcp("h", 1, "C"))
        assertTrue(runner is KissTcpRunner)
    }

    @Test
    fun `creates a BluetoothKissRunner for BluetoothKiss config`() {
        val runner = PortRunnerFactory.create(PortConfig.BluetoothKiss("AA:BB", "TNC", "C"))
        assertTrue(runner is BluetoothKissRunner)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `throws for not-yet-implemented transports`() {
        PortRunnerFactory.create(PortConfig.UsbSerialKiss(0, 0, 9600, "C"))
    }
}
