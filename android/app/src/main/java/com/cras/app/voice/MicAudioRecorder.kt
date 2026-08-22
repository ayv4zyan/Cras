package com.cras.app.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import java.time.Instant
import kotlin.concurrent.thread

/**
 * [VoiceMicRecorder] backed by android.media.AudioRecord. Captures mono 16-bit
 * PCM at the device's native rate on a background thread; the shared
 * normalization to mono 16 kHz 16-bit PCM WAV (duration/size bounded) happens
 * in RecordingBuffer, mirroring web audioRecorder.ts semantics.
 *
 * Kept thin and untested on the JVM: all logic that can fail in unit tests
 * lives in RecordingBuffer/WavCodec, which are covered.
 */
class MicAudioRecorder(
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
) : VoiceMicRecorder {

    @Volatile
    private var active = false

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var buffer: RecordingBuffer? = null
    private var pendingResult: AudioRecordingResult? = null
    private var autoStopFired = false
    private var inputSampleRate: Int = TARGET_SAMPLE_RATE

    override var startedAt: Instant? = null
        private set

    override var onAutoStop: (() -> Unit)? = null

    override val isRecording: Boolean
        get() = active

    override fun start() {
        if (active) return

        val config = pickCaptureConfig()
            ?: throw IOException("Microphone capture configuration unavailable.")

        try {
            val record = AudioRecord(
                audioSource,
                config,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferBytes(config) * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IOException("Microphone initialisation failed. Check RECORD_AUDIO permission.")
            }

            buffer = RecordingBuffer(inputSampleRate = config)
            pendingResult = null
            autoStopFired = false
            inputSampleRate = config
            audioRecord = record
            startedAt = Instant.now()
            active = true
            record.startRecording()

            captureThread = thread(name = "cras-voice-capture") {
                captureLoop(record, config)
            }
        } catch (e: SecurityException) {
            active = false
            throw IOException("Microphone permission denied.", e)
        }
    }

    override fun stop(): AudioRecordingResult {
        if (!active && pendingResult == null && (buffer?.isEmpty != false)) {
            throw IllegalStateException("No active recording to stop.")
        }
        active = false
        // When auto-stop fired from the capture thread it already finalized.
        joinCaptureThreadIfExternal()
        val result = pendingResult
            ?: buffer?.buildOrNull()
            ?: throw IllegalStateException("No active recording to stop.")
        reset()
        return result
    }

    override fun cancel() {
        active = false
        joinCaptureThreadIfExternal()
        reset()
    }

    private fun joinCaptureThreadIfExternal() {
        val thread = captureThread ?: return
        if (thread != Thread.currentThread()) {
            runCatching { thread.join(JOIN_TIMEOUT_MS) }
        }
    }

    private fun reset() {
        captureThread = null
        buffer = null
        pendingResult = null
        startedAt = null
        autoStopFired = false
    }

    private fun captureLoop(record: AudioRecord, sampleRate: Int) {
        val chunkShorts = ShortArray(sampleRate / CHUNKS_PER_SECOND)
        var boundReached = false

        while (active) {
            val read = record.read(chunkShorts, 0, chunkShorts.size)
            if (read <= 0) break

            val floats = FloatArray(read) { i -> chunkShorts[i] / 32768f }
            buffer?.append(floats)

            if (buffer?.shouldAutoStop == true) {
                boundReached = true
                break
            }
        }
        active = false

        finalizeCapture(record, boundReached)
    }

    private fun finalizeCapture(record: AudioRecord, boundReached: Boolean) {
        try {
            record.stop()
        } catch (_: Exception) {
            // Already stopped or never started.
        }
        record.release()
        audioRecord = null

        pendingResult = buffer?.buildOrNull()

        if (boundReached && !autoStopFired) {
            autoStopFired = true
            onAutoStop?.invoke()
        }
    }

    /**
     * Picks a supported native capture rate so resample-to-16k is exercised on
     * real devices, falling back to 16 kHz which is universally supported.
     */
    private fun pickCaptureConfig(): Int? =
        CAPTURE_RATES.firstOrNull { rate ->
            AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ) > 0
        }

    private fun minBufferBytes(sampleRate: Int): Int =
        AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate)

    companion object {
        // ~100 ms chunks keep auto-stop latency low without busy looping.
        private const val CHUNKS_PER_SECOND = 10
        private const val JOIN_TIMEOUT_MS = 2_000L

        private val CAPTURE_RATES = intArrayOf(48000, 44100, 32000, 16000)
    }
}
