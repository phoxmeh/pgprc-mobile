package net.packetradio.mobile.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KissTest {

    @Test
    fun `encodeDataFrame wraps payload in FEND with a data command byte`() {
        val encoded = Kiss.encodeDataFrame(kissPort = 0, payload = byteArrayOf(1, 2, 3))
        assertEquals(Kiss.FEND, encoded[0].toInt() and 0xFF)
        assertEquals(0, encoded[1].toInt()) // port 0, frame type 0 (data)
        assertEquals(Kiss.FEND, encoded.last().toInt() and 0xFF)
    }

    @Test
    fun `command byte packs port into the high nibble and frame type into the low nibble`() {
        val encoded = Kiss.encodeDataFrame(kissPort = 3, payload = byteArrayOf())
        assertEquals(0x30, encoded[1].toInt() and 0xFF)
    }

    @Test
    fun `FEND and FESC bytes in the payload are escaped`() {
        val encoded = Kiss.encodeDataFrame(0, byteArrayOf(Kiss.FEND.toByte(), Kiss.FESC.toByte()))
        val expected = byteArrayOf(
            Kiss.FEND.toByte(), 0,
            Kiss.FESC.toByte(), Kiss.TFEND.toByte(),
            Kiss.FESC.toByte(), Kiss.TFESC.toByte(),
            Kiss.FEND.toByte(),
        )
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun `decoder round-trips an encoded data frame`() {
        val payload = byteArrayOf(0x10, Kiss.FEND.toByte(), 0x20, Kiss.FESC.toByte(), 0x30)
        val encoded = Kiss.encodeDataFrame(kissPort = 1, payload = payload)

        val decoder = KissDecoder()
        val frames = decoder.feed(encoded)

        assertEquals(1, frames.size)
        val (cmd, decodedPayload) = frames[0]
        assertEquals(0x10, cmd) // port 1, frame type 0
        assertArrayEquals(payload, decodedPayload)
    }

    @Test
    fun `decoder handles a frame split across multiple feed calls`() {
        val encoded = Kiss.encodeDataFrame(0, byteArrayOf(1, 2, 3, 4, 5))
        val decoder = KissDecoder()

        val firstHalf = decoder.feed(encoded.copyOfRange(0, 4))
        assertTrue(firstHalf.isEmpty())

        val secondHalf = decoder.feed(encoded.copyOfRange(4, encoded.size))
        assertEquals(1, secondHalf.size)
    }

    @Test
    fun `a lone FEND with nothing buffered does not produce a frame`() {
        val decoder = KissDecoder()
        val frames = decoder.feed(byteArrayOf(Kiss.FEND.toByte(), Kiss.FEND.toByte(), Kiss.FEND.toByte()))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `encodeParamFrame produces a single-byte payload with the given command type`() {
        val encoded = Kiss.encodeParamFrame(kissPort = 0, kind = Kiss.CMD_TX_DELAY, value = 30)
        val decoder = KissDecoder()
        val (cmd, payload) = decoder.feed(encoded).single()
        assertEquals(Kiss.CMD_TX_DELAY, cmd)
        assertArrayEquals(byteArrayOf(30), payload)
    }

    @Test
    fun `two frames back to back both decode correctly`() {
        val a = Kiss.encodeDataFrame(0, byteArrayOf(1))
        val b = Kiss.encodeDataFrame(0, byteArrayOf(2))
        val decoder = KissDecoder()
        val frames = decoder.feed(a + b)
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(1), frames[0].second)
        assertArrayEquals(byteArrayOf(2), frames[1].second)
    }
}
