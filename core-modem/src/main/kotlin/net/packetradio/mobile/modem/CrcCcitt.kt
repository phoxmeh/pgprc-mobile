package net.packetradio.mobile.modem

/**
 * CRC-CCITT-16 as used by AX.25 / HDLC.
 *
 * Polynomial 0x8408 (bit-reversed 0x1021), initial value 0xFFFF.
 * Input and output are processed LSB-first (matching HDLC bit order).
 * The final value is NOT complemented here — callers that need the
 * on-air FCS must XOR the result with 0xFFFF before transmitting.
 */
internal object CrcCcitt {

    private val TABLE = IntArray(256) { byte ->
        var crc = byte
        repeat(8) {
            crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
        }
        crc
    }

    /** Compute CRC over [data], starting from [initial] (default 0xFFFF). */
    fun compute(data: ByteArray, initial: Int = 0xFFFF): Int {
        var crc = initial
        for (b in data) {
            crc = (crc ushr 8) xor TABLE[(crc xor b.toInt()) and 0xFF]
        }
        return crc
    }

    /**
     * Compute CRC over a bit stream given as a [BooleanArray] (true = 1),
     * processing bits LSB-first in groups of 8.
     */
    fun computeBits(bits: BooleanArray): Int {
        var crc = 0xFFFF
        var byte = 0
        for ((i, bit) in bits.withIndex()) {
            byte = byte or ((if (bit) 1 else 0) shl (i % 8))
            if (i % 8 == 7) {
                crc = (crc ushr 8) xor TABLE[(crc xor byte) and 0xFF]
                byte = 0
            }
        }
        return crc
    }
}
