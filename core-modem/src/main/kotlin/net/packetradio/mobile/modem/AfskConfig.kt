package net.packetradio.mobile.modem

import net.packetradio.mobile.model.ModemMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    /** Pre-computed cosine/sine tables for the correlator (one full symbol window). */
    val markCos: DoubleArray
    val markSin: DoubleArray
    val spaceCos: DoubleArray
    val spaceSin: DoubleArray

    init {
        val n = samplesPerSymbol.toInt()
        markCos  = DoubleArray(n) { i -> cos(2.0 * PI * markHz  * i / sampleRate) }
        markSin  = DoubleArray(n) { i -> sin(2.0 * PI * markHz  * i / sampleRate) }
        spaceCos = DoubleArray(n) { i -> cos(2.0 * PI * spaceHz * i / sampleRate) }
        spaceSin = DoubleArray(n) { i -> sin(2.0 * PI * spaceHz * i / sampleRate) }
    }

    companion object {
        val BELL_202_1200 = AfskConfig(baudRate = 1200, markHz = 1200,  spaceHz = 2200)
        val HF_300        = AfskConfig(baudRate = 300,  markHz = 1600,  spaceHz = 1800)
    }
}

fun ModemMode.toAfskConfig(): AfskConfig = when (this) {
    ModemMode.BELL_202_1200 -> AfskConfig.BELL_202_1200
    ModemMode.HF_300        -> AfskConfig.HF_300
}
