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
import android.os.Process
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One outgoing burst of real audio for [AfskModem]'s continuous TX writer thread. */
private class TxJob(val samples: ShortArray, val done: CompletableDeferred<Unit>)

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

        // One AudioTrack plays continuously for the entire port session — fed silence when
        // idle, real modulated samples during TX — rather than a fresh track built per
        // transmission. Confirmed by recording an actual transmission and analyzing it with
        // fine-resolution FFT: the tone frequency drifted from ~1670Hz toward the correct
        // 1600Hz over roughly the first second, exactly matching a USB audio DAC clock/PLL
        // still stabilizing right after being (re)enabled — building a fresh track per
        // transmission re-triggers that enable/re-lock cycle every single time, and most of
        // this app's transmissions are shorter than the clock's settling time, meaning the
        // audio was drifting for its entire duration. Neither Direwolf's own decoder (tested
        // offline via atest against the recording) nor this app's own decoder could lock onto
        // any of it. A track that's continuously playing (even if silent) never triggers
        // Android's separate idle-route teardown either — that targets a paused/inactive
        // track, not one that's actively playing — so this avoids both problems at once.
        val track = buildOutputTrack()
        track.play()
        log("TX: continuous playback started via ${track.routedDevice?.productName ?: "unknown"}.")
        val outputLatencyMs: Long = try {
            (track.javaClass.getMethod("getLatency").invoke(track) as Int).toLong()
        } catch (_: Exception) {
            100L
        }

        val txQueue = LinkedBlockingQueue<TxJob>()
        val outputRunning = AtomicBoolean(true)
        val outputThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val silence = ShortArray(minBufOut)
            while (outputRunning.get()) {
                val job = txQueue.poll(20, TimeUnit.MILLISECONDS)
                if (job == null) {
                    track.write(silence, 0, silence.size)
                } else {
                    var offset = 0
                    while (offset < job.samples.size) {
                        val chunk = minOf(minBufOut, job.samples.size - offset)
                        val written = track.write(job.samples, offset, chunk)
                        if (written <= 0) {
                            log("TX: write returned $written at offset $offset/${job.samples.size}.")
                            break
                        }
                        offset += written
                    }
                    job.done.complete(Unit)
                }
            }
        }.apply { name = "AfskModem-TX"; start() }

        record.startRecording()

        // Confirms AudioRecord actually routed to the requested USB device rather than silently
        // falling back to the built-in mic. routedDevice is only populated once capture is
        // actively running, hence the check after startRecording().
        log("RX: requested input=${inputDevice.productName} (id=${inputDevice.id}), actually routed to=${record.routedDevice?.productName ?: "unknown"} (id=${record.routedDevice?.id ?: -1}), inputGain=${settings.inputGain}.")

        // Pin STREAM_MUSIC to max so the TX level is deterministic instead of depending on
        // whatever the phone's media volume happens to be. Android's volume steps are coarse,
        // and a one-step change can drop some interfaces below their detection threshold
        // outright. TX drive is then set by the modulator's own scale (AfskModulator.AMPLITUDE).
        // Note: this can overdrive interfaces with a linear audio input; if that shows up,
        // this is the place to make the level user-adjustable instead.
        val maxMusicVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusicVolume, 0)
        log("Modem started: ${config.baudRate} baud, mark=${config.markHz} Hz, space=${config.spaceHz} Hz, output latency=${outputLatencyMs}ms, source=$sourceName.")

        // RX runs on a dedicated thread at THREAD_PRIORITY_URGENT_AUDIO, not a coroutine on
        // the shared Dispatchers.IO pool. Confirmed via logcat during a real TX failure:
        // AudioFlinger's prepareTracks_l removed our TX track from its active list with
        // "BUFFER TIMEOUT ... due to underrun" — the underlying hardware period here is only
        // ~10ms (256 samples x 2 periods @ 48kHz), and ordinary scheduling jitter on a shared
        // thread pool (this coroutine competed with the TX write loop and whatever else uses
        // Dispatchers.IO) is enough to miss that deadline. THREAD_PRIORITY_URGENT_AUDIO is
        // Android's own documented mechanism for exactly this class of real-time audio I/O.
        val rxRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val rxThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val readBuf = ShortArray(512)
            val gain = settings.inputGain
            while (rxRunning.get()) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    for (i in 0 until n) {
                        val s = if (gain == 1.0) readBuf[i]
                                else (readBuf[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        demodulator.feed(s)
                    }
                }
            }
        }.apply { name = "AfskModem-RX"; start() }

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

                // Hand the real samples to the continuously-running output thread (see
                // outputThread above) — it's already playing this same track, so this is
                // just a stream hand-off, not a fresh play()/route cycle.
                val startPos = track.playbackHeadPosition
                val markerReached = CompletableDeferred<Unit>()
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) { markerReached.complete(Unit) }
                    override fun onPeriodicNotification(t: AudioTrack) {}
                })
                track.setNotificationMarkerPosition(startPos + samples.size)

                val writeDone = CompletableDeferred<Unit>()
                txQueue.put(TxJob(samples, writeDone))
                writeDone.await()

                val timeoutMs = samples.size.toLong() * 1000 / config.sampleRate + outputLatencyMs + 1000
                if (withTimeoutOrNull(timeoutMs) { markerReached.await() } == null) {
                    log("TX: playback marker timed out after ${timeoutMs}ms.")
                }
                track.setPlaybackPositionUpdateListener(null)

                kotlinx.coroutines.delay(outputLatencyMs + settings.tailMs)
                ptt(false)
                log("TX: PTT off.")
            }
        } finally {
            rxRunning.set(false)
            try { record.stop() } catch (_: Exception) {}  // unblocks the RX thread's read()
            try { rxThread.join(1000) } catch (_: Exception) {}
            outputRunning.set(false)
            try { outputThread.join(1000) } catch (_: Exception) {}
            try { track.stop(); track.release() } catch (_: Exception) {}
            fwdJob.cancel()
            rxChannel.close()
            try { agc?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
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
