package net.packetradio.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgwFrameTest {

    @Test
    fun `encode then decode round-trips a data frame`() {
        val frame = AgwFrame.create(
            port = 0,
            dataKind = 'D',
            callFrom = "KD3BFP-9",
            callTo = "N0CALL-1",
            data = "hello".toByteArray(),
        )
        val encoded = frame.encode()
        assertEquals(AgwFrame.HEADER_LEN + 5, encoded.size)

        val (decoded, consumed) = AgwFrame.decode(encoded)!!
        assertEquals(consumed, encoded.size)
        assertEquals(frame, decoded)
    }

    @Test
    fun `decode returns null when header is incomplete`() {
        assertNull(AgwFrame.decode(ByteArray(10)))
    }

    @Test
    fun `decode returns null when payload has not fully arrived yet`() {
        val encoded = AgwFrame.create(0, 'D', "A", "B", ByteArray(20)).encode()
        // Header claims 20 bytes of payload but we only hand over the header + 5.
        val partial = encoded.copyOfRange(0, AgwFrame.HEADER_LEN + 5)
        assertNull(AgwFrame.decode(partial))
    }

    @Test
    fun `write and read padded round-trip a short call sign`() {
        val padded = AgwFrame.writePadded("KD3BFP-9", 10)
        assertEquals(10, padded.size)
        assertEquals("KD3BFP-9", AgwFrame.readPadded(padded))
    }

    @Test
    fun `write padded truncates to leave a trailing NUL even for a full-length value`() {
        // A 9-char value exactly fills 9 of 10 bytes, leaving byte 9 as NUL.
        val padded = AgwFrame.writePadded("123456789", 10)
        assertEquals(0.toByte(), padded[9])
        assertEquals("123456789", AgwFrame.readPadded(padded))
    }

    @Test
    fun `textFromBytes strips every embedded NUL not just a trailing one`() {
        val bytes = byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte(), 0, 0, ' '.code.toByte())
        assertEquals("ab", AgwFrame.textFromBytes(bytes))
    }

    @Test
    fun `encodeDigiPath packs a count byte then one 10-byte field per digi`() {
        val encoded = AgwFrame.encodeDigiPath(listOf("WIDE1-1", "WIDE2-1"))
        assertEquals(1 + 2 * AgwFrame.CALL_FIELD_LEN, encoded.size)
        assertEquals(2, encoded[0].toInt())
    }

    @Test
    fun `unprotoVia appends info immediately after the digi path with no separator`() {
        val frame = AgwFrame.unprotoVia(0, "KD3BFP-9", "CQ", listOf("WIDE1-1"), "hi".toByteArray())
        val digiPathLen = 1 + AgwFrame.CALL_FIELD_LEN
        assertEquals(digiPathLen + 2, frame.data.size)
        assertEquals("hi", String(frame.data, digiPathLen, 2))
    }

    @Test
    fun `FrameDecoder accumulates partial reads across multiple feeds`() {
        val encoded = AgwFrame.create(0, 'D', "A", "B", "payload".toByteArray()).encode()
        val decoder = FrameDecoder()
        assertNull(decoder.nextFrame())

        decoder.feed(encoded.copyOfRange(0, 20))
        assertNull(decoder.nextFrame())

        decoder.feed(encoded.copyOfRange(20, encoded.size))
        val frame = decoder.nextFrame()
        assertEquals("payload", String(frame!!.data))
        assertNull(decoder.nextFrame())
    }
}
