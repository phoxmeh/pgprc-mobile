package net.packetradio.mobile.ui.ports

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Level math and guidance for the audio input meter. Levels are linear fractions of full scale
 * (0..1); the meter works in dBFS, the usual unit for judging whether a digital capture is too
 * quiet or about to clip.
 */
internal object AudioLevel {
    /** Bottom of the meter: anything quieter reads as empty. */
    const val FLOOR_DB = -60f

    /** Peaks between these two levels are a healthy capture for a typical signal. */
    const val TARGET_LOW_DB = -20f
    const val TARGET_HIGH_DB = -6f

    /** Above this peak (fraction of full scale) the capture is effectively clipping. */
    const val CLIP_PEAK = 0.97f

    /** Below this peak (~ -50 dBFS) there is no usable signal at all. */
    const val NO_SIGNAL_PEAK = 0.003f

    enum class Status { NO_SIGNAL, LOW, GOOD, HIGH, CLIPPING }

    class Reading(val peak: Float, val rms: Float)

    fun dbfs(linear: Float): Float = if (linear <= 1e-6f) -120f else 20f * log10(linear)

    /** Where [peak] sits on the meter, 0 (empty) to 1 (full scale). */
    fun barFraction(peak: Float): Float = ((dbfs(peak) - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

    fun status(peak: Float): Status {
        val db = dbfs(peak)
        return when {
            peak > CLIP_PEAK -> Status.CLIPPING
            peak < NO_SIGNAL_PEAK -> Status.NO_SIGNAL
            db < TARGET_LOW_DB -> Status.LOW
            db <= TARGET_HIGH_DB -> Status.GOOD
            else -> Status.HIGH
        }
    }

    /**
     * Peak and RMS of the first [n] samples of [buf] after [gain], clamped to 16-bit range the same
     * way the modem clamps before demodulating — so the meter shows what the demodulator actually
     * receives, including clipping when the gain is pushed too far.
     */
    fun measure(buf: ShortArray, n: Int, gain: Float): Reading {
        if (n <= 0) return Reading(0f, 0f)
        var peak = 0f
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = (buf[i] * gain).coerceIn(-32768f, 32767f) / 32768f
            val a = abs(s)
            if (a > peak) peak = a
            sumSq += s.toDouble() * s
        }
        return Reading(peak, sqrt(sumSq / n).toFloat())
    }
}
