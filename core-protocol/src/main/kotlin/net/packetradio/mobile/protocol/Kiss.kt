package net.packetradio.mobile.protocol

/**
 * KISS framing: byte-stuffing escape codec + a streaming decoder. Direct
 * port of `pr-ax25::kiss`.
 */
object Kiss {
    const val FEND = 0xC0
    const val FESC = 0xDB
    const val TFEND = 0xDC
    const val TFESC = 0xDD

    const val CMD_TX_DELAY = 1
    const val CMD_PERSISTENCE = 2
    const val CMD_SLOT_TIME = 3
    const val CMD_TX_TAIL = 4
    const val CMD_FULL_DUPLEX = 5

    /** Command byte = port in the high nibble, frame type in the low nibble. */
    fun encodeFrame(kissPort: Int, frameType: Int, payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(payload.size + 4)
        out.add(FEND.toByte())
        out.add((((kissPort and 0x0F) shl 4) or (frameType and 0x0F)).toByte())
        for (b in payload) {
            val v = b.toInt() and 0xFF
            when (v) {
                FEND -> {
                    out.add(FESC.toByte())
                    out.add(TFEND.toByte())
                }
                FESC -> {
                    out.add(FESC.toByte())
                    out.add(TFESC.toByte())
                }
                else -> out.add(b)
            }
        }
        out.add(FEND.toByte())
        return out.toByteArray()
    }

    fun encodeDataFrame(kissPort: Int, payload: ByteArray): ByteArray = encodeFrame(kissPort, 0, payload)

    fun encodeParamFrame(kissPort: Int, kind: Int, value: Int): ByteArray =
        encodeFrame(kissPort, kind, byteArrayOf(value.toByte()))
}

/**
 * Streaming KISS decoder. [feed] processes bytes one at a time and returns
 * every complete `(command, payload)` frame found — a FEND both starts and
 * ends a frame, so a lone FEND with nothing buffered yet is not a frame.
 */
class KissDecoder {
    private var buffer = ArrayList<Byte>()
    private var inFrame = false
    private var escaped = false

    fun feed(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                v == Kiss.FEND -> {
                    if (inFrame && buffer.isNotEmpty()) {
                        val cmd = buffer[0].toInt() and 0xFF
                        val payload = buffer.drop(1).toByteArray()
                        frames += cmd to payload
                    }
                    buffer = ArrayList()
                    inFrame = true
                    escaped = false
                }
                v == Kiss.FESC && inFrame -> escaped = true
                v == Kiss.TFEND && inFrame && escaped -> {
                    buffer.add(Kiss.FEND.toByte())
                    escaped = false
                }
                v == Kiss.TFESC && inFrame && escaped -> {
                    buffer.add(Kiss.FESC.toByte())
                    escaped = false
                }
                inFrame -> {
                    buffer.add(b)
                    escaped = false
                }
                // byte arrives before the first FEND — dropped.
            }
        }
        return frames
    }
}
