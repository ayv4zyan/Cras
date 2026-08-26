package com.cras.app.ui.voice

import com.cras.app.auth.AuthService
import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Plan
import com.cras.app.models.Task
import com.cras.app.voice.AudioRecordingResult
import com.cras.app.voice.DraftTask
import com.cras.app.voice.ExtractedDraftPayload
import com.cras.app.voice.VoiceCaptureApi
import com.cras.app.voice.VoiceCaptureMode
import com.cras.app.voice.VoiceCaptureRequestOptions
import com.cras.app.voice.VoiceCaptureResult
import com.cras.app.voice.VoiceError
import com.cras.app.voice.VoiceRecordingStore
import com.cras.app.voice.VoiceMicRecorder
import com.cras.app.voice.RetainedRecording
import com.cras.app.ui.inbox.AuthUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.UUID

private fun anyTask(): Task = Task(
    id = UUID.randomUUID().toString(),
    title = "Existing",
    description = null,
    priority = 4,
    plan = null,
    labels = emptyList(),
    parentId = null,
    completedAt = null,
    createdAt = "2026-08-20T00:00:00Z",
    updatedAt = "2026-08-20T00:00:00Z",
    version = 1,
)

private class FakeAuthService(session: OperatorSession) : AuthService {
    private val flow = MutableStateFlow<OperatorSession?>(session)
    override val currentSession: StateFlow<OperatorSession?> = flow.asStateFlow()
    private val store = InMemorySessionStore()
    private val authenticated: OperatorSession = session

    override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): OperatorSession =
        authenticated

    override suspend fun restoreSession(): OperatorSession? = store.loadSession()
    override suspend fun restoreSession(session: OperatorSession): OperatorSession {
        store.saveSession(session)
        flow.value = session
        return session
    }
    override suspend fun signOut() {
        flow.value = null
    }
}

/** A mic recorder fake driven by synthetic samples and an injected clock. */
private class FakeMicRecorder(
    anchor: Instant,
) : VoiceMicRecorder {
    private val buffer = com.cras.app.voice.RecordingBuffer(inputSampleRate = 16000)

    @Volatile
    private var active = false

    private val anchorInstant: Instant = anchor

    override var onAutoStop: (() -> Unit)? = null
    override val isRecording: Boolean get() = active
    override val startedAt: Instant? get() = if (active) anchorInstant else null

    override fun start() {
        active = true
    }

    fun emitSamples(count: Int = 16_000) {
        check(active)
        buffer.append(FloatArray(count))
    }

    override fun stop(): AudioRecordingResult {
        active = false
        return buffer.build()
    }

    override fun cancel() {
        active = false
        buffer.cancel()
    }

    fun fireAutoStop() {
        onAutoStop?.invoke()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    // Recording began just before midnight UTC; processing happens later.
    private val recordingStart: Instant = Instant.parse("2026-08-21T23:59:30Z")

    private lateinit var recordingClock: MutableStateFlow<Instant>
    private val createdRecorders = mutableListOf<FakeMicRecorder>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        recordingClock = MutableStateFlow(recordingStart)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeVoiceCaptureApi : VoiceCaptureApi {
        val requests = mutableListOf<VoiceCaptureRequestOptions>()
        var response: ((VoiceCaptureRequestOptions) -> VoiceCaptureResult)? = null
        var error: VoiceError? = null

        /** When set, calls suspend here until completed, simulating an in-flight send. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun sendVoiceCapture(
            session: OperatorSession,
            options: VoiceCaptureRequestOptions,
        ): VoiceCaptureResult {
            requests.add(options)
            gate?.await()
            error?.let { throw it }
            return response?.invoke(options)
                ?: VoiceCaptureResult("", VoiceCaptureMode.CREATE, emptyList())
        }
    }

    private fun createResponse(vararg titles: String): (VoiceCaptureRequestOptions) -> VoiceCaptureResult =
        { options ->
            VoiceCaptureResult(
                transcript = "heard it",
                mode = VoiceCaptureMode.CREATE,
                drafts = titles.mapIndexed { index, title ->
                    createDraftTaskFromExtractedForTest(
                        ExtractedDraftPayload(title = title),
                        options.effectiveDefaultTimedPlanType,
                    )
                },
            )
        }

    private fun newViewModel(
        api: FakeVoiceCaptureApi,
        hasMicPermission: Boolean = true,
    ): VoiceViewModel {
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(session()),
            voiceCaptureApi = api,
            recordingStore = com.cras.app.voice.DirectoryVoiceRecordingStore(tmp.newFolder("retained")),
            micRecorderProvider = {
                FakeMicRecorder(recordingClock.value).also { createdRecorders.add(it) }
            },
            effectiveDefaultTimedPlanTypeProvider = { TimedPlanType.INSTANT },
            zoneIdProvider = { java.time.ZoneId.of("UTC") },
            timezoneProvider = { "UTC" },
            micPermissionProvider = { hasMicPermission },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )
        return viewModel
    }

    private fun session() = OperatorSession(
        operatorId = UUID.randomUUID().toString(),
        email = "op@example.com",
        accessToken = "token",
    )

    private fun startRecording(
        viewModel: VoiceViewModel,
        advance: () -> Unit,
    ): FakeMicRecorder {
        viewModel.startRecording()
        advance()
        val state = viewModel.uiState.value
        assertTrue("Expected Recording but was $state", state is VoiceUiState.Recording)
        return createdRecorders.last()
    }

    // ---- states ----

    @Test
    fun `missing microphone permission disables voice before capture starts`() = runTest {
        val api = FakeVoiceCaptureApi()
        val viewModel = newViewModel(api, hasMicPermission = false)

        viewModel.open(focusedTask = null)
        viewModel.startRecording()
        advanceUntilIdle()

        val state = viewModel.uiState.value as VoiceUiState.Failed
        assertEquals(VoiceFailure.MicPermissionMissing, state.failure)
        assertEquals(0, api.requests.size)
    }

    @Test
    fun `double-tapping start keeps a single live recorder instead of leaking the mic`() =
        runTest {
            val api = FakeVoiceCaptureApi().apply { response = createResponse("Once") }
            val viewModel = newViewModel(api)

            viewModel.open(focusedTask = null)
            startRecording(viewModel) { advanceUntilIdle() }
            viewModel.startRecording()
            advanceUntilIdle()

            assertEquals(1, createdRecorders.size)
            assertTrue(viewModel.uiState.value is VoiceUiState.Recording)
        }

    @Test
    fun `closing during processing discards the stale result instead of surfacing it later`() =
        runTest {
            val api = FakeVoiceCaptureApi().apply {
                gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                response = createResponse("Stale draft")
            }
            val viewModel = newViewModel(api)

            viewModel.open(focusedTask = null)
            val fake = startRecording(viewModel) { advanceUntilIdle() }
            fake.emitSamples(1_600)
            viewModel.stopAndProcess()
            advanceUntilIdle() // suspends inside the gated API call

            assertTrue(viewModel.uiState.value is VoiceUiState.Processing)

            viewModel.close()
            assertEquals(VoiceUiState.Idle, viewModel.uiState.value)

            api.gate?.complete(Unit)
            advanceUntilIdle()

            // The late response must not overwrite the fresh Idle session.
            assertEquals(VoiceUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `cancelling during processing discards the late result instead of surfacing it`() =
        runTest {
            val api = FakeVoiceCaptureApi().apply {
                gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                response = createResponse("Stale draft")
            }
            val viewModel = newViewModel(api)

            viewModel.open(focusedTask = null)
            val fake = startRecording(viewModel) { advanceUntilIdle() }
            fake.emitSamples(1_600)
            viewModel.stopAndProcess()
            advanceUntilIdle() // suspends inside the gated API call

            assertTrue(viewModel.uiState.value is VoiceUiState.Processing)

            viewModel.cancelRecording()
            assertEquals(VoiceUiState.Idle, viewModel.uiState.value)

            api.gate?.complete(Unit)
            advanceUntilIdle()

            // The cancelled send must not overwrite Idle with Drafts or Failed.
            assertEquals(VoiceUiState.Idle, viewModel.uiState.value)
        }

    @Test
    fun `clearing all retained recordings empties the retry store`() = runTest {
        val api = FakeVoiceCaptureApi().apply {
            error = VoiceError(502, "provider_error", "Voice processing failed.")
        }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()
        assertTrue(viewModel.retainedRecordings.value.isNotEmpty())

        viewModel.deleteAllRetainedRecordings()
        advanceUntilIdle()

        assertTrue(viewModel.retainedRecordings.value.isEmpty())
    }

    @Test
    fun `successful capture presents drafts anchored to the recording start`() = runTest {
        val api = FakeVoiceCaptureApi().apply { response = createResponse("Buy milk") }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(16_000) // one second of audio at 16 kHz
        viewModel.stopAndProcess()
        advanceUntilIdle()

        val state = viewModel.uiState.value as VoiceUiState.Drafts
        assertEquals(listOf("Buy milk"), state.drafts.map { it.title })
        assertEquals("heard it", state.transcript)
        assertEquals(VoiceCaptureMode.CREATE, state.mode)
        // The pre-midnight recording start anchors the boundary call.
        assertEquals(recordingStart, api.requests.single().recordingStartTime)
    }

    @Test
    fun `auto-stop processes the recording through the shared duration bound`() = runTest {
        val api = FakeVoiceCaptureApi().apply { response = createResponse("From auto stop") }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(120 * 16_000) // full 120 s
        fake.fireAutoStop() // recorder invokes the hook; VM stops and processes
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is VoiceUiState.Drafts)
        assertEquals(recordingStart, api.requests.single().recordingStartTime)
    }

    @Test
    fun `allowance exhaustion surfaces an allowance-exhausted failure and keeps the take for retry`() =
        runTest {
            val api = FakeVoiceCaptureApi().apply {
                error = VoiceError(
                    status = 429,
                    code = "rate_limit_minute",
                    message = "Rate limit exceeded.",
                    earliestRetryAt = "2026-08-22T00:01:00Z",
                    retryAfterSeconds = 45,
                )
            }
            val viewModel = newViewModel(api)

            viewModel.open(focusedTask = null)
            val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
            viewModel.stopAndProcess()
            advanceUntilIdle()

            val failed = viewModel.uiState.value as VoiceUiState.Failed
            assertEquals(
                VoiceFailure.AllowanceExhausted(
                    earliestRetryAt = "2026-08-22T00:01:00Z",
                    retryAfterSeconds = 45,
                ),
                failed.failure,
            )
            assertTrue(failed.canRetryWithSavedAudio)

            // Retry reuses the locally retained recording without re-recording.
            viewModel.retryProcessing()
            advanceUntilIdle()
            assertEquals(2, api.requests.size)
            assertEquals(recordingStart, api.requests[1].recordingStartTime)
        }

    @Test
    fun `deployment circuit breaker trips surface as unavailable voice`() = runTest {
        val api = FakeVoiceCaptureApi().apply {
            error = VoiceError(
                status = 503,
                code = "circuit_breaker_daily",
                message = "Voice capture is temporarily unavailable. Please try again later.",
            )
        }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()

        assertEquals(
            VoiceFailure.CircuitBreakerTripped,
            (viewModel.uiState.value as VoiceUiState.Failed).failure,
        )
    }

    @Test
    fun `provider failures surface distinctly from allowance states`() = runTest {
        val api = FakeVoiceCaptureApi().apply {
            error = VoiceError(502, "provider_error", "Voice processing failed. Please try again.")
        }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()

        assertEquals(
            VoiceFailure.ProviderFailed,
            (viewModel.uiState.value as VoiceUiState.Failed).failure,
        )
    }

    @Test
    fun `network failures flag a retryable saved recording`() = runTest {
        val api = FakeVoiceCaptureApi().apply {
            error = VoiceError(
                0,
                "network_error",
                "Network error: unable to reach Voice service. Your recording is preserved.",
                isNetworkError = true,
            )
        }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()

        val failed = viewModel.uiState.value as VoiceUiState.Failed
        assertEquals(
            VoiceFailure.NetworkError(
                "Network error: unable to reach Voice service. Your recording is preserved."
            ),
            failed.failure,
        )
        assertTrue(failed.canRetryWithSavedAudio)
    }

    // ---- correction & draft editing ----

    @Test
    fun `correction pass sends existing drafts context and merges updates`() = runTest {
        val api = FakeVoiceCaptureApi()
        val viewModel = newViewModel(api)
        api.response = createResponse("Buy milk", "Go to pool")

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()

        api.error = null
        api.response = { _ ->
            VoiceCaptureResult(
                transcript = "no, milk today",
                mode = VoiceCaptureMode.CREATE,
                drafts = listOf(
                    createDraftTaskFromExtractedForTest(
                        ExtractedDraftPayload(
                            title = "Buy milk",
                            target_draft_index = 0,
                            plan_date = "2026-08-21",
                        ),
                        TimedPlanType.INSTANT,
                    ),
                    createDraftTaskFromExtractedForTest(
                        ExtractedDraftPayload(title = "Go to pool"),
                        TimedPlanType.INSTANT,
                    ),
                ),
            )
        }

        viewModel.correctByVoice()
        advanceUntilIdle()
        val secondTake = startRecording(viewModel) { advanceUntilIdle() }
        secondTake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()

        val correctionOptions = api.requests[1]
        assertTrue(correctionOptions.existingDrafts != null)
        assertEquals(2, correctionOptions.existingDrafts!!.size)

        val state = viewModel.uiState.value as VoiceUiState.Drafts
        assertTrue(state.isCorrection)
        assertEquals(Plan.DateOnly(date = "2026-08-21"), state.drafts[0].plan)
    }

    @Test
    fun `switching a draft timed type through the view model preserves the displayed face`() =
        runTest {
            val api = FakeVoiceCaptureApi().apply { response = createResponse("Timed thing") }
            val viewModel = newViewModel(api)
            api.response = { _ ->
                VoiceCaptureResult(
                    transcript = "timed thing at nine",
                    mode = VoiceCaptureMode.CREATE,
                    drafts = listOf(
                        createDraftTaskFromExtractedForTest(
                            ExtractedDraftPayload(
                                title = "Timed thing",
                                plan_date = "2026-08-25",
                                plan_time = "09:30:00",
                            ),
                            TimedPlanType.INSTANT,
                            zoneId = java.time.ZoneId.of("UTC"),
                        ),
                    ),
                )
            }

            viewModel.open(focusedTask = null)
            val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
            viewModel.stopAndProcess()
            advanceUntilIdle()

            viewModel.switchDraftPlanType(0, TimedPlanType.FLOATING)
            advanceUntilIdle()

            val state = viewModel.uiState.value as VoiceUiState.Drafts
            assertEquals(Plan.Floating(date = "2026-08-25", time = "09:30"), state.drafts[0].plan)
        }

    @Test
    fun `removing and updating drafts mutates only the targeted draft`() = runTest {
        val api = FakeVoiceCaptureApi().apply { response = createResponse("One", "Two") }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()

        viewModel.removeDraft(0)
        advanceUntilIdle()

        val state = viewModel.uiState.value as VoiceUiState.Drafts
        assertEquals(listOf("Two"), state.drafts.map { it.title })
    }

    @Test
    fun `retained recordings are listed and removable by the operator`() = runTest {
        val api = FakeVoiceCaptureApi().apply {
            error = VoiceError(502, "provider_error", "Voice processing failed.")
        }
        val viewModel = newViewModel(api)

        viewModel.open(focusedTask = null)
        val fake = startRecording(viewModel) { advanceUntilIdle() }
        fake.emitSamples(1_600)
        viewModel.stopAndProcess()
        advanceUntilIdle()

        assertEquals(1, viewModel.retainedRecordings.value.size)
        val retainedId = viewModel.retainedRecordings.value.single().id

        viewModel.deleteRetainedRecording(retainedId)
        advanceUntilIdle()

        assertTrue(viewModel.retainedRecordings.value.isEmpty())
    }

    @Test
    fun `cold start with persisted owner clears recordings if authenticated operator differs`() = runTest {
        val folder = tmp.newFolder("cold_start_diff_op")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        store.setRecordingOwner("operator-A")
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)
        assertEquals(1, store.list().size)

        val api = FakeVoiceCaptureApi()
        val opB = OperatorSession(
            operatorId = "operator-B",
            email = "opB@example.com",
            accessToken = "tokenB",
        )
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(opB),
            voiceCaptureApi = api,
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        val authFlow = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
        val job = launch { viewModel.collectAuthStateAndPruneRecordings(authFlow) }
        advanceUntilIdle()

        authFlow.value = AuthUiState.Authenticated(opB)
        advanceUntilIdle()

        assertTrue(store.list().isEmpty())
        assertTrue(viewModel.retainedRecordings.value.isEmpty())
        assertEquals("operator-B", store.getRecordingOwner())
        job.cancel()
    }

    @Test
    fun `cold start with persisted owner retains recordings if authenticated operator matches`() = runTest {
        val folder = tmp.newFolder("cold_start_same_op")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        store.setRecordingOwner("operator-A")
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)
        assertEquals(1, store.list().size)

        val api = FakeVoiceCaptureApi()
        val opA = OperatorSession(
            operatorId = "operator-A",
            email = "opA@example.com",
            accessToken = "tokenA",
        )
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(opA),
            voiceCaptureApi = api,
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        val authFlow = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
        val job = launch { viewModel.collectAuthStateAndPruneRecordings(authFlow) }
        advanceUntilIdle()

        authFlow.value = AuthUiState.Authenticated(opA)
        advanceUntilIdle()

        assertEquals(1, store.list().size)
        assertEquals("operator-A", store.getRecordingOwner())
        job.cancel()
    }

    @Test
    fun `cold start with persisted owner clears recordings if unauthenticated`() = runTest {
        val folder = tmp.newFolder("cold_start_unauth")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        store.setRecordingOwner("operator-A")
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)
        assertEquals(1, store.list().size)

        val api = FakeVoiceCaptureApi()
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(session()),
            voiceCaptureApi = api,
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        val authFlow = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
        val job = launch { viewModel.collectAuthStateAndPruneRecordings(authFlow) }
        advanceUntilIdle()

        authFlow.value = AuthUiState.Unauthenticated()
        advanceUntilIdle()

        assertTrue(store.list().isEmpty())
        assertTrue(viewModel.retainedRecordings.value.isEmpty())
        assertNull(store.getRecordingOwner())
        job.cancel()
    }

    @Test
    fun `cold start with ownerless recordings clears recordings before assigning authenticated operator`() = runTest {
        val folder = tmp.newFolder("cold_start_ownerless_auth")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        // Store has recording but NO owner
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)
        assertNull(store.getRecordingOwner())
        assertEquals(1, store.list().size)

        val api = FakeVoiceCaptureApi()
        val opA = OperatorSession(
            operatorId = "operator-A",
            email = "opA@example.com",
            accessToken = "tokenA",
        )
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(opA),
            voiceCaptureApi = api,
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        val authFlow = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
        val job = launch { viewModel.collectAuthStateAndPruneRecordings(authFlow) }
        advanceUntilIdle()

        authFlow.value = AuthUiState.Authenticated(opA)
        advanceUntilIdle()

        assertTrue(store.list().isEmpty())
        assertTrue(viewModel.retainedRecordings.value.isEmpty())
        assertEquals("operator-A", store.getRecordingOwner())
        job.cancel()
    }

    @Test
    fun `cold start with ownerless recordings does not assign operator if cleanup fails`() = runTest {
        val rec = RetainedRecording("rec-ownerless", "rec-ownerless.wav", 100L, 1_000L)
        var storeOwner: String? = null
        val failingStore = object : VoiceRecordingStore {
            override fun save(wav: ByteArray, createdAtEpochMs: Long): RetainedRecording = rec
            override fun list(): List<RetainedRecording> = listOf(rec)
            override fun latest(): RetainedRecording? = rec
            override fun readBytes(id: String): ByteArray? = byteArrayOf(1, 2, 3)
            override fun delete(id: String): Boolean = false
            override fun clearAll(): Boolean = false
            override fun getRecordingOwner(): String? = storeOwner
            override fun setRecordingOwner(operatorId: String?) {
                storeOwner = operatorId
            }
        }

        val opA = OperatorSession(operatorId = "operator-A", email = "opA@example.com", accessToken = "tokenA")
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(opA),
            voiceCaptureApi = FakeVoiceCaptureApi(),
            recordingStore = failingStore,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        val authFlow = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
        val job = launch { viewModel.collectAuthStateAndPruneRecordings(authFlow) }
        advanceUntilIdle()

        authFlow.value = AuthUiState.Authenticated(opA)
        advanceUntilIdle()

        // Owner must NOT be set because cleanup failed
        assertNull(failingStore.getRecordingOwner())
        job.cancel()
    }

    @Test
    fun `cold start with ownerless recordings clears recordings when unauthenticated`() = runTest {
        val folder = tmp.newFolder("cold_start_ownerless_unauth")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        // Store has recording but NO owner
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)
        assertNull(store.getRecordingOwner())
        assertEquals(1, store.list().size)

        val api = FakeVoiceCaptureApi()
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(session()),
            voiceCaptureApi = api,
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        val authFlow = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
        val job = launch { viewModel.collectAuthStateAndPruneRecordings(authFlow) }
        advanceUntilIdle()

        authFlow.value = AuthUiState.Unauthenticated()
        advanceUntilIdle()

        assertTrue(store.list().isEmpty())
        assertTrue(viewModel.retainedRecordings.value.isEmpty())
        assertNull(store.getRecordingOwner())
        job.cancel()
    }

    @Test
    fun `refreshRetainedRecordings purges and clears memory if owner differs from active session`() = runTest {
        val folder = tmp.newFolder("refresh_owner_mismatch")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        store.setRecordingOwner("operator-A")
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)

        val opB = OperatorSession(
            operatorId = "operator-B",
            email = "opB@example.com",
            accessToken = "tokenB",
        )
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(opB),
            voiceCaptureApi = FakeVoiceCaptureApi(),
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        viewModel.refreshRetainedRecordings()
        assertTrue(store.list().isEmpty())
        assertTrue(viewModel.retainedRecordings.value.isEmpty())
    }

    @Test
    fun `failed deletion retries and clears in-memory retained list`() = runTest {
        val currentSession = session()
        var attempts = 0
        val stubStore = object : VoiceRecordingStore {
            val rec = RetainedRecording("rec-1", "rec-1.wav", 100L, 1_000L)
            var deleted = false
            var owner: String? = currentSession.operatorId
            override fun save(wav: ByteArray, createdAtEpochMs: Long): RetainedRecording = rec
            override fun list(): List<RetainedRecording> = if (deleted) emptyList() else listOf(rec)
            override fun latest(): RetainedRecording? = list().firstOrNull()
            override fun readBytes(id: String): ByteArray? = if (deleted) null else byteArrayOf(1, 2)
            override fun delete(id: String): Boolean {
                attempts++
                if (attempts >= 2) {
                    deleted = true
                    return true
                }
                return false
            }
            override fun clearAll(): Boolean {
                attempts++
                if (attempts >= 2) {
                    deleted = true
                    owner = null
                    return true
                }
                return false
            }
            override fun getRecordingOwner(): String? = owner
            override fun setRecordingOwner(operatorId: String?) {
                owner = operatorId
            }
        }

        val viewModel = VoiceViewModel(
            authService = FakeAuthService(currentSession),
            voiceCaptureApi = FakeVoiceCaptureApi(),
            recordingStore = stubStore,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        viewModel.refreshRetainedRecordings()
        assertEquals(1, viewModel.retainedRecordings.value.size)

        val success = viewModel.deleteAllRetainedRecordings()
        assertTrue(success)
        assertTrue(attempts >= 2)
        assertTrue(viewModel.retainedRecordings.value.isEmpty())
    }

    @Test
    fun `retryProcessing handles unreadable or empty saved recording gracefully`() = runTest {
        val currentSession = session()
        val stubStore = object : VoiceRecordingStore {
            val rec = RetainedRecording("rec-empty", "rec-empty.wav", 0L, 1_000L)
            var owner: String? = currentSession.operatorId
            override fun save(wav: ByteArray, createdAtEpochMs: Long): RetainedRecording = rec
            override fun list(): List<RetainedRecording> = listOf(rec)
            override fun latest(): RetainedRecording? = rec
            override fun readBytes(id: String): ByteArray? = ByteArray(0)
            override fun delete(id: String): Boolean = true
            override fun clearAll(): Boolean = true
            override fun getRecordingOwner(): String? = owner
            override fun setRecordingOwner(operatorId: String?) {
                owner = operatorId
            }
        }

        val viewModel = VoiceViewModel(
            authService = FakeAuthService(currentSession),
            voiceCaptureApi = FakeVoiceCaptureApi(),
            recordingStore = stubStore,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        viewModel.retryProcessing()
        advanceUntilIdle()

        val state = viewModel.uiState.value as VoiceUiState.Failed
        assertFalse(state.canRetryWithSavedAudio)
    }

    @Test
    fun `ownerless recordings are treated as unsafe and cleared on refresh`() = runTest {
        val folder = tmp.newFolder("ownerless_recordings_refresh")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        // Save recording without an owner
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)
        assertNull(store.getRecordingOwner())
        assertEquals(1, store.list().size)

        val viewModel = VoiceViewModel(
            authService = FakeAuthService(session()),
            voiceCaptureApi = FakeVoiceCaptureApi(),
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        viewModel.refreshRetainedRecordings()
        assertTrue(viewModel.retainedRecordings.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `ownerless recordings are treated as unsafe and not retried`() = runTest {
        val folder = tmp.newFolder("ownerless_recordings_retry")
        val store = com.cras.app.voice.DirectoryVoiceRecordingStore(folder)
        store.save(byteArrayOf(1, 2, 3), createdAtEpochMs = 1_000L)
        assertNull(store.getRecordingOwner())
        assertEquals(1, store.list().size)

        val api = FakeVoiceCaptureApi()
        val viewModel = VoiceViewModel(
            authService = FakeAuthService(session()),
            voiceCaptureApi = api,
            recordingStore = store,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        viewModel.retryProcessing()
        advanceUntilIdle()

        // Capture API was never called with the ownerless recording
        assertEquals(0, api.requests.size)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `failed operator switch A to B cleanup preserves prior owner and denies operator B access`() = runTest {
        val rec = RetainedRecording("rec-1", "rec-1.wav", 100L, 1_000L)
        var storeOwner: String? = "operator-A"
        val failingStore = object : VoiceRecordingStore {
            override fun save(wav: ByteArray, createdAtEpochMs: Long): RetainedRecording = rec
            override fun list(): List<RetainedRecording> = listOf(rec)
            override fun latest(): RetainedRecording? = rec
            override fun readBytes(id: String): ByteArray? = byteArrayOf(1, 2, 3)
            override fun delete(id: String): Boolean = false
            override fun clearAll(): Boolean = false
            override fun getRecordingOwner(): String? = storeOwner
            override fun setRecordingOwner(operatorId: String?) {
                storeOwner = operatorId
            }
        }

        val opA = OperatorSession(operatorId = "operator-A", email = "opA@example.com", accessToken = "tokenA")
        val opB = OperatorSession(operatorId = "operator-B", email = "opB@example.com", accessToken = "tokenB")
        val authService = FakeAuthService(opA)
        val api = FakeVoiceCaptureApi()

        val viewModel = VoiceViewModel(
            authService = authService,
            voiceCaptureApi = api,
            recordingStore = failingStore,
            micRecorderProvider = { FakeMicRecorder(recordingClock.value) },
            nowProvider = { recordingClock.value },
            elapsedTickerIntervalMs = 0L,
        )

        val authFlow = MutableStateFlow<AuthUiState>(AuthUiState.Authenticated(opA))
        val job = launch { viewModel.collectAuthStateAndPruneRecordings(authFlow) }
        advanceUntilIdle()

        // Switch to operator B, where cleanup fails
        authFlow.value = AuthUiState.Authenticated(opB)
        advanceUntilIdle()

        // Store owner must still be operator-A because cleanup failed
        assertEquals("operator-A", failingStore.getRecordingOwner())

        // Switch active session to opB
        authService.restoreSession(opB)

        // Operator B tries to refresh recordings -> must be empty
        viewModel.refreshRetainedRecordings()
        assertTrue(viewModel.retainedRecordings.value.isEmpty())

        // Operator B tries to retry processing -> must not send Op A's recording
        viewModel.retryProcessing()
        advanceUntilIdle()
        assertEquals(0, api.requests.size)

        job.cancel()
    }
}

private fun createDraftTaskFromExtractedForTest(
    payload: ExtractedDraftPayload,
    effectiveDefault: TimedPlanType,
    zoneId: java.time.ZoneId = java.time.ZoneId.of("UTC"),
): DraftTask = com.cras.app.voice.createDraftTaskFromExtracted(
    payload = payload,
    effectiveDefault = effectiveDefault,
    zoneId = zoneId,
)
