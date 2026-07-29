package net.packetradio.mobile.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PortConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `agwpe config round-trips through json`() {
        val config: PortConfig = PortConfig.Agwpe(
            host = "192.168.1.50",
            port = 8000,
            radioPort = 0,
            myCall = "KD3BFP-9",
            login = AgwpeLogin(username = "user", password = "pass"),
        )
        val encoded = json.encodeToString(PortConfig.Agwpe.serializer(), config as PortConfig.Agwpe)
        val decoded = json.decodeFromString(PortConfig.Agwpe.serializer(), encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `kiss tcp config defaults kiss params to all-null`() {
        val config = PortConfig.KissTcp(host = "192.168.1.50", port = 8001, myCall = "KD3BFP-9")
        assertEquals(KissParams(), config.kissParams)
        assertEquals(null, config.kissParams.txDelay)
    }

    @Test
    fun `bluetooth kiss config round-trips through json`() {
        val config = PortConfig.BluetoothKiss(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "Mobilinkd TNC3",
            myCall = "KD3BFP-9",
            kissParams = KissParams(txDelay = 30, fullDuplex = false),
        )
        val encoded = json.encodeToString(config)
        val decoded = json.decodeFromString<PortConfig.BluetoothKiss>(encoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `port kind predicates match desktop semantics`() {
        val agwpe = PortConfig.Agwpe("h", 1, 0, "C")
        val kissTcp = PortConfig.KissTcp("h", 1, "C")
        val telnet = PortConfig.Telnet("h", 1)

        assertEquals(true, agwpe.supportsConnect())
        assertEquals(true, agwpe.supportsUnproto())
        assertEquals(false, kissTcp.supportsConnect())
        assertEquals(true, kissTcp.supportsUnproto())
        assertEquals(false, telnet.needsNode())
    }
}
