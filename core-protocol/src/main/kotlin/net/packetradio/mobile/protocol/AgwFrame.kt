package net.packetradio.mobile.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AGWPE frame: a 36-byte header followed by [data]. Direct port of
 * `pr-agwpe::codec::AgwFrame`/`encode`/`decode`. All multi-byte header
 * fields are little-endian.
 *
 * Header layout (36 bytes):
 * ```
 * +00      1  Port
 * +01..03  3  reserved (zero)
 * +04      1  DataKind (ASCII command letter)
 * +05      1  reserved (zero)
 * +06      1  PID
 * +07      1  reserved (zero)
 * +08..17 10  CallFrom, NUL-padded
 * +18..27 10  CallTo, NUL-padded
 * +28..31  4  DataLen, u32 LE
 * +32..35  4  reserved (zero)
 * +36..   DataLen  Data
 * ```
 */
class AgwFrame(
    val port: Int,
    val dataKind: Char,
    val pid: Int = 0xF0,
    val callFrom: String,
    val callTo: String,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        val header = ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        header.put(port.toByte())
        header.put(ByteArray(3)) // reserved
        header.put(dataKind.code.toByte())
        header.put(0) // reserved
        header.put(pid.toByte())
        header.put(0) // reserved
        header.put(writePadded(callFrom, CALL_FIELD_LEN))
        header.put(writePadded(callTo, CALL_FIELD_LEN))
        header.putInt(data.size)
        header.put(ByteArray(4)) // reserved
        return header.array() + data
    }

    override fun equals(other: Any?): Boolean =
        other is AgwFrame && port == other.port && dataKind == other.dataKind && pid == other.pid &&
            callFrom == other.callFrom && callTo == other.callTo && data.contentEquals(other.data)

    override fun hashCode(): Int =
        listOf(port, dataKind, pid, callFrom, callTo).hashCode() * 31 + data.contentHashCode()

    override fun toString(): String =
        "AgwFrame(port=$port, dataKind=$dataKind, pid=$pid, callFrom=$callFrom, callTo=$callTo, data=${data.size}B)"

    companion object {
        const val HEADER_LEN = 36
        const val CALL_FIELD_LEN = 10

        fun create(port: Int, dataKind: Char, callFrom: String, callTo: String, data: ByteArray): AgwFrame =
            AgwFrame(port, dataKind, pid = 0xF0, callFrom = callFrom, callTo = callTo, data = data)

        /** Kind `'P'`, port 0, empty CallFrom/CallTo; data = two 255-byte NUL-padded fields. */
        fun login(username: String, password: String): AgwFrame {
            val out = ByteArray(510)
            writePadded(username, 255).copyInto(out, 0)
            writePadded(password, 255).copyInto(out, 255)
            return create(port = 0, dataKind = 'P', callFrom = "", callTo = "", data = out)
        }

        /** Kind `'v'`; data = the digipeater path only. */
        fun connectVia(port: Int, callFrom: String, callTo: String, digis: List<String>): AgwFrame =
            create(port, 'v', callFrom, callTo, encodeDigiPath(digis))

        /** Kind `'V'`; data = the digipeater path followed immediately by [info] (no separator). */
        fun unprotoVia(port: Int, callFrom: String, callTo: String, digis: List<String>, info: ByteArray): AgwFrame =
            create(port, 'V', callFrom, callTo, encodeDigiPath(digis) + info)

        /** 1 count byte, then each digi callsign in its own 10-byte NUL-padded field. */
        fun encodeDigiPath(digis: List<String>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(digis.size and 0xFF)
            for (digi in digis) out.write(writePadded(digi, CALL_FIELD_LEN))
            return out.toByteArray()
        }

        /**
         * Truncates [value] to `fieldLen - 1` bytes so at least one trailing
         * NUL always remains, then NUL-pads to [fieldLen].
         */
        fun writePadded(value: String, fieldLen: Int): ByteArray {
            val field = ByteArray(fieldLen)
            val bytes = value.toByteArray(Charsets.US_ASCII)
            val n = minOf(bytes.size, fieldLen - 1)
            bytes.copyInto(field, 0, 0, n)
            return field
        }

        /** Scans for the first NUL byte; the text before it is the value (or the whole field if none). */
        fun readPadded(field: ByteArray): String {
            val nulIndex = field.indexOf(0)
            val end = if (nulIndex >= 0) nulIndex else field.size
            return String(field, 0, end, Charsets.US_ASCII)
        }

        /**
         * AGWPE frequently NUL-pads/terminates text fields inside the data
         * payload. Strips *every* embedded NUL (not just a trailing one —
         * NULs can be interior), then trims whitespace. This is the
         * behavior any Compose text state consuming this data needs, the
         * same reason GTK's string marshaling needed it on the desktop.
         */
        fun textFromBytes(bytes: ByteArray): String =
            String(bytes, Charsets.UTF_8).replace("\u0000", "").trim()

        /** Returns `(frame, bytesConsumed)`, or `null` if [buf] doesn't yet hold a full frame. */
        fun decode(buf: ByteArray): Pair<AgwFrame, Int>? {
            if (buf.size < HEADER_LEN) return null
            val header = ByteBuffer.wrap(buf, 0, HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            val port = header.get(0).toInt() and 0xFF
            val dataKind = (header.get(4).toInt() and 0xFF).toChar()
            val pid = header.get(6).toInt() and 0xFF
            val callFrom = readPadded(buf.copyOfRange(8, 18))
            val callTo = readPadded(buf.copyOfRange(18, 28))
            val dataLen = header.getInt(28)
            val total = HEADER_LEN + dataLen
            if (buf.size < total) return null
            val data = buf.copyOfRange(HEADER_LEN, total)
            return AgwFrame(port, dataKind, pid, callFrom, callTo, data) to total
        }
    }
}

private fun ByteArray.indexOf(byte: Byte): Int {
    for (i in indices) if (this[i] == byte) return i
    return -1
}

/**
 * Stateful incremental parser: [feed] appends bytes, [nextFrame] pulls one
 * complete frame if the buffer holds one, draining consumed bytes.
 */
class FrameDecoder {
    private var buffer: ByteArray = ByteArray(0)

    fun feed(bytes: ByteArray) {
        buffer += bytes
    }

    fun nextFrame(): AgwFrame? {
        val result = AgwFrame.decode(buffer) ?: return null
        val (frame, consumed) = result
        buffer = buffer.copyOfRange(consumed, buffer.size)
        return frame
    }
}
