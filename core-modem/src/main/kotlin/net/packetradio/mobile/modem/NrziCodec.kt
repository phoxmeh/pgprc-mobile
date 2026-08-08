package net.packetradio.mobile.modem

/**
 * NRZI (Non-Return-to-Zero Inverted) encoding used by Bell 202 AFSK.
 *
 * Convention: a 0 data bit causes a transition; a 1 data bit causes no transition.
 * The output bit represents the current signal level (mark or space).
 */
internal object NrziCodec {

    /** Encode a stream of data bits to NRZI signal levels. [lastLevel] is the
     *  signal level before the first bit (true = mark). */
    fun encode(bits: BooleanArray, lastLevel: Boolean = true): BooleanArray {
        val out = BooleanArray(bits.size)
        var level = lastLevel
        for (i in bits.indices) {
            if (!bits[i]) level = !level   // data 0 → transition
            out[i] = level
        }
        return out
    }

    /** Decode NRZI signal levels back to data bits. */
    fun decode(levels: BooleanArray, lastLevel: Boolean = true): BooleanArray {
        val out = BooleanArray(levels.size)
        var prev = lastLevel
        for (i in levels.indices) {
            out[i] = levels[i] == prev   // no transition → 1, transition → 0
            prev = levels[i]
        }
        return out
    }
}
