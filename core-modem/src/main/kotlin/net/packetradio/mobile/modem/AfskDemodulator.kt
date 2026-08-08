package net.packetradio.mobile.modem

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Streaming AFSK demodulator using a sliding-window matched-filter (correlator).
 *
 * For each incoming PCM sample:
 *  1. Maintain circular buffers of length [windowSize] (= samplesPerSymbol, rounded).
 *  2. Compute mark energy and space energy as the squared magnitude of the DFT at
 *     the mark/space frequencies over the window (= matched filter output).
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
    private val buf = DoubleArray(windowSize)
    private var bufHead = 0   // circular buffer write index

    // Running correlator sums (updated incrementally to avoid O(N²))
    private var markI = 0.0; private var markQ = 0.0
    private var spaceI = 0.0; private var spaceQ = 0.0

    // Symbol clock state
    private var samplesSinceEdge = 0
    private var halfSymbol = windowSize / 2
    private var lastDecision = false

    fun feed(sample: Short) {
        val s = sample.toDouble() / Short.MAX_VALUE

        // Remove oldest sample from running sums
        val oldest = buf[bufHead]
        val oldIdx = bufHead   // position in the pre-computed sinusoid table
        markI  -= oldest * config.markCos[oldIdx]
        markQ  -= oldest * config.markSin[oldIdx]
        spaceI -= oldest * config.spaceCos[oldIdx]
        spaceQ -= oldest * config.spaceSin[oldIdx]

        // Insert new sample
        buf[bufHead] = s
        val newIdx = bufHead
        markI  += s * config.markCos[newIdx]
        markQ  += s * config.markSin[newIdx]
        spaceI += s * config.spaceCos[newIdx]
        spaceQ += s * config.spaceSin[newIdx]

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

    // Level estimate for CSMA carrier detect (RMS of the circular buffer)
    fun rmsLevel(): Double {
        var sum = 0.0
        for (s in buf) sum += s * s
        return sqrt(sum / windowSize)
    }

    fun reset() {
        buf.fill(0.0)
        bufHead = 0
        markI = 0.0; markQ = 0.0; spaceI = 0.0; spaceQ = 0.0
        samplesSinceEdge = 0; lastDecision = false
    }
}
