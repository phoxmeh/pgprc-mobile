package net.packetradio.mobile.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ax25Test {

    @Test
    fun `address parse splits callsign and SSID`() {
        assertEquals(Ax25Address("KD3BFP", 9), Ax25Address.parse("kd3bfp-9"))
        assertEquals(Ax25Address("N0CALL", 0), Ax25Address.parse("N0CALL"))
    }

    @Test
    fun `address label omits the SSID suffix when it is zero`() {
        assertEquals("N0CALL", Ax25Address("N0CALL", 0).label())
        assertEquals("KD3BFP-9", Ax25Address("KD3BFP", 9).label())
    }

    @Test
    fun `encodeUiFrame then decodeFrame round-trips source, destination, and info`() {
        val source = Ax25Address("KD3BFP", 9)
        val destination = Ax25Address("CQ", 0)
        val info = "hello world".toByteArray()

        val encoded = Ax25.encodeUiFrame(source, destination, info = info)
        val decoded = Ax25.decodeFrame(encoded)!!

        assertEquals(source, decoded.source)
        assertEquals(destination, decoded.destination)
        assertTrue(decoded.digipeaters.isEmpty())
        val content = decoded.content as Ax25FrameContent.UnnumberedInformation
        assertEquals(0xF0, content.pid)
        assertEquals("hello world", String(content.info))
    }

    @Test
    fun `encodeUiFrame with digipeaters round-trips the via path in order`() {
        val digis = listOf(Ax25Address("WIDE1", 1), Ax25Address("WIDE2", 1))
        val encoded = Ax25.encodeUiFrame(
            source = Ax25Address("KD3BFP", 9),
            destination = Ax25Address("CQ", 0),
            digipeaters = digis,
            info = "beacon".toByteArray(),
        )
        val decoded = Ax25.decodeFrame(encoded)!!
        assertEquals(digis, decoded.digipeaters)
    }

    @Test
    fun `describeFrame formats a UI frame with PID label suffix`() {
        val encoded = Ax25.encodeUiFrame(
            source = Ax25Address("KD3BFP", 9),
            destination = Ax25Address("N0CALL", 1),
            pid = 0xCF, // NET/ROM
            info = "hi".toByteArray(),
        )
        val decoded = Ax25.decodeFrame(encoded)!!
        assertEquals("KD3BFP-9 > N0CALL-1 [UI] [PID: NET/ROM]: hi", Ax25.describeFrame(decoded))
    }

    @Test
    fun `describeFrame strips embedded NUL but does not trim surrounding whitespace`() {
        val infoWithNul = byteArrayOf('h'.code.toByte(), 0, 'i'.code.toByte(), ' '.code.toByte())
        val encoded = Ax25.encodeUiFrame(Ax25Address("A"), Ax25Address("B"), info = infoWithNul)
        val decoded = Ax25.decodeFrame(encoded)!!
        // "hi " with the embedded NUL removed, but the trailing space kept —
        // unlike AgwFrame.textFromBytes, this one does not .trim().
        assertEquals("[UI]: hi ", Ax25.describeFrame(decoded).substringAfter("> B "))
    }

    @Test
    fun `decodeFrame recognizes the common U-frame types regardless of the poll-final bit`() {
        fun frameWithControl(control: Int): ByteArray {
            val addresses = Ax25.encodeUiFrame(Ax25Address("A"), Ax25Address("B"), info = ByteArray(0))
            // Swap out the UI control byte (right after the two 7-byte addresses) for the one under test.
            val out = addresses.copyOf()
            out[14] = control.toByte()
            return out.copyOfRange(0, 15) // no PID/info follows non-I/UI frames
        }

        assertEquals(Ax25FrameContent.SetAsynchronousBalancedMode, Ax25.decodeFrame(frameWithControl(0x2F))!!.content)
        assertEquals(Ax25FrameContent.SetAsynchronousBalancedMode, Ax25.decodeFrame(frameWithControl(0x3F))!!.content) // P bit set
        assertEquals(Ax25FrameContent.Disconnect, Ax25.decodeFrame(frameWithControl(0x43))!!.content)
        assertEquals(Ax25FrameContent.DisconnectedMode, Ax25.decodeFrame(frameWithControl(0x0F))!!.content)
        assertEquals(Ax25FrameContent.UnnumberedAcknowledge, Ax25.decodeFrame(frameWithControl(0x63))!!.content)
        assertEquals(Ax25FrameContent.FrameReject, Ax25.decodeFrame(frameWithControl(0x87))!!.content)
    }

    @Test
    fun `decodeFrame recognizes S-frame types with N(R)`() {
        fun sFrame(sType: Int, nr: Int): ByteArray {
            val addresses = Ax25.encodeUiFrame(Ax25Address("A"), Ax25Address("B"), info = ByteArray(0))
            val control = (nr shl 5) or (sType shl 2) or 0x01
            val out = addresses.copyOf()
            out[14] = control.toByte()
            return out.copyOfRange(0, 15)
        }

        assertEquals(Ax25FrameContent.ReceiveReady(3, false), Ax25.decodeFrame(sFrame(0, 3))!!.content)
        assertEquals(Ax25FrameContent.ReceiveNotReady(5, false), Ax25.decodeFrame(sFrame(1, 5))!!.content)
        assertEquals(Ax25FrameContent.Reject(1, false), Ax25.decodeFrame(sFrame(2, 1))!!.content)
    }

    @Test
    fun `decodeFrame returns null for a truncated buffer`() {
        assertNull(Ax25.decodeFrame(ByteArray(5)))
    }

    private val a = Ax25Address("KD3BFP", 9)
    private val b = Ax25Address("N0CALL", 1)

    @Test
    fun `encodeSabm and encodeDisc round-trip as commands with P set`() {
        assertEquals(Ax25FrameContent.SetAsynchronousBalancedMode, Ax25.decodeFrame(Ax25.encodeSabm(a, b))!!.content)
        assertEquals(Ax25FrameContent.Disconnect, Ax25.decodeFrame(Ax25.encodeDisc(a, b))!!.content)
    }

    @Test
    fun `encodeUa and encodeDm round-trip as responses with F set`() {
        assertEquals(Ax25FrameContent.UnnumberedAcknowledge, Ax25.decodeFrame(Ax25.encodeUa(a, b))!!.content)
        assertEquals(Ax25FrameContent.DisconnectedMode, Ax25.decodeFrame(Ax25.encodeDm(a, b))!!.content)
    }

    @Test
    fun `encodeInformation round-trips sequence numbers, poll-final, and info`() {
        val encoded = Ax25.encodeInformation(a, b, ns = 3, nr = 5, pollFinal = true, info = "hi".toByteArray())
        val content = Ax25.decodeFrame(encoded)!!.content as Ax25FrameContent.Information
        assertEquals(3, content.ns)
        assertEquals(5, content.nr)
        assertTrue(content.pollFinal)
        assertEquals("hi", String(content.info))
    }

    @Test
    fun `encodeReceiveReady and encodeReject round-trip N(R) into the correct frame type`() {
        val rr = Ax25.decodeFrame(Ax25.encodeReceiveReady(a, b, nr = 4, pollFinal = false, command = false))!!.content
        assertEquals(Ax25FrameContent.ReceiveReady(4, false), rr)

        val rej = Ax25.decodeFrame(Ax25.encodeReject(a, b, nr = 2, pollFinal = true, command = false))!!.content
        assertEquals(Ax25FrameContent.Reject(2, true), rej)
    }

    /** The destination's C-bit (0x80) is 1 for a command frame, 0 for a response — and the mirror on the source. */
    @Test
    fun `command frames and response frames set the C-bit oppositely on destination vs source`() {
        val command = Ax25.encodeSabm(a, b)
        val destByte = command[6].toInt() and 0xFF
        val sourceByte = command[13].toInt() and 0xFF
        assertTrue("destination C-bit set on a command frame", (destByte and 0x80) != 0)
        assertTrue("source C-bit clear on a command frame", (sourceByte and 0x80) == 0)

        val response = Ax25.encodeUa(a, b)
        val destByteResp = response[6].toInt() and 0xFF
        val sourceByteResp = response[13].toInt() and 0xFF
        assertTrue("destination C-bit clear on a response frame", (destByteResp and 0x80) == 0)
        assertTrue("source C-bit set on a response frame", (sourceByteResp and 0x80) != 0)
    }
}
