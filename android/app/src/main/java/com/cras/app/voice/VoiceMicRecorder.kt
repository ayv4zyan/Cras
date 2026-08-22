package com.cras.app.voice

import java.io.IOException
import java.time.Instant

/** Thrown when microphone capture cannot start because RECORD_AUDIO is denied. */
class MicPermissionDeniedException(
    cause: Throwable? = null,
) : IOException("Microphone permission denied.", cause)

/**
 * Microphone capture boundary for Voice capture. Mirrors the web AudioRecorder
 * lifecycle: start (with an auto-stop hook at the shared duration limit),
 * stop returning the normalized WAV, or cancel discarding the take.
 */
interface VoiceMicRecorder {
    val isRecording: Boolean

    /** The instant recording began; anchors relative dates at the boundary. */
    val startedAt: Instant?

    /** Invoked once when the shared recording limit forces an automatic stop. */
    var onAutoStop: (() -> Unit)?

    /** Begins capturing microphone audio. Throws when capture cannot start. */
    fun start()

    /**
     * Stops capture and returns the normalized mono 16 kHz PCM WAV result.
     * Throws like the web recorder when nothing was recorded.
     */
    fun stop(): AudioRecordingResult

    /** Cancels and discards the active recording. */
    fun cancel()
}

object MicAudioRecorderFactory {
    fun create(): VoiceMicRecorder = MicAudioRecorder()
}
