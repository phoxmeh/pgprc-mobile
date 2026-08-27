package net.packetradio.mobile.modem

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.packetradio.mobile.model.AfskSettings
import net.packetradio.mobile.model.ModemMode

/**
 * AFSK modem coordinator.
 *
 * Bridges between raw AX.25 frame bytes (no FCS) and the USB audio device:
 * - RX: AudioRecord → AFSK demod → NRZI decode → HDLC decode → emits frames via [rxFrames]
 * - TX: receives frames from [txFrames] → HDLC encode → NRZI encode → AFSK mod → AudioTrack
 *       PTT is controlled via the [ptt] callback (true = transmitting).
 *
 * CSMA: before transmitting, waits for channel to be quiet using the RMS level
 * from the demodulator.  Uses P-persistence with [PERSIST] and [SLOT_TIME_MS].
 *
 * @param config          Modem parameters (baud rate, tone frequencies, sample rate).
 * @param audioManager    From context — used to find the USB audio device.
 * @param audioDeviceName Product name to match in [AudioManager.getDevices].
 * @param txFrames        Incoming AX.25 frames to transmit (without FCS).
 * @param rxFrames        Channel to deliver received AX.25 frames (without FCS).
 * @param log             Log message callback (called on the modem coroutine thread).
 * @param ptt             PTT callback: true = TX, false = RX.
 */
class AfskModem(
    private val config: AfskConfig,
    private val settings: AfskSettings,
    private val audioManager: AudioManager,
    private val audioDeviceName: String,
    private val txFrames: ReceiveChannel<ByteArray>,
    private val rxFrames: SendChannel<ByteArray>,
    private val log: (String) -> Unit,
    private val ptt: (Boolean) -> Unit,
) {
    suspend fun run() = withContext(Dispatchers.IO) {
        fun isUsbAudio(info: AudioDeviceInfo) =
            info.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                info.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                info.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        val allDevices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)

        val allUsbDevices = if (audioDeviceName.isBlank()) {
            allDevices.filter { isUsbAudio(it) }
        } else {
            allDevices.filter { isUsbAudio(it) && it.productName.toString().equals(audioDeviceName, ignoreCase = true) }
        }

        val inputDevice  = allUsbDevices.firstOrNull { it.isSource }
        val outputDevice = allUsbDevices.firstOrNull { !it.isSource }

        if (inputDevice == null || outputDevice == null) {
            val hint = if (audioDeviceName.isBlank()) "no USB audio device" else "\"$audioDeviceName\" not found"
            log("USB audio: $hint as ${if (inputDevice == null) "input" else ""}${if (inputDevice == null && outputDevice == null) "/" else ""}${if (outputDevice == null) "output" else ""} — ensure the device is connected.")
            return@withContext
        }
        log("USB audio: input=${inputDevice.productName} (id=${inputDevice.id}), output=${outputDevice.productName} (id=${outputDevice.id}).")

        val sampleRate = config.sampleRate
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Use UNPROCESSED to avoid Android's audio policy overriding preferredDevice. MIC source
        // triggers noise-suppression routing rules on some OEMs that silently redirect to the
        // built-in mic even when preferredDevice is a USB audio device.
        var audioSource = MediaRecorder.AudioSource.UNPROCESSED
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufIn * 4)
                .build()
                .also { it.preferredDevice = inputDevice }
        } catch (_: Exception) {
            // UNPROCESSED is not guaranteed on all devices; fall back to MIC.
            audioSource = MediaRecorder.AudioSource.MIC
            try {
                AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufIn * 4)
                    .build()
                    .also { it.preferredDevice = inputDevice }
            } catch (e: SecurityException) {
                log("RECORD_AUDIO permission not granted — grant it in Settings > App permissions, then reconnect.")
                return@withContext
            }
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            log("AudioRecord init failed — check USB audio device and RECORD_AUDIO permission.")
            record.release()
            return@withContext
        }

        // Disable AGC and noise suppression regardless of audio source — these distort AFSK tones.
        // UNPROCESSED bypasses them implicitly; for MIC fallback we disable them explicitly.
        val agc = if (AutomaticGainControl.isAvailable())
            AutomaticGainControl.create(record.audioSessionId)?.also { it.enabled = false }
        else null
        val ns = if (NoiseSuppressor.isAvailable())
            NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = false }
        else null
        val sourceName = if (audioSource == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC (AGC+NS disabled)"

        val audioFormatOut = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBufOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // USAGE_MEDIA/CONTENT_TYPE_MUSIC + explicit full volume, matching FT8CN's working
        // AudioTrack TX path — an unset AudioAttributes leaves this track's behavior under
        // system volume/audio-focus state unspecified, which could silently attenuate the
        // AFSK tone even when write()/play() succeed with no error.
        val trackAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // A fresh AudioTrack is built for EACH transmission (see the TX loop) rather than one
        // long-lived track kept paused between frames. Confirmed via USB audio HAL logs: a track
        // left paused/idle for even a few seconds has its underlying output patch torn down by
        // Android on its own (observed identically on two different USB audio interfaces, with
        // Bluetooth fully disabled, ruling out route contention from another device) — matching
        // FT8CN's own working AudioTrack TX path, which likewise builds a new track per
        // transmission rather than reusing one across idle periods.
        fun buildOutputTrack(): AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(trackAttributes)
            .setAudioFormat(audioFormatOut)
            .setBufferSizeInBytes(minBufOut * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                it.preferredDevice = outputDevice
                it.setVolume(1.0f)
            }

        val modulator = AfskModulator(config)
        val rxChannel = Channel<ByteArray>(64)

        val hdlcDecoder = HdlcCodec.Decoder { frame ->
            // Called from RX thread; bridge to coroutine via channel
            rxChannel.trySend(frame)
        }
        var nrziLastLevel = true
        val demodulator = AfskDemodulator(config) { dataBit ->
            // NRZI decode on the fly
            val nrziLevel = dataBit
            val nrziBit = nrziLevel == nrziLastLevel  // no transition = 1
            nrziLastLevel = nrziLevel
            hdlcDecoder.feed(nrziBit)
        }

        // Query the hardware output latency once via a throwaway probe track — the time from
        // when getPlaybackHeadPosition() reports a frame as "played" to when it actually comes
        // out of the DAC. USB audio typically adds 40-120ms here. getLatency() is @hide but
        // stable and widely used. Released immediately; the real per-transmission tracks are
        // built fresh in the TX loop.
        val outputLatencyMs: Long = buildOutputTrack().let { probe ->
            try {
                probe.play()
                (probe.javaClass.getMethod("getLatency").invoke(probe) as Int).toLong()
            } catch (_: Exception) {
                100L
            } finally {
                try { probe.stop(); probe.release() } catch (_: Exception) {}
            }
        }

        record.startRecording()

        // Force the USB device's STREAM_MUSIC volume to max — Android tracks a separate
        // volume index per output device, and this app's TX level shouldn't depend on
        // wherever the phone's media slider happens to be left. The per-track setVolume(1.0f)
        // in buildOutputTrack() is a separate multiplier on top of this; both are needed since
        // neither alone controls the other.
        val maxMusicVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVolume, 0)

        log("Modem started: ${config.baudRate} baud, mark=${config.markHz} Hz, space=${config.spaceHz} Hz, output latency=${outputLatencyMs}ms, source=$sourceName.")

        // RX coroutine — reads AudioRecord and feeds the demodulator
        val rxJob = launch(Dispatchers.IO) {
            val readBuf = ShortArray(512)
            val gain = settings.inputGain
            while (isActive) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    for (i in 0 until n) {
                        val s = if (gain == 1.0) readBuf[i]
                                else (readBuf[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        demodulator.feed(s)
                    }
                }
            }
        }

        // Frame forward coroutine — moves received frames from hdlcDecoder callback to caller
        val fwdJob = launch(Dispatchers.IO) {
            for (frame in rxChannel) {
                rxFrames.send(frame)
            }
        }

        // TX loop — processes outgoing frames with CSMA
        try {
            for (frame in txFrames) {
                if (!isActive) break

                // CSMA: wait for clear channel using P-persistence
                csmaWait(demodulator)

                // Build the full bit stream: HDLC → NRZI
                val hdlcBits = HdlcCodec.encodeFrame(frame, preambleFlags = settings.preambleFlags)
                val nrziBits = NrziCodec.encode(hdlcBits)
                val samples = modulator.modulate(nrziBits)

                val txDelayMs = when {
                    settings.txDelayMs >= 0 -> settings.txDelayMs
                    config.baudRate <= 300  -> TX_DELAY_HF_MS
                    else                    -> TX_DELAY_VHF_MS
                }
                log("TX: PTT on, ${samples.size} samples, ${frame.size} byte frame.")
                ptt(true)
                kotlinx.coroutines.delay(txDelayMs.toLong())

                // Build a fresh track for this transmission (see buildOutputTrack() kdoc for
                // why: a long-lived track left paused between frames had its output patch torn
                // down by Android after a few seconds).
                val track = buildOutputTrack()
                try {
                    // Prime the buffer with an initial chunk BEFORE calling play(), then keep
                    // writing while it plays. Confirmed via logcat (AudioFlinger/AHal::Usb):
                    // calling play() on a track with zero data queued starts the mixer pulling
                    // from an empty buffer immediately — "[AHWSinkUSB] HF underrun" fired 30ms
                    // after the interface enabled, and AudioFlinger's prepareTracks_l then
                    // removed the track from its active list entirely ("BUFFER TIMEOUT ...
                    // due to underrun") — once that happens nothing written afterward can play,
                    // regardless of what the rest of this function does. Writing the *entire*
                    // buffer before play() (the previous approach) has the opposite problem:
                    // this track's hardware ring buffer is far smaller than a full AX.25 frame's
                    // worth of audio (up to ~1.5s for a longer HF 300-baud frame), so once full
                    // with nothing yet draining it, write() has nowhere to put the rest. Priming
                    // one chunk avoids both: the mixer has something to consume the instant
                    // play() starts, and the loop below keeps feeding it before that runs dry.
                    val markerReached = CompletableDeferred<Unit>()
                    track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                        override fun onMarkerReached(t: AudioTrack) { markerReached.complete(Unit) }
                        override fun onPeriodicNotification(t: AudioTrack) {}
                    })
                    track.setNotificationMarkerPosition(samples.size)

                    var writeError = false
                    var offset = 0
                    val primeChunk = minOf(minBufOut, samples.size)
                    val primed = track.write(samples, 0, primeChunk)
                    if (primed <= 0) {
                        log("TX: write returned $primed priming offset 0/${samples.size}.")
                        writeError = true
                    } else {
                        offset = primed
                    }

                    if (!writeError) {
                        track.play()
                        log("TX: playing via ${track.routedDevice?.productName ?: "unknown"} (playState=${track.playState}).")
                    }

                    while (!writeError && offset < samples.size) {
                        val chunk = minOf(minBufOut, samples.size - offset)
                        val written = track.write(samples, offset, chunk)
                        if (written <= 0) {
                            log("TX: write returned $written at offset $offset/${samples.size}.")
                            writeError = true
                            break
                        }
                        offset += written
                    }

                    if (!writeError) {
                        val timeoutMs = samples.size.toLong() * 1000 / config.sampleRate + outputLatencyMs + 1000
                        if (withTimeoutOrNull(timeoutMs) { markerReached.await() } == null) {
                            log("TX: playback marker timed out after ${timeoutMs}ms.")
                        }
                    }
                } finally {
                    try { track.stop(); track.release() } catch (_: Exception) {}
                }
                kotlinx.coroutines.delay(outputLatencyMs + settings.tailMs)
                ptt(false)
                log("TX: PTT off.")
            }
        } finally {
            rxJob.cancel()
            fwdJob.cancel()
            rxChannel.close()
            try { agc?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { record.stop(); record.release() } catch (_: Exception) {}
            ptt(false)
            log("Modem stopped.")
        }
    }

    private suspend fun csmaWait(demodulator: AfskDemodulator) {
        var wasBusy = false
        while (true) {
            val rms = demodulator.rmsLevel()
            if (rms < settings.carrierThreshold) {
                if ((Math.random() * 255).toInt() < settings.persist) {
                    if (wasBusy) log("CSMA: channel clear, transmitting.")
                    return
                }
            } else if (!wasBusy) {
                log("CSMA: channel busy (RMS=%.3f ≥ threshold=%.3f), waiting for clear channel.".format(rms, settings.carrierThreshold))
                wasBusy = true
            }
            kotlinx.coroutines.delay(settings.slotTimeMs.toLong())
        }
    }

    companion object {
        private const val TX_DELAY_VHF_MS = 300
        private const val TX_DELAY_HF_MS = 500
    }
}
