package net.packetradio.mobile.protocol

/**
 * A minimal, hand-rolled AX.25 frame codec — no Kotlin/Java AX.25 library is
 * assumed to exist. Scope is deliberately narrow, matching what this app
 * actually needs: encode outgoing UI (unproto/beacon) frames, and decode
 * enough incoming frame types to reproduce the desktop's `describe_frame`
 * monitor-line labels (I/UI/RR/RNR/REJ/SABM/DISC/DM/UA/FRMR/unknown).
 * Nothing here implements the connected-mode ARQ state machine itself —
 * that's always owned by the AGWPE host or, for bare KISS, not implemented
 * at all (same scope boundary as the desktop app).
 */
data class Ax25Address(val callsign: String, val ssid: Int = 0) {
    fun label(): String = if (ssid != 0) "$callsign-$ssid" else callsign

    companion object {
        /** Splits "CALL-SSID" on the last `-`; a missing/non-numeric SSID defaults to 0. */
        fun parse(text: String): Ax25Address {
            val trimmed = text.trim().uppercase()
            val dash = trimmed.lastIndexOf('-')
            if (dash <= 0) return Ax25Address(trimmed, 0)
            val ssid = trimmed.substring(dash + 1).toIntOrNull()
            return if (ssid != null) Ax25Address(trimmed.substring(0, dash), ssid) else Ax25Address(trimmed, 0)
        }
    }
}

sealed interface Ax25FrameContent {
    class Information(val ns: Int, val nr: Int, val pollFinal: Boolean, val pid: Int, val info: ByteArray) : Ax25FrameContent {
        override fun equals(other: Any?) = other is Information && ns == other.ns && nr == other.nr &&
            pollFinal == other.pollFinal && pid == other.pid && info.contentEquals(other.info)
        override fun hashCode() = listOf(ns, nr, pollFinal, pid).hashCode() * 31 + info.contentHashCode()
    }

    class UnnumberedInformation(val pid: Int, val info: ByteArray) : Ax25FrameContent {
        override fun equals(other: Any?) = other is UnnumberedInformation && pid == other.pid && info.contentEquals(other.info)
        override fun hashCode() = pid * 31 + info.contentHashCode()
    }

    data class ReceiveReady(val nr: Int, val pollFinal: Boolean) : Ax25FrameContent
    data class ReceiveNotReady(val nr: Int, val pollFinal: Boolean) : Ax25FrameContent
    data class Reject(val nr: Int, val pollFinal: Boolean) : Ax25FrameContent
    data object SetAsynchronousBalancedMode : Ax25FrameContent
    data object Disconnect : Ax25FrameContent
    data object DisconnectedMode : Ax25FrameContent
    data object UnnumberedAcknowledge : Ax25FrameContent
    data object FrameReject : Ax25FrameContent
    data class Unknown(val control: Int) : Ax25FrameContent
}

data class Ax25DecodedFrame(
    val destination: Ax25Address,
    val source: Ax25Address,
    val digipeaters: List<Ax25Address>,
    val content: Ax25FrameContent,
)

object Ax25 {
    private const val ADDRESS_LEN = 7
    private const val CONTROL_UI = 0x03
    private const val CONTROL_SABM = 0x2F
    private const val CONTROL_DISC = 0x43
    private const val CONTROL_DM = 0x0F
    private const val CONTROL_UA = 0x63
    private const val CONTROL_FRMR = 0x87
    private const val PF_BIT = 0x10

    // S-frame type-code (control bits 2-3): 0=RR, 1=RNR, 2=REJ, 3=SREJ (AX.25 2.2 only, unused here).
    private const val SS_RR = 0
    private const val SS_REJ = 2

    /**
     * Encodes a UI (unproto) frame: destination, source, then up to 8
     * digipeaters, control byte 0x03, [pid], then [info]. UI is always a
     * command frame per spec (see [encodeAddressChain]).
     */
    fun encodeUiFrame(
        source: Ax25Address,
        destination: Ax25Address,
        digipeaters: List<Ax25Address> = emptyList(),
        pid: Int = 0xF0,
        info: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(encodeAddressChain(destination, source, digipeaters, command = true))
        out.write(CONTROL_UI)
        out.write(pid and 0xFF)
        out.write(info)
        return out.toByteArray()
    }

    /** An I-frame: N(S)/N(R)-sequenced data, only meaningful once a connected-mode link is up. */
    fun encodeInformation(
        source: Ax25Address,
        destination: Ax25Address,
        digipeaters: List<Ax25Address> = emptyList(),
        ns: Int,
        nr: Int,
        pollFinal: Boolean = false,
        pid: Int = 0xF0,
        info: ByteArray,
    ): ByteArray {
        val control = ((ns and 0x07) shl 1) or (if (pollFinal) PF_BIT else 0) or ((nr and 0x07) shl 5)
        val out = java.io.ByteArrayOutputStream()
        out.write(encodeAddressChain(destination, source, digipeaters, command = true))
        out.write(control and 0xFF)
        out.write(pid and 0xFF)
        out.write(info)
        return out.toByteArray()
    }

    /** Connect request — always a command, P-bit always set (universal TNC convention). */
    fun encodeSabm(source: Ax25Address, destination: Ax25Address, digipeaters: List<Ax25Address> = emptyList()): ByteArray =
        encodeControlOnlyFrame(source, destination, digipeaters, CONTROL_SABM or PF_BIT, command = true)

    /** Disconnect request — always a command, P-bit always set. */
    fun encodeDisc(source: Ax25Address, destination: Ax25Address, digipeaters: List<Ax25Address> = emptyList()): ByteArray =
        encodeControlOnlyFrame(source, destination, digipeaters, CONTROL_DISC or PF_BIT, command = true)

    /**
     * Accepts a connect/disconnect request — always a response. F-bit always set: this
     * always mirrors a P=1 SABM/DISC (every TNC in practice sets P=1 on those), so there's
     * no incoming P value worth threading through here.
     */
    fun encodeUa(source: Ax25Address, destination: Ax25Address, digipeaters: List<Ax25Address> = emptyList()): ByteArray =
        encodeControlOnlyFrame(source, destination, digipeaters, CONTROL_UA or PF_BIT, command = false)

    /** Refuses a connect request, or reports "no such link" — always a response. */
    fun encodeDm(source: Ax25Address, destination: Ax25Address, digipeaters: List<Ax25Address> = emptyList()): ByteArray =
        encodeControlOnlyFrame(source, destination, digipeaters, CONTROL_DM or PF_BIT, command = false)

    /** Acknowledges up to N(R); [command] is only ever `false` in this app — see [Ax25LinkSession]. */
    fun encodeReceiveReady(
        source: Ax25Address,
        destination: Ax25Address,
        digipeaters: List<Ax25Address> = emptyList(),
        nr: Int,
        pollFinal: Boolean,
        command: Boolean,
    ): ByteArray = encodeControlOnlyFrame(source, destination, digipeaters, sFrameControl(SS_RR, nr, pollFinal), command)

    /** Requests retransmission starting at N(R); [command] is only ever `false` in this app. */
    fun encodeReject(
        source: Ax25Address,
        destination: Ax25Address,
        digipeaters: List<Ax25Address> = emptyList(),
        nr: Int,
        pollFinal: Boolean,
        command: Boolean,
    ): ByteArray = encodeControlOnlyFrame(source, destination, digipeaters, sFrameControl(SS_REJ, nr, pollFinal), command)

    private fun sFrameControl(sType: Int, nr: Int, pollFinal: Boolean): Int =
        0x01 or (sType shl 2) or (if (pollFinal) PF_BIT else 0) or ((nr and 0x07) shl 5)

    private fun encodeControlOnlyFrame(
        source: Ax25Address,
        destination: Ax25Address,
        digipeaters: List<Ax25Address>,
        control: Int,
        command: Boolean,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(encodeAddressChain(destination, source, digipeaters, command))
        out.write(control and 0xFF)
        return out.toByteArray()
    }

    /**
     * Builds the destination/source/digipeater address chain with the command/response (C)
     * bit set correctly: 1 on the destination and 0 on the source for a command frame, and
     * the mirror image for a response — digipeater addresses never carry it (that bit
     * position is their unrelated has-been-repeated flag, which this app, never itself a
     * digipeater, never sets).
     */
    private fun encodeAddressChain(
        destination: Ax25Address,
        source: Ax25Address,
        digipeaters: List<Ax25Address>,
        command: Boolean,
    ): ByteArray {
        val chain = buildList {
            add(destination to command)
            add(source to !command)
            addAll(digipeaters.map { it to false })
        }
        val out = java.io.ByteArrayOutputStream()
        chain.forEachIndexed { index, (addr, cBit) ->
            out.write(encodeAddress(addr, isLast = index == chain.lastIndex, cBit = cBit))
        }
        return out.toByteArray()
    }

    private fun encodeAddress(address: Ax25Address, isLast: Boolean, cBit: Boolean): ByteArray {
        val call = address.callsign.uppercase().take(6).padEnd(6)
        val out = ByteArray(ADDRESS_LEN)
        for (i in 0 until 6) out[i] = ((call[i].code shl 1) and 0xFF).toByte()
        // Bits 5-6 set per convention (reserved, left at their default); bit 7 is the C-bit;
        // bit0 marks the last address.
        var ssidByte = 0x60 or ((address.ssid and 0x0F) shl 1)
        if (cBit) ssidByte = ssidByte or 0x80
        if (isLast) ssidByte = ssidByte or 0x01
        out[6] = ssidByte.toByte()
        return out
    }

    private data class ParsedAddress(val address: Ax25Address, val isLast: Boolean)

    private fun decodeAddress(bytes: ByteArray, offset: Int): ParsedAddress {
        val chars = CharArray(6) { i -> ((bytes[offset + i].toInt() and 0xFF) shr 1).toChar() }
        val call = String(chars).trim()
        val ssidByte = bytes[offset + 6].toInt() and 0xFF
        val ssid = (ssidByte shr 1) and 0x0F
        val isLast = (ssidByte and 0x01) != 0
        return ParsedAddress(Ax25Address(call, ssid), isLast)
    }

    /** Decodes destination/source/digipeaters and the control-byte-driven [Ax25FrameContent]. */
    fun decodeFrame(bytes: ByteArray): Ax25DecodedFrame? {
        val addresses = mutableListOf<ParsedAddress>()
        var offset = 0
        while (true) {
            if (offset + ADDRESS_LEN > bytes.size) return null
            val parsed = decodeAddress(bytes, offset)
            addresses += parsed
            offset += ADDRESS_LEN
            if (parsed.isLast) break
            if (addresses.size > 10) return null // 2 endpoints + up to 8 digis
        }
        if (addresses.size < 2 || offset >= bytes.size) return null

        val control = bytes[offset].toInt() and 0xFF
        offset += 1

        val content: Ax25FrameContent = when {
            (control and 0x01) == 0 -> {
                val ns = (control shr 1) and 0x07
                val pf = (control and PF_BIT) != 0
                val nr = (control shr 5) and 0x07
                if (offset >= bytes.size) return null
                val pid = bytes[offset].toInt() and 0xFF
                val info = bytes.copyOfRange(offset + 1, bytes.size)
                Ax25FrameContent.Information(ns, nr, pf, pid, info)
            }
            (control and 0x03) == 0x01 -> {
                val sType = (control shr 2) and 0x03
                val pf = (control and PF_BIT) != 0
                val nr = (control shr 5) and 0x07
                // Bug fix: this used to map 1->Reject and 2->ReceiveNotReady, backwards from the
                // real AX.25 S-frame type-code assignment (RR=0, RNR=1, REJ=2) — confirmed against
                // the canonical control-byte values (RR=0x01, RNR=0x05, REJ=0x09 at N(R)=0). Any
                // real RNR/REJ frame from a peer was mislabeled in the Monitor view until now.
                when (sType) {
                    0 -> Ax25FrameContent.ReceiveReady(nr, pf)
                    1 -> Ax25FrameContent.ReceiveNotReady(nr, pf)
                    2 -> Ax25FrameContent.Reject(nr, pf)
                    else -> Ax25FrameContent.Unknown(control)
                }
            }
            (control and 0xEF) == CONTROL_UI -> {
                if (offset >= bytes.size) return null
                val pid = bytes[offset].toInt() and 0xFF
                val info = bytes.copyOfRange(offset + 1, bytes.size)
                Ax25FrameContent.UnnumberedInformation(pid, info)
            }
            (control and 0xEF) == CONTROL_SABM -> Ax25FrameContent.SetAsynchronousBalancedMode
            (control and 0xEF) == CONTROL_DISC -> Ax25FrameContent.Disconnect
            (control and 0xEF) == CONTROL_DM -> Ax25FrameContent.DisconnectedMode
            (control and 0xEF) == CONTROL_UA -> Ax25FrameContent.UnnumberedAcknowledge
            (control and 0xEF) == CONTROL_FRMR -> Ax25FrameContent.FrameReject
            else -> Ax25FrameContent.Unknown(control)
        }

        return Ax25DecodedFrame(
            destination = addresses[0].address,
            source = addresses[1].address,
            digipeaters = addresses.drop(2).map { it.address },
            content = content,
        )
    }

    /**
     * NUL-stripped (but, unlike [AgwFrame.textFromBytes], *not* further
     * trimmed) — matches the desktop's `describe_frame`, which only ever
     * calls `.replace('\0', "")` on KISS-decoded info text.
     */
    fun kissTextFromBytes(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).replace("\u0000", "")

    /** Monitor-line text for a decoded frame, mirroring `pr-ax25::kiss_runner::describe_frame`. */
    fun describeFrame(frame: Ax25DecodedFrame): String {
        val from = frame.source.label()
        val to = frame.destination.label()
        return when (val content = frame.content) {
            is Ax25FrameContent.Information ->
                "$from > $to [I N(S)=${content.ns} N(R)=${content.nr}]${pidSuffix(content.pid)}: ${kissTextFromBytes(content.info)}"
            is Ax25FrameContent.UnnumberedInformation ->
                "$from > $to [UI]${pidSuffix(content.pid)}: ${kissTextFromBytes(content.info)}"
            is Ax25FrameContent.ReceiveReady -> "$from > $to [RR N(R)=${content.nr}]"
            is Ax25FrameContent.ReceiveNotReady -> "[RNR N(R)=${content.nr}]"
            is Ax25FrameContent.Reject -> "[REJ N(R)=${content.nr}]"
            Ax25FrameContent.SetAsynchronousBalancedMode -> "[SABM]"
            Ax25FrameContent.Disconnect -> "[DISC]"
            Ax25FrameContent.DisconnectedMode -> "[DM]"
            Ax25FrameContent.UnnumberedAcknowledge -> "[UA]"
            Ax25FrameContent.FrameReject -> "[FRMR]"
            is Ax25FrameContent.Unknown -> "[?]"
        }
    }
}
