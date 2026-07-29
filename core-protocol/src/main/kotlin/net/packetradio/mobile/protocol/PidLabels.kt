package net.packetradio.mobile.protocol

/**
 * AX.25 PID (protocol-id) byte → short label, for cosmetic "what's riding
 * inside this frame" display. Ported from `pr_ax25::pid_label`.
 *
 * The desktop app needed two separate tables here — one for AGWPE's raw PID
 * byte, one matching the external `ax25` crate's own enum (whose byte
 * conversion was private) for the KISS side. Since this reimplementation
 * decodes AX.25 frames itself rather than depending on a third-party crate,
 * both codecs see the same raw PID byte and can share this single table.
 */
fun pidLabel(pid: Int): String? {
    val masked = pid and 0xFF
    return when (masked) {
        0xF0 -> null // no L3 / plain text — don't label our own default traffic
        0x01 -> "X.25 PLP"
        0x06 -> "Compressed TCP/IP"
        0x07 -> "Uncompressed TCP/IP"
        0x08 -> "Segmentation Fragment"
        0xC3 -> "TEXNET"
        0xC4 -> "Link Quality"
        0xCA -> "Appletalk"
        0xCB -> "Appletalk ARP"
        0xCC -> "ARPA IP"
        0xCD -> "ARPA ARP"
        0xCE -> "FlexNet"
        0xCF -> "NET/ROM"
        0xFF -> "Escape"
        else -> if (masked and 0b0011_0000 == 0b0001_0000 || masked and 0b0011_0000 == 0b0010_0000) {
            "Layer 3"
        } else {
            null
        }
    }
}

/** `" [PID: <label>]"`, or `""` when [pidLabel] has nothing to say. */
fun pidSuffix(pid: Int): String = pidLabel(pid)?.let { " [PID: $it]" } ?: ""
