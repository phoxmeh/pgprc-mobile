package net.packetradio.mobile.ui.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {

    @Test
    fun dbfsOfFullScaleIsZeroAndHalfIsAboutMinusSix() {
        assertEquals(0f, AudioLevel.dbfs(1f), 0.001f)
        assertEquals(-6.02f, AudioLevel.dbfs(0.5f), 0.01f)
        assertEquals(-120f, AudioLevel.dbfs(0f), 0f)
    }

    @Test
    fun barFractionSpansFloorToFullScale() {
        assertEquals(0f, AudioLevel.barFraction(0f), 0f)
        assertEquals(1f, AudioLevel.barFraction(1f), 0.001f)
        // -30 dBFS is halfway up a -60..0 meter.
        assertEquals(0.5f, AudioLevel.barFraction(0.0316228f), 0.001f)
    }

    @Test
    fun statusFollowsTheTargetWindow() {
        assertEquals(AudioLevel.Status.NO_SIGNAL, AudioLevel.status(0.001f))
        assertEquals(AudioLevel.Status.LOW, AudioLevel.status(0.05f))      // about -26 dBFS
        assertEquals(AudioLevel.Status.GOOD, AudioLevel.status(0.2f))      // about -14 dBFS
        assertEquals(AudioLevel.Status.HIGH, AudioLevel.status(0.7f))      // about -3 dBFS
        assertEquals(AudioLevel.Status.CLIPPING, AudioLevel.status(0.99f))
    }

    @Test
    fun measureReportsPeakAndRms() {
        val buf = shortArrayOf(16384, -16384, 16384, -16384)
        val r = AudioLevel.measure(buf, buf.size, 1f)
        assertEquals(0.5f, r.peak, 0.001f)
        assertEquals(0.5f, r.rms, 0.001f)
    }

    @Test
    fun measureAppliesGainAndClampsLikeTheModem() {
        val buf = shortArrayOf(20000, -20000)
        val r = AudioLevel.measure(buf, buf.size, 4f)
        // 80000 would overflow 16 bits; the modem clamps, so the meter must show full scale.
        assertTrue(r.peak >= 0.999f)
        assertEquals(AudioLevel.Status.CLIPPING, AudioLevel.status(r.peak))
    }

    @Test
    fun measureOfNothingIsSilent() {
        val r = AudioLevel.measure(ShortArray(8), 0, 1f)
        assertEquals(0f, r.peak, 0f)
        assertEquals(0f, r.rms, 0f)
    }
}
