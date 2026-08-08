package net.packetradio.mobile.modem

/**
 * HDLC framing layer used by AX.25 over the air.
 *
 * Responsibilities: flag detection (0x7E = 01111110), bit stuffing/destuffing,
 * and FCS (CRC-CCITT-16) append/verify.  Operates on raw data bits (pre-NRZI).
 *
 * Frame wire format:
 *   [FLAG…] [AX.25 data bits, bit-stuffed] [FCS lo byte] [FCS hi byte] [FLAG]
 *
 * The AX.25 payload passed into [encodeFrame] and returned from [Decoder.feed]
 * is raw frame bytes WITHOUT the FCS — FCS is appended/checked internally.
 */
internal object HdlcCodec {

    // 01111110
    private val FLAG_BITS = booleanArrayOf(false, true, true, true, true, true, true, false)

    private const val TAIL_FLAGS = 3

    /**
     * Encode one AX.25 frame (without FCS) into a NRZI-ready bit stream.
     * Output includes preamble flags, stuffed payload + FCS, and tail flags.
     *
     * [preambleFlags] controls how many 0x7E sync bytes precede the frame.
     * Receivers need at least 3–4 to achieve bit-clock sync; 20 is comfortable
     * for most hardware TNCs and software decoders at 1200 baud (~133ms).
     */
    fun encodeFrame(frame: ByteArray, preambleFlags: Int = 20): BooleanArray {
        // AX.25 FCS = CRC-CCITT with initial 0xFFFF, output complemented (XOR 0xFFFF).
        val fcs = CrcCcitt.compute(frame) xor 0xFFFF
        val fcsBits = byteArrayOf((fcs and 0xFF).toByte(), (fcs ushr 8).toByte())

        // Build data + FCS bits (LSB first per HDLC)
        val rawBits = mutableListOf<Boolean>()
        for (b in frame + fcsBits) {
            for (bit in 0..7) rawBits.add((b.toInt() ushr bit) and 1 != 0)
        }

        // Bit stuffing: insert a 0 after every run of 5 consecutive 1s
        val stuffed = mutableListOf<Boolean>()
        var ones = 0
        for (bit in rawBits) {
            stuffed.add(bit)
            if (bit) {
                ones++
                if (ones == 5) { stuffed.add(false); ones = 0 }
            } else {
                ones = 0
            }
        }

        val out = mutableListOf<Boolean>()
        repeat(preambleFlags) { out.addAll(FLAG_BITS.toList()) }
        out.addAll(stuffed)
        repeat(TAIL_FLAGS) { out.addAll(FLAG_BITS.toList()) }
        return out.toBooleanArray()
    }

    /**
     * Stateful frame decoder. Feed decoded (post-NRZI) bits one at a time via
     * [feed]; complete valid frames are returned through [onFrame].
     */
    class Decoder(private val onFrame: (ByteArray) -> Unit) {

        private val window = ArrayDeque<Boolean>(8)  // last 8 bits for flag/abort detection
        private val frameBits = mutableListOf<Boolean>()
        private var inFrame = false
        private var stuffCount = 0   // consecutive 1s seen so far (for destuffing)

        fun feed(bit: Boolean) {
            // Maintain sliding 8-bit window for flag/abort detection
            window.addLast(bit)
            if (window.size > 8) window.removeFirst()

            // Check for FLAG pattern (01111110)
            if (window.size == 8 && isFlagOrAbort(window)) {
                val isFlag = !window[7]  // last bit is 0 → FLAG; all-ones → abort
                if (isFlag) {
                    if (inFrame && frameBits.size >= 16) finishFrame()
                    frameBits.clear()
                    inFrame = true
                    stuffCount = 0
                } else {
                    // Abort (01111111 or similar) — discard current frame
                    inFrame = false
                    frameBits.clear()
                }
                return
            }

            if (!inFrame) return

            // Bit destuffing: a 0 following 5 ones is a stuffed bit — discard it
            if (bit) {
                stuffCount++
                if (stuffCount > 5) { inFrame = false; frameBits.clear(); return } // framing error
                frameBits.add(true)
            } else {
                if (stuffCount == 5) {
                    // Stuffed zero — drop it, reset count
                    stuffCount = 0
                } else {
                    stuffCount = 0
                    frameBits.add(false)
                }
            }
        }

        private fun isFlagOrAbort(bits: ArrayDeque<Boolean>): Boolean {
            // FLAG: 01111110, Abort: 01111111 — both have exactly 6 ones in positions 1..6.
            if (bits[0]) return false  // must start with 0
            for (i in 1..6) if (!bits[i]) return false  // must have all 6 ones
            return true
        }

        private fun finishFrame() {
            if (frameBits.size % 8 != 0) return  // not byte-aligned

            val bytes = ByteArray(frameBits.size / 8)
            for (i in bytes.indices) {
                var b = 0
                for (bit in 0..7) if (frameBits[i * 8 + bit]) b = b or (1 shl bit)
                bytes[i] = b.toByte()
            }

            if (bytes.size < 2) return

            // Last two bytes are FCS (lo, hi)
            val payload = bytes.copyOf(bytes.size - 2)
            val receivedFcs = (bytes[bytes.size - 2].toInt() and 0xFF) or
                ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
            val computedFcs = CrcCcitt.compute(payload) xor 0xFFFF

            if (receivedFcs == computedFcs) onFrame(payload)
        }

        fun reset() {
            window.clear()
            frameBits.clear()
            inFrame = false
            stuffCount = 0
        }
    }
}
