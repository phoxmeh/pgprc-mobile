package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * Selects the on-air modulation scheme for a USB Audio/PTT port.
 *
 * [BELL_202_1200] — 1200 baud AFSK, mark=1200 Hz, space=2200 Hz.
 *   Standard for VHF/UHF APRS and AX.25 packet (Digirig + any 2m/70cm radio).
 *
 * [HF_300] — 300 baud AFSK, mark=1600 Hz, space=1800 Hz.
 *   Standard for HF AX.25 packet and Winlink.
 */
@Serializable
enum class ModemMode {
    BELL_202_1200,
    HF_300,
}
