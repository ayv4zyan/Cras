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

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var buffer: RecordingBuffer? = null

    @Volatile
    private var pendingResult: AudioRecordingResult? = null
    private var autoStopFired = false

    /**
     * Identity of the current take. Capture threads carry their take's value
     * and refuse to touch shared state once it no longer matches, so a thread
     * that outlives a timed-out stop/cancel becomes harmless even if it wakes
     * up later.
     */
    @Volatile
    private var generation = 0L

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

            try {
                generation += 1
                val takeGeneration = generation
                buffer = RecordingBuffer(inputSampleRate = config)
                pendingResult = null
                autoStopFired = false
                audioRecord = record
                startedAt = Instant.now()
                active = true
                record.startRecording()

                captureThread = thread(name = "cras-voice-capture") {
                    captureLoop(record, config, takeGeneration)
                }
            } catch (e: Throwable) {
                // A failure after construction must neither leak the native
                // microphone session nor wedge the recorder into a state where
                // every later start() returns early: release and clear first.
                active = false
                audioRecord = null
                releaseRecordQuietly(record)
                reset()
                throw e
            }
        } catch (e: SecurityException) {
            active = false
            throw MicPermissionDeniedException(e)
        }
    }

    override fun stop(): AudioRecordingResult {
        if (!active && pendingResult == null && (buffer?.isEmpty != false)) {
            throw IllegalStateException("No active recording to stop.")
        }
        active = false
        // When auto-stop fired from the capture thread it already finalized.
        if (!joinCaptureThreadIfExternal()) {
            // The capture thread is wedged past the bounded join. Invalidate its
            // finalization instead of racing it, and refuse the unsalvageable
            // take rather than reading a buffer it may still be mutating.
            generation += 1
            reset()
            throw IllegalStateException("Microphone capture did not finish; recording abandoned.")
        }
        val result = pendingResult
            ?: buffer?.buildOrNull()
            ?: throw IllegalStateException("No active recording to stop.")
        reset()
        return result
    }

    override fun cancel() {
        active = false
        if (!joinCaptureThreadIfExternal()) {
            generation += 1
        }
        reset()
    }

    /** Returns true when the capture thread finished (or the call comes from it). */
    private fun joinCaptureThreadIfExternal(): Boolean {
        val thread = captureThread ?: return true
        if (thread == Thread.currentThread()) return true
        runCatching { thread.join(JOIN_TIMEOUT_MS) }
        return !thread.isAlive
    }

    private fun reset() {
        captureThread = null
        buffer = null
        pendingResult = null
        startedAt = null
        autoStopFired = false
    }

    private fun captureLoop(record: AudioRecord, sampleRate: Int, takeGeneration: Long) {
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

        if (takeGeneration != generation) {
            // This take was abandoned by a caller whose bounded join timed out;
            // the caller owns the shared state now. Release locally, stay quiet.
            releaseRecordQuietly(record)
            return
        }

        finalizeCapture(record, boundReached, takeGeneration)
    }

    private fun finalizeCapture(record: AudioRecord, boundReached: Boolean, takeGeneration: Long) {
        releaseRecordQuietly(record)

        // Re-check after the potentially slow native calls: the caller may have
        // abandoned this take in between, and it must not see stale writes.
        if (takeGeneration != generation) return

        audioRecord = null
        pendingResult = buffer?.buildOrNull()

        if (boundReached && !autoStopFired) {
            autoStopFired = true
            onAutoStop?.invoke()
        }
    }

    private fun releaseRecordQuietly(record: AudioRecord) {
        try {
            record.stop()
        } catch (_: Exception) {
            // Already stopped or never started.
        }
        record.release()
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
