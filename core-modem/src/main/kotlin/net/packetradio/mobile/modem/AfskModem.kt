package net.packetradio.mobile.modem

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        if (audioDeviceName.isBlank()) {
            log("USB audio auto-selected: ${inputDevice.productName} (input) / ${outputDevice.productName} (output).")
        }

        val sampleRate = config.sampleRate
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBufIn = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufIn * 4)
                .build()
                .also { it.preferredDevice = inputDevice }
        } catch (e: SecurityException) {
            log("RECORD_AUDIO permission not granted — grant it in Settings > App permissions, then reconnect.")
            return@withContext
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            log("AudioRecord init failed — check USB audio device and RECORD_AUDIO permission.")
            record.release()
            return@withContext
        }

        val audioFormatOut = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBufOut = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioFormat(audioFormatOut)
            .setBufferSizeInBytes(minBufOut * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.preferredDevice = outputDevice }

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

        // Query the hardware output latency once — the time from when getPlaybackHeadPosition()
        // reports a frame as "played" to when it actually comes out of the DAC.  USB audio
        // typically adds 40-120ms here.  getLatency() is @hide but stable and widely used.
        val outputLatencyMs: Long = try {
            (track.javaClass.getMethod("getLatency").invoke(track) as Int).toLong()
        } catch (_: Exception) {
            100L
        }

        record.startRecording()
        // Keep the AudioTrack running continuously so the USB audio endpoint stays active
        // between transmissions.  Stopping/restarting it per TX causes the USB isochronous
        // scheduling to re-initialize, which delays actual audio output by 50-150ms and
        // makes PTT appear to fire before audio starts.
        track.play()
        log("Modem started: ${config.baudRate} baud, mark=${config.markHz} Hz, space=${config.spaceHz} Hz, output latency=${outputLatencyMs}ms.")

        // RX coroutine — reads AudioRecord and feeds the demodulator
        val rxJob = launch(Dispatchers.IO) {
            val readBuf = ShortArray(512)
            while (isActive) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    for (i in 0 until n) demodulator.feed(readBuf[i])
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

                ptt(true)
                val txDelayMs = when {
                    settings.txDelayMs >= 0 -> settings.txDelayMs
                    config.baudRate <= 300  -> TX_DELAY_HF_MS
                    else                    -> TX_DELAY_VHF_MS
                }
                kotlinx.coroutines.delay(txDelayMs.toLong())

                // Capture head position just before writing — the track is continuously running
                // so the position is never reset; we use it as a relative reference.
                val startHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val targetHead = startHead + samples.size.toLong()

                var offset = 0
                while (offset < samples.size) {
                    val chunk = minOf(minBufOut, samples.size - offset)
                    track.write(samples, offset, chunk)
                    offset += chunk
                }

                // Poll until all samples have passed through the audio pipeline.
                // getPlaybackHeadPosition() tracks the pipeline position, not actual DAC output —
                // add outputLatencyMs + TAIL_MS after the poll to cover the remaining hardware path.
                val drainDeadline = System.currentTimeMillis() +
                    samples.size.toLong() * 1000 / config.sampleRate + outputLatencyMs + 1000
                while (System.currentTimeMillis() < drainDeadline) {
                    val pos = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (pos >= targetHead) break
                    kotlinx.coroutines.delay(5)
                }
                kotlinx.coroutines.delay(outputLatencyMs + settings.tailMs)
                ptt(false)
            }
        } finally {
            rxJob.cancel()
            fwdJob.cancel()
            rxChannel.close()
            try { record.stop(); record.release() } catch (_: Exception) {}
            try { track.stop(); track.release() } catch (_: Exception) {}
            ptt(false)
            log("Modem stopped.")
        }
    }

    private suspend fun csmaWait(demodulator: AfskDemodulator) {
        while (true) {
            val rms = demodulator.rmsLevel()
            if (rms < CARRIER_THRESHOLD) {
                if ((Math.random() * 255).toInt() < settings.persist) return
            }
            kotlinx.coroutines.delay(settings.slotTimeMs.toLong())
        }
    }

    companion object {
        private const val CARRIER_THRESHOLD = 0.05
        private const val TX_DELAY_VHF_MS = 300
        private const val TX_DELAY_HF_MS = 500
    }
}
