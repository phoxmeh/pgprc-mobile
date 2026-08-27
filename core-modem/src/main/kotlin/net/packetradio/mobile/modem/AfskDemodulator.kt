package net.packetradio.mobile.modem

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming AFSK demodulator using a sliding-window matched-filter (correlator).
 *
 * For each incoming PCM sample:
 *  1. Multiply it against continuously-running mark/space reference oscillators
 *     (NCOs), matching [AfskModulator]'s own continuous-phase approach and
 *     Direwolf's demod_afsk.c (`osc_phase += osc_delta` every sample, never reset
 *     to align with a window) — a table indexed by position within a symbol window
 *     is only phase-consistent across window wraparound when the tone completes a
 *     whole number of cycles per window, which isn't true for either tone pair this
 *     app uses (e.g. 1600 Hz over a 160-sample window at 48 kHz is 5.33 cycles).
 *  2. Maintain a sliding sum of those products over the last [windowSize] samples
 *     (= matched filter output) by buffering each sample's products and
 *     subtracting them back out [windowSize] samples later.
 *  3. Decision: markEnergy > spaceEnergy → 1, else → 0.
 *  4. Edge-track the decision signal to recover the symbol clock, then sample at
 *     the midpoint of each inferred symbol period.
 *
 * Decoded data bits (post-NRZI) are emitted via [onBit].
 */
internal class AfskDemodulator(
    private val config: AfskConfig,
    private val onBit: (Boolean) -> Unit,
) {
    private val windowSize = config.samplesPerSymbol.toInt()

    // Circular buffers of each sample's mark/space reference-oscillator products,
    // as they stood at insertion time — needed to subtract the exact contribution
    // of the sample leaving the window, since the reference phase never repeats
    // on a windowSize-aligned cycle.
    private val markIBuf = DoubleArray(windowSize)
    private val markQBuf = DoubleArray(windowSize)
    private val spaceIBuf = DoubleArray(windowSize)
    private val spaceQBuf = DoubleArray(windowSize)
    private val magBuf = DoubleArray(windowSize)  // |sample|, for RMS level
    private var bufHead = 0

    // Running correlator sums (updated incrementally to avoid O(N²))
    private var markI = 0.0; private var markQ = 0.0
    private var spaceI = 0.0; private var spaceQ = 0.0
    private var magSum = 0.0

    // Continuously-running reference oscillator phases (radians)
    private var markPhase = 0.0
    private var spacePhase = 0.0

    // Symbol clock state
    private var samplesSinceEdge = 0
    private var halfSymbol = windowSize / 2
    private var lastDecision = false

    fun feed(sample: Short) {
        val s = sample.toDouble() / Short.MAX_VALUE

        val markCos = cos(markPhase); val markSin = sin(markPhase)
        val spaceCos = cos(spacePhase); val spaceSin = sin(spacePhase)
        markPhase = (markPhase + config.markPhaseIncrement) % (2.0 * PI)
        spacePhase = (spacePhase + config.spacePhaseIncrement) % (2.0 * PI)

        // Remove the sample leaving the window, using the exact products it was
        // inserted with (not a fresh lookup — its reference phase never recurs).
        markI  -= markIBuf[bufHead];  markQ  -= markQBuf[bufHead]
        spaceI -= spaceIBuf[bufHead]; spaceQ -= spaceQBuf[bufHead]
        magSum -= magBuf[bufHead]

        val newMarkI = s * markCos; val newMarkQ = s * markSin
        val newSpaceI = s * spaceCos; val newSpaceQ = s * spaceSin
        markIBuf[bufHead] = newMarkI; markQBuf[bufHead] = newMarkQ
        spaceIBuf[bufHead] = newSpaceI; spaceQBuf[bufHead] = newSpaceQ
        magBuf[bufHead] = s * s

        markI += newMarkI; markQ += newMarkQ
        spaceI += newSpaceI; spaceQ += newSpaceQ
        magSum += s * s

        bufHead = (bufHead + 1) % windowSize

        val markEnergy  = markI  * markI  + markQ  * markQ
        val spaceEnergy = spaceI * spaceI + spaceQ * spaceQ
        val decision = markEnergy > spaceEnergy

        // Edge detection: when the decision flips, re-sync the symbol clock
        if (decision != lastDecision) {
            // An edge should ideally land at a symbol boundary.
            // Nudge the clock: next sample point is half a symbol from here.
            samplesSinceEdge = 0
        }

        samplesSinceEdge++

        // Sample at the midpoint of the inferred symbol, then free-run at windowSize period
        if (samplesSinceEdge == halfSymbol) {
            onBit(decision)
        }
        if (samplesSinceEdge >= windowSize) {
            samplesSinceEdge = 0
        }

        lastDecision = decision
    }

    // Level estimate for CSMA carrier detect (RMS over the current window)
    fun rmsLevel(): Double = sqrt(magSum / windowSize)

    fun reset() {
        markIBuf.fill(0.0); markQBuf.fill(0.0)
        spaceIBuf.fill(0.0); spaceQBuf.fill(0.0)
        magBuf.fill(0.0)
        bufHead = 0
        markI = 0.0; markQ = 0.0; spaceI = 0.0; spaceQ = 0.0; magSum = 0.0
        markPhase = 0.0; spacePhase = 0.0
        samplesSinceEdge = 0; lastDecision = false
    }
}
