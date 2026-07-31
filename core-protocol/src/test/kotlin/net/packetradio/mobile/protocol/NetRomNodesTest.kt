package net.packetradio.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetRomNodesTest {

    /** Mirrors [Ax25]'s shifted-ASCII + SSID encoding for a bare 7-byte callsign field. */
    private fun encodeCallsignField(address: Ax25Address): ByteArray {
        val call = address.callsign.uppercase().take(6).padEnd(6)
        val out = ByteArray(7)
        for (i in 0 until 6) out[i] = ((call[i].code shl 1) and 0xFF).toByte()
        out[6] = ((address.ssid and 0x0F) shl 1).toByte()
        return out
    }

    private fun asciiField(text: String, len: Int): ByteArray =
        text.uppercase().take(len).padEnd(len).toByteArray(Charsets.US_ASCII)

    private fun buildPayload(senderAlias: String, neighbors: List<NetRomNeighbor>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFF)
        out.write(asciiField(senderAlias, 6))
        for (n in neighbors) {
            out.write(encodeCallsignField(n.callsign))
            out.write(asciiField(n.alias, 6))
            out.write(encodeCallsignField(n.bestNeighbor))
            out.write(n.quality and 0xFF)
        }
        return out.toByteArray()
    }

    @Test
    fun `decodes sender alias and every neighbor record`() {
        val neighbor1 = NetRomNeighbor(Ax25Address("KD3BFP", 9), "BFPNOD", Ax25Address("N0CALL", 1), 200)
        val neighbor2 = NetRomNeighbor(Ax25Address("WIDE1", 0), "WIDE", Ax25Address("KD3BFP", 9), 128)
        val payload = buildPayload("MYNODE", listOf(neighbor1, neighbor2))

        val decoded = decodeNetRomNodes(payload)!!
        assertEquals("MYNODE", decoded.senderAlias)
        assertEquals(2, decoded.neighbors.size)

        assertEquals(Ax25Address("KD3BFP", 9), decoded.neighbors[0].callsign)
        assertEquals("BFPNOD", decoded.neighbors[0].alias)
        assertEquals(Ax25Address("N0CALL", 1), decoded.neighbors[0].bestNeighbor)
        assertEquals(200, decoded.neighbors[0].quality)

        assertEquals(Ax25Address("WIDE1", 0), decoded.neighbors[1].callsign)
        assertEquals("WIDE", decoded.neighbors[1].alias)
        assertEquals(128, decoded.neighbors[1].quality)
    }

    @Test
    fun `a sender with no neighbors yet decodes to an empty list`() {
        val payload = buildPayload("LONELY", emptyList())
        val decoded = decodeNetRomNodes(payload)!!
        assertEquals("LONELY", decoded.senderAlias)
        assertEquals(emptyList<NetRomNeighbor>(), decoded.neighbors)
    }

    @Test
    fun `a trailing partial record is ignored rather than crashing`() {
        val payload = buildPayload("MYNODE", listOf(NetRomNeighbor(Ax25Address("KD3BFP", 9), "X", Ax25Address("N0CALL", 0), 1))) +
            byteArrayOf(1, 2, 3) // fewer than 20 bytes left over
        val decoded = decodeNetRomNodes(payload)!!
        assertEquals(1, decoded.neighbors.size)
    }

    @Test
    fun `missing the 0xFF marker means it's not a NODES broadcast`() {
        val payload = buildPayload("MYNODE", emptyList())
        payload[0] = 0x00
        assertNull(decodeNetRomNodes(payload))
    }

    @Test
    fun `a buffer shorter than the header is not a NODES broadcast`() {
        assertNull(decodeNetRomNodes(byteArrayOf(0xFF.toByte(), 1, 2)))
    }
}
