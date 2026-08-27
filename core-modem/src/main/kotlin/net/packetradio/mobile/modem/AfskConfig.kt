package net.packetradio.mobile.modem

import net.packetradio.mobile.model.ModemMode
import kotlin.math.PI

/**
 * Physical-layer parameters for one AFSK variant.
 *
 * @param baudRate   Symbol rate in baud.
 * @param markHz     Frequency of the mark (binary 1) tone, Hz.
 * @param spaceHz    Frequency of the space (binary 0) tone, Hz.
 * @param sampleRate PCM sample rate; must match the USB audio device (48 000 Hz typical).
 */
data class AfskConfig(
    val baudRate: Int,
    val markHz: Int,
    val spaceHz: Int,
    val sampleRate: Int = 48_000,
) {
    /** Samples per symbol (may not be an integer — kept as Double for precision). */
    val samplesPerSymbol: Double = sampleRate.toDouble() / baudRate

    /**
     * Per-sample phase increment (radians) for each reference oscillator, for a
     * continuously-running NCO — see [AfskDemodulator]. A table indexed by position
     * within a symbol window (the previous approach) is only phase-consistent across
     * window wraparound when the tone completes a whole number of cycles per window,
     * which isn't true for either tone pair this app uses (e.g. 1600 Hz over a
     * 160-sample window at 48 kHz is 5.33 cycles, not a whole number) — matches
     * Direwolf's own demod_afsk.c technique (`osc_phase += osc_delta` every sample,
     * never reset to align with a window).
     */
    val markPhaseIncrement: Double = 2.0 * PI * markHz / sampleRate
    val spacePhaseIncrement: Double = 2.0 * PI * spaceHz / sampleRate

    companion object {
        val BELL_202_1200 = AfskConfig(baudRate = 1200, markHz = 1200,  spaceHz = 2200)
        val HF_300        = AfskConfig(baudRate = 300,  markHz = 1600,  spaceHz = 1800)
    }
}

fun ModemMode.toAfskConfig(): AfskConfig = when (this) {
    ModemMode.BELL_202_1200 -> AfskConfig.BELL_202_1200
    ModemMode.HF_300        -> AfskConfig.HF_300
}
