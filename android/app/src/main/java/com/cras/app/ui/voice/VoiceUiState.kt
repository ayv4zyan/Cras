package com.cras.app.ui.voice

import com.cras.app.voice.DraftTask
import com.cras.app.voice.VoiceCaptureMode
import com.cras.app.voice.VoiceError

/**
 * Why Voice is unavailable. Ordinary Task work continues unaffected in every
 * case; these only gate the Voice capture surface.
 */
sealed interface VoiceFailure {
    /** RECORD_AUDIO permission not granted on this installation. */
    data object MicPermissionMissing : VoiceFailure

    /** Voice allowance or rate limit reached for this Operator. */
    data class AllowanceExhausted(
        val earliestRetryAt: String? = null,
        val retryAfterSeconds: Int? = null,
    ) : VoiceFailure

    /** The Deployment circuit breaker temporarily disabled Voice for everyone. */
    data object CircuitBreakerTripped : VoiceFailure

    /** Voice is disabled in the Deployment configuration. */
    data object VoiceDisabled : VoiceFailure

    /** STT/extractor provider failed after retries. */
    data object ProviderFailed : VoiceFailure

    /** The boundary could not be reached; the recording was preserved locally. */
    data class NetworkError(val message: String) : VoiceFailure

    /** The boundary rejected the audio payload. */
    data class InvalidAudio(val message: String) : VoiceFailure

    /** Any other failure. */
    data class Unknown(val message: String) : VoiceFailure
}

/** Maps a boundary [VoiceError] onto an unavailable-Voice reason. */
fun voiceFailureFromError(error: VoiceError): VoiceFailure = when {
    error.isNetworkError -> VoiceFailure.NetworkError(error.message)
    error.code == "invalid_audio" -> VoiceFailure.InvalidAudio(error.message)
    error.status == 503 && error.code?.startsWith("circuit_breaker") == true ->
        VoiceFailure.CircuitBreakerTripped
    error.status == 503 && error.code == "voice_disabled" ->
        VoiceFailure.VoiceDisabled
    error.status == 502 -> VoiceFailure.ProviderFailed
    error.status == 429 || error.status == 402 -> VoiceFailure.AllowanceExhausted(
        earliestRetryAt = error.earliestRetryAt,
        retryAfterSeconds = error.retryAfterSeconds,
    )
    else -> VoiceFailure.Unknown(error.message)
}

/**
 * Compose states of the Voice capture flow:
 * recording, processing, Draft (create/edit/correction), and rejection via
 * per-draft validation errors plus the unavailable-Voice failures above.
 */
sealed interface VoiceUiState {
    data object Idle : VoiceUiState

    data class Recording(
        val elapsedMs: Long = 0L,
        val maxDurationMs: Long = com.cras.app.voice.MAX_RECORDING_DURATION_MS,
    ) : VoiceUiState

    data object Processing : VoiceUiState

    data class Drafts(
        val transcript: String,
        val mode: VoiceCaptureMode,
        val drafts: List<DraftTask>,
        val editProposal: DraftTask? = null,
        val isCorrection: Boolean = false,
    ) : VoiceUiState {
        val hasValidationErrors: Boolean
            get() = drafts.any { !it.validationError.isNullOrEmpty() }
    }

    data class Failed(
        val failure: VoiceFailure,
        val canRetryWithSavedAudio: Boolean = false,
    ) : VoiceUiState
}
