package net.packetradio.mobile.modem

import kotlin.math.PI
import kotlin.math.sin

/**
 * AFSK modulator: converts a NRZI bit stream to 16-bit PCM samples.
 *
 * Each bit generates [AfskConfig.samplesPerSymbol] samples of a sine wave at
 * either the mark or space frequency.  A fractional accumulator handles non-integer
 * samples-per-symbol so output length stays accurate over long transmissions.
 *
 * Phase is maintained continuously across symbol boundaries to avoid spectral splatter.
 */
internal class AfskModulator(private val config: AfskConfig) {

    private var phase = 0.0   // current carrier phase in radians

    /** Modulate one frame's worth of NRZI bits into PCM samples (Short, range ±32767). */
    fun modulate(nrziBits: BooleanArray): ShortArray {
        // Worst-case: ceil(bits * samplesPerSymbol) + 1 extra
        val maxSamples = (nrziBits.size * config.samplesPerSymbol).toInt() + 2
        val buf = ShortArray(maxSamples)
        var writePos = 0
        var fractionalCarry = 0.0

        for (bit in nrziBits) {
            val freq = if (bit) config.markHz.toDouble() else config.spaceHz.toDouble()
            val phaseIncrement = 2.0 * PI * freq / config.sampleRate

            // Generate samples for this symbol, honouring fractional accumulator
            val symbolSamples = config.samplesPerSymbol + fractionalCarry
            val n = symbolSamples.toInt()
            fractionalCarry = symbolSamples - n

            repeat(n) {
                buf[writePos++] = (sin(phase) * Short.MAX_VALUE * AMPLITUDE).toInt().toShort()
                phase += phaseIncrement
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
        }

        return buf.copyOf(writePos)
    }

    fun reset() { phase = 0.0 }

    companion object {
        private const val AMPLITUDE = 0.85  // leave headroom to avoid clipping
    }
}
