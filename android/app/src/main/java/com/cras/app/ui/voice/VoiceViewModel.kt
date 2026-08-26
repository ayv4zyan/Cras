package com.cras.app.ui.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cras.app.auth.AuthService
import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Task
import com.cras.app.voice.DraftTask
import com.cras.app.voice.MAX_RECORDING_DURATION_MS
import com.cras.app.voice.MicAudioRecorderFactory
import com.cras.app.voice.MicPermissionDeniedException
import com.cras.app.voice.RetainedRecording
import com.cras.app.voice.VoiceCaptureApi
import com.cras.app.voice.VoiceCaptureMode
import com.cras.app.voice.VoiceCaptureRequestOptions
import com.cras.app.voice.VoiceError
import com.cras.app.voice.VoiceMicRecorder
import com.cras.app.voice.VoiceRecordingStore
import com.cras.app.voice.switchDraftTimedPlanType
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.cras.app.ui.inbox.AuthUiState

/**
 * Drives the Voice capture journey: recording, boundary call, Draft review and
 * correction. Failures only affect the Voice surface; ordinary Task work is
 * untouched by every state produced here.
 */
class VoiceViewModel(
    private val authService: AuthService,
    private val voiceCaptureApi: VoiceCaptureApi,
    private val recordingStore: VoiceRecordingStore,
    private val micRecorderProvider: () -> VoiceMicRecorder = { MicAudioRecorderFactory.create() },
    private val effectiveDefaultTimedPlanTypeProvider: () -> TimedPlanType = { TimedPlanType.INSTANT },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val timezoneProvider: () -> String = { ZoneId.systemDefault().id },
    private val micPermissionProvider: () -> Boolean = { true },
    private val nowProvider: () -> Instant = Instant::now,
    private val elapsedTickerIntervalMs: Long = 200L,
) : ViewModel() {

    private val _uiState = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val uiState: StateFlow<VoiceUiState> = _uiState.asStateFlow()

    private val _retainedRecordings =
        MutableStateFlow<List<RetainedRecording>>(emptyList())
    val retainedRecordings: StateFlow<List<RetainedRecording>> =
        _retainedRecordings.asStateFlow()

    private var activeRecorder: VoiceMicRecorder? = null
    private var recordingStart: Instant? = null
    private var tickerJob: Job? = null
    private var elapsedStartMs: Long = 0L
    private var sendJob: Job? = null

    private var focusedTask: Task? = null
    private var workingDrafts: List<DraftTask> = emptyList()

    /** Prepares the flow for an entry point (Inbox create or Task-detail edit). */
    fun open(focusedTask: Task?) {
        this.focusedTask = focusedTask
        refreshRetainedRecordings()
    }

    /** Leaves the Voice surface, cancelling any live recording or in-flight send. */
    fun close() {
        sendJob?.cancel()
        sendJob = null
        cancelRecording()
        workingDrafts = emptyList()
        focusedTask = null
        _uiState.value = VoiceUiState.Idle
    }

    fun startRecording() {
        // A live recorder must never be replaced: the orphaned instance would keep
        // holding the microphone. Processing waits for the in-flight send instead.
        if (activeRecorder != null || _uiState.value is VoiceUiState.Processing) {
            return
        }

        if (!micPermissionProvider()) {
            _uiState.value = VoiceUiState.Failed(VoiceFailure.MicPermissionMissing)
            return
        }

        cancelTicker()

        val recorder = try {
            micRecorderProvider().also {
                it.onAutoStop = { viewModelScope.launch { stopAndProcess(isCorrection = false) } }
            }
        } catch (_: Exception) {
            _uiState.value = VoiceUiState.Failed(
                VoiceFailure.Unknown("Failed to access microphone.")
            )
            return
        }

        try {
            recorder.start()
        } catch (_: MicPermissionDeniedException) {
            _uiState.value = VoiceUiState.Failed(VoiceFailure.MicPermissionMissing)
            return
        } catch (e: IOException) {
            _uiState.value = VoiceUiState.Failed(VoiceFailure.Unknown(e.message ?: "Microphone unavailable."))
            return
        }

        activeRecorder = recorder
        recordingStart = recorder.startedAt ?: nowProvider()
        elapsedStartMs = nowProvider().toEpochMilli()
        _uiState.value = VoiceUiState.Recording(elapsedMs = 0L)
        startTicker()
    }

    /**
     * "Correct by Voice": records again while keeping the drafts on screen so
     * the next send merges speech corrections into them.
     */
    fun correctByVoice() = startRecording()

    fun stopAndProcess(isCorrection: Boolean) {
        val recorder = activeRecorder ?: return
        val anchor = recordingStart ?: nowProvider()
        cancelTicker()

        val result = try {
            recorder.stop()
        } catch (_: IllegalStateException) {
            activeRecorder = null
            _uiState.value = VoiceUiState.Idle
            return
        } finally {
            // stopAndProcess may be re-entered from auto-stop; clear eagerly.
            if (activeRecorder === recorder) {
                activeRecorder = null
            }
        }

        retainForRetry(result.wav, anchor)

        _uiState.value = VoiceUiState.Processing
        sendCapture(
            audioWav = result.wav,
            anchor = anchor,
            includeFocusedTask = !isCorrection,
        )
    }

    fun stopAndProcess() = stopAndProcess(isCorrection = false)

    /** Re-sends the latest retained recording without re-recording. */
    fun retryProcessing() {
        if (_uiState.value is VoiceUiState.Processing) return
        val currentOpId = authService.currentSession.value?.operatorId
        val owner = recordingStore.getRecordingOwner()
        if (currentOpId == null || owner == null || owner != currentOpId) {
            if (recordingStore.list().isNotEmpty()) {
                deleteAllRetainedRecordings()
            }
            startRecording()
            return
        }
        val latest = recordingStore.latest() ?: run {
            startRecording()
            return
        }
        val wav = recordingStore.readBytes(latest.id)?.takeIf { it.isNotEmpty() } ?: run {
            _uiState.value = VoiceUiState.Failed(
                VoiceFailure.Unknown("The saved recording could not be read."),
                canRetryWithSavedAudio = false,
            )
            return
        }

        _uiState.value = VoiceUiState.Processing
        sendCapture(
            audioWav = wav,
            anchor = Instant.ofEpochMilli(latest.createdAtEpochMs),
            includeFocusedTask = true,
        )
    }

    fun cancelRecording() {
        cancelTicker()
        activeRecorder?.cancel()
        activeRecorder = null
        recordingStart = null
        if (_uiState.value is VoiceUiState.Recording || _uiState.value is VoiceUiState.Processing) {
            // Like close(): a cancelled take must not let the in-flight send
            // overwrite Idle with a late Drafts/Failed result.
            sendJob?.cancel()
            sendJob = null
            _uiState.value = VoiceUiState.Idle
        }
    }

    fun startOver() {
        workingDrafts = emptyList()
        startRecording()
    }

    fun updateDraft(index: Int, transform: (DraftTask) -> DraftTask) {
        val current = currentDraftsOrThrow()
        if (index !in current.indices) return
        workingDrafts = current.mapIndexed { i, draft -> if (i == index) transform(draft) else draft }
        publishDraftsIfVisible()
    }

    /** Replaces a whole draft card, as edited in Compose inputs. */
    fun replaceDraft(index: Int, updated: DraftTask) =
        updateDraft(index) { _ -> updated }

    fun removeDraft(index: Int) {
        val current = currentDraftsOrThrow()
        if (index !in current.indices) return
        workingDrafts = current.filterIndexed { i, _ -> i != index }
        publishDraftsIfVisible()
    }

    /** Switches a timed draft between Instant and Floating, preserving its face. */
    fun switchDraftPlanType(index: Int, newType: TimedPlanType) {
        updateDraft(index) { draft ->
            switchDraftTimedPlanType(draft, newType, effectiveDefault(), zoneIdProvider())
        }
    }

    fun deleteRetainedRecording(id: String): Boolean {
        var success = recordingStore.delete(id)
        var attempts = 0
        while (!success && attempts < 3) {
            attempts++
            success = recordingStore.delete(id)
        }
        refreshRetainedRecordings()
        return success
    }

    fun deleteAllRetainedRecordings(): Boolean {
        var success = recordingStore.clearAll()
        var attempts = 0
        while (!success && attempts < 3) {
            attempts++
            success = recordingStore.clearAll()
        }
        _retainedRecordings.value = recordingStore.list()
        if (!success || _retainedRecordings.value.isNotEmpty()) {
            _retainedRecordings.value = emptyList()
        }
        return success
    }

    /**
     * Observes [authState] and drops all retained recordings whenever the authenticated
     * operator switches or signs out.
     */
    suspend fun collectAuthStateAndPruneRecordings(authState: Flow<AuthUiState>) {
        collectAuthStateAndClearRecordingsOnOperatorChange(
            authState = authState,
            initialOperatorId = recordingStore.getRecordingOwner(),
            onOperatorChanged = { newOwner ->
                recordingStore.setRecordingOwner(newOwner)
            },
            onClearRecordings = {
                deleteAllRetainedRecordings()
            },
        )
    }

    fun refreshRetainedRecordings() {
        val owner = recordingStore.getRecordingOwner()
        val currentOpId = authService.currentSession.value?.operatorId
        if (currentOpId == null || owner == null || owner != currentOpId) {
            if (recordingStore.list().isNotEmpty()) {
                deleteAllRetainedRecordings()
            }
            _retainedRecordings.value = emptyList()
            return
        }
        _retainedRecordings.value = recordingStore.list()
    }

    // ---- internals ----

    private fun sendCapture(
        audioWav: ByteArray,
        anchor: Instant,
        includeFocusedTask: Boolean,
    ) {
        val session = authService.currentSession.value
        if (session == null) {
            _uiState.value = VoiceUiState.Failed(
                VoiceFailure.Unknown("Please sign in to use Voice capture."),
                canRetryWithSavedAudio = false,
            )
            return
        }

        val options = VoiceCaptureRequestOptions(
            audioWav = audioWav,
            recordingStartTime = anchor,
            timezone = timezoneProvider(),
            focusedTask = if (includeFocusedTask) focusedTask else null,
            existingDrafts = workingDrafts.ifEmpty { null },
            effectiveDefaultTimedPlanType = effectiveDefault(),
        )

        sendJob = viewModelScope.launch {
            try {
                val result = voiceCaptureApi.sendVoiceCapture(session, options)
                workingDrafts = result.drafts.toList()
                _uiState.value = VoiceUiState.Drafts(
                    transcript = result.transcript,
                    mode = result.mode,
                    drafts = workingDrafts,
                    editProposal = result.editProposal,
                    isCorrection = options.existingDrafts != null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceError) {
                _uiState.value = VoiceUiState.Failed(
                    failure = voiceFailureFromError(e),
                    canRetryWithSavedAudio = canRetrySavedRecording(),
                )
            } catch (e: Exception) {
                _uiState.value = VoiceUiState.Failed(
                    VoiceFailure.Unknown(e.message ?: "Voice capture failed."),
                    canRetryWithSavedAudio = canRetrySavedRecording(),
                )
            }
        }
    }

    private fun retainForRetry(wav: ByteArray, anchor: Instant) {
        try {
            val opId = authService.currentSession.value?.operatorId
            if (opId != null) {
                val owner = recordingStore.getRecordingOwner()
                if (owner != opId && recordingStore.list().isNotEmpty()) {
                    if (!recordingStore.clearAll()) {
                        return
                    }
                }
                recordingStore.setRecordingOwner(opId)
                recordingStore.save(wav, createdAtEpochMs = anchor.toEpochMilli())
            }
        } catch (_: Exception) {
            // Retention is best-effort; the capture still processes.
        }
        refreshRetainedRecordings()
    }

    private fun canRetrySavedRecording(): Boolean {
        val currentOpId = authService.currentSession.value?.operatorId ?: return false
        val owner = recordingStore.getRecordingOwner() ?: return false
        return owner == currentOpId && recordingStore.latest() != null
    }

    private fun effectiveDefault(): TimedPlanType = effectiveDefaultTimedPlanTypeProvider()

    private fun currentDraftsOrThrow(): List<DraftTask> = when (val state = _uiState.value) {
        is VoiceUiState.Drafts -> state.drafts
        else -> workingDrafts
    }

    private fun publishDraftsIfVisible() {
        val state = _uiState.value as? VoiceUiState.Drafts ?: return
        _uiState.value = state.copy(drafts = workingDrafts.toList())
    }

    private fun startTicker() {
        if (elapsedTickerIntervalMs <= 0L) return
        tickerJob = viewModelScope.launch {
            while (_uiState.value is VoiceUiState.Recording) {
                delay(elapsedTickerIntervalMs)
                val state = _uiState.value as? VoiceUiState.Recording ?: break
                val elapsed = nowProvider().toEpochMilli() - elapsedStartMs
                _uiState.value = state.copy(elapsedMs = elapsed.coerceAtMost(MAX_RECORDING_DURATION_MS))
            }
        }
    }

    private fun cancelTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}

/**
 * Observes [authState] and invokes [onClearRecordings] whenever the authenticated
 * operator switches or signs out, preserving retained recordings across same-operator
 * token refreshes and transient loading states.
 */
suspend fun collectAuthStateAndClearRecordingsOnOperatorChange(
    authState: Flow<AuthUiState>,
    initialOperatorId: String? = null,
    onOperatorChanged: (String?) -> Unit = {},
    onClearRecordings: () -> Boolean,
) {
    var lastAuthenticatedOperatorId: String? = initialOperatorId
    authState.collect { state ->
        when (state) {
            is AuthUiState.Authenticated -> {
                val currentOperatorId = state.session.operatorId
                if (lastAuthenticatedOperatorId != currentOperatorId) {
                    val cleanupSuccess = onClearRecordings()
                    if (cleanupSuccess) {
                        lastAuthenticatedOperatorId = currentOperatorId
                        onOperatorChanged(currentOperatorId)
                    }
                }
            }
            is AuthUiState.Unauthenticated -> {
                val cleanupSuccess = onClearRecordings()
                if (cleanupSuccess) {
                    lastAuthenticatedOperatorId = null
                    onOperatorChanged(null)
                }
            }
            is AuthUiState.Loading -> {
                // Maintain lastAuthenticatedOperatorId across transient loading
                // states to ensure operator transitions are detected accurately.
            }
        }
    }
}

