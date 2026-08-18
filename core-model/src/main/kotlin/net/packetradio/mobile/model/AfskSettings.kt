package net.packetradio.mobile.model

import kotlinx.serialization.Serializable

/**
 * User-configurable AFSK modem parameters stored alongside [PortConfig.UsbAudio].
 *
 * Defaults are chosen to work reliably with most VHF/UHF radios and the Digirig
 * interface.  HF ([ModemMode.HF_300]) automatically gets a longer TX delay.
 */
@Serializable
data class AfskSettings(
    /** Milliseconds to hold PTT before audio starts. -1 = mode default (300 VHF / 500 HF). */
    val txDelayMs: Int = -1,
    /** Number of HDLC flag bytes (0x7E) in the preamble. More = easier sync for slow decoders. */
    val preambleFlags: Int = 20,
    /** Milliseconds PTT stays up after the last audio sample to allow the radio to stop transmitting. */
    val tailMs: Int = 50,
    /** CSMA P-persistence value (0–255). Higher = more likely to transmit immediately on a clear channel. */
    val persist: Int = 63,
    /** CSMA slot time in milliseconds. */
    val slotTimeMs: Int = 100,
    /** RMS carrier-detect threshold (0.0–1.0). Raise if the device's USB audio noise floor prevents TX. */
    val carrierThreshold: Double = 0.05,
    /** Software input gain multiplier (1.0 = unity). Raise on devices where USB audio input level is too low. */
    val inputGain: Double = 1.0,
)
