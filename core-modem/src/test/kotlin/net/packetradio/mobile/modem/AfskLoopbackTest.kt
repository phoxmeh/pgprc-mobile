package net.packetradio.mobile.modem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end loopback: encode a frame through the same modulate/demodulate path
 * [UsbAudioRunner] uses, with no audio hardware involved, to verify the
 * demodulator's mark/space correlator recovers bits correctly regardless of
 * whether a tone completes a whole number of cycles per symbol window.
 */
class AfskLoopbackTest {

    private fun loopback(config: AfskConfig, frame: ByteArray): ByteArray? {
        val hdlcBits = HdlcCodec.encodeFrame(frame, preambleFlags = 20)
        val nrziBits = NrziCodec.encode(hdlcBits)
        val samples = AfskModulator(config).modulate(nrziBits)

        var decoded: ByteArray? = null
        val hdlcDecoder = HdlcCodec.Decoder { decoded = it }
        var nrziLastLevel = true
        val demodulator = AfskDemodulator(config) { dataBit ->
            val nrziBit = dataBit == nrziLastLevel
            nrziLastLevel = dataBit
            hdlcDecoder.feed(nrziBit)
        }
        for (s in samples) demodulator.feed(s)
        return decoded
    }

    @Test
    fun `HF 300 baud loopback recovers the original frame`() {
        val frame = "CQ CQ CQ DE N0CALL".toByteArray()
        val decoded = loopback(AfskConfig.HF_300, frame)
        assertArrayEquals(frame, decoded)
    }

    @Test
    fun `Bell 202 1200 baud loopback recovers the original frame`() {
        val frame = "CQ CQ CQ DE N0CALL".toByteArray()
        val decoded = loopback(AfskConfig.BELL_202_1200, frame)
        assertArrayEquals(frame, decoded)
    }

    @Test
    fun `HF 300 baud loopback works for a short frame`() {
        val frame = byteArrayOf(1, 2, 3)
        val decoded = loopback(AfskConfig.HF_300, frame)
        assertArrayEquals(frame, decoded)
    }

    @Test
    fun `HF 300 baud loopback works for a longer frame`() {
        val frame = ByteArray(200) { (it % 256).toByte() }
        val decoded = loopback(AfskConfig.HF_300, frame)
        assertArrayEquals(frame, decoded)
    }

    @Test
    fun `demodulator RMS level responds to signal presence`() {
        val config = AfskConfig.HF_300
        val demodulator = AfskDemodulator(config) {}
        // Feed silence first — RMS should be near zero.
        repeat(config.samplesPerSymbol.toInt() * 2) { demodulator.feed(0) }
        val silentRms = demodulator.rmsLevel()

        // Now feed an actual mark tone at full amplitude.
        val frame = byteArrayOf(0x7E)
        val hdlcBits = HdlcCodec.encodeFrame(frame, preambleFlags = 20)
        val nrziBits = NrziCodec.encode(hdlcBits)
        val samples = AfskModulator(config).modulate(nrziBits)
        for (s in samples) demodulator.feed(s)
        val activeRms = demodulator.rmsLevel()

        assertTrue("silent=$silentRms active=$activeRms", activeRms > silentRms + 0.1)
    }
}
