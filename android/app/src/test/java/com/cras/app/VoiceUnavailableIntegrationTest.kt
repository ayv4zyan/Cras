package com.cras.app

import com.cras.app.auth.AuthService
import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CommentService
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.InMemoryOutboxStore
import com.cras.app.data.LabelService
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Task
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.voice.VoiceFailure
import com.cras.app.ui.voice.VoiceUiState
import com.cras.app.ui.voice.VoiceViewModel
import com.cras.app.voice.AudioRecordingResult
import com.cras.app.voice.RecordingBuffer
import com.cras.app.voice.VoiceCaptureApi
import com.cras.app.voice.VoiceCaptureResult
import com.cras.app.voice.VoiceError
import com.cras.app.voice.VoiceMicRecorder
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Acceptance: unavailable Voice (allowance exhausted, circuit breaker tripped,
 * provider failure, missing microphone permission) never blocks ordinary Task
 * work, and ordinary Task work never disturbs Voice state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceUnavailableIntegrationTest {

    private val dispatcher = StandardTestDispatcher()

    private val session = OperatorSession(
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "op@cras.app",
        accessToken = "token"
    )

    private lateinit var authSessionFlow: MutableStateFlow<OperatorSession?>
    private val createdRecorders = mutableListOf<SilentMicRecorder>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        authSessionFlow = MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeAuthService(
        private val authenticated: OperatorSession,
        private val sessionFlow: MutableStateFlow<OperatorSession?>
    ) : AuthService {
        override val currentSession: StateFlow<OperatorSession?> = sessionFlow.asStateFlow()
        private val store = InMemorySessionStore()

        override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): OperatorSession {
            sessionFlow.value = authenticated
            return authenticated
        }

        override suspend fun restoreSession(): OperatorSession? = store.loadSession()

        override suspend fun restoreSession(session: OperatorSession): OperatorSession {
            store.saveSession(session)
            sessionFlow.value = session
            return session
        }

        override suspend fun signOut() {
            store.clearSession()
            sessionFlow.value = null
        }
    }

    /** Stores tasks handed to it, mirroring server behaviour for accepted creates. */
    private class StoringTaskService : TaskService {
        val stored = mutableListOf<Task>()

        override suspend fun fetchTasks(session: OperatorSession): List<Task> = stored.toList()
        override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? =
            stored.firstOrNull { it.id == taskId }

        override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
            val now = "2026-08-21T00:00:00Z"
            val task = Task(
                id = params.id ?: UUID.randomUUID().toString(),
                title = params.title,
                description = params.description,
                priority = params.priority,
                plan = params.plan,
                labels = params.labels.toList(),
                parentId = params.parentId,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
                version = 1
            )
            stored.add(task)
            return task
        }

        override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task =
            throw UnsupportedOperationException()

        override suspend fun completeTask(
            session: OperatorSession,
            taskId: String,
            expectedVersion: Int,
            completedAt: String?
        ): Task {
            val index = stored.indexOfFirst { it.id == taskId }
            val current = stored[index]
            val completed = current.copy(
                completedAt = completedAt ?: "2026-08-21T01:00:00Z",
                updatedAt = "2026-08-21T01:00:00Z",
                version = expectedVersion + 1
            )
            stored[index] = completed
            return completed
        }

        override suspend fun uncompleteTask(
            session: OperatorSession,
            taskId: String,
            expectedVersion: Int
        ): Task = throw UnsupportedOperationException()
    }

    private class NoopLabelService : LabelService {
        override suspend fun fetchLabels(session: OperatorSession): List<Label> = emptyList()
        override suspend fun createLabel(session: OperatorSession, params: CreateLabelParams): Label =
            throw UnsupportedOperationException()

        override suspend fun updateLabel(session: OperatorSession, params: UpdateLabelParams): Label =
            throw UnsupportedOperationException()

        override suspend fun deleteLabel(session: OperatorSession, labelId: String) {}
    }

    private class NoopCommentService : CommentService {
        override suspend fun fetchComments(session: OperatorSession, taskId: String?): List<Comment> =
            emptyList()

        override suspend fun createComment(
            session: OperatorSession,
            params: CreateCommentParams
        ): Comment = throw UnsupportedOperationException()
    }

    /** Always trips the Deployment circuit breaker. */
    private class CircuitBrokenVoiceApi : VoiceCaptureApi {
        var calls = 0
        override suspend fun sendVoiceCapture(
            session: OperatorSession,
            options: com.cras.app.voice.VoiceCaptureRequestOptions
        ): VoiceCaptureResult {
            calls += 1
            throw VoiceError(
                status = 503,
                code = "circuit_breaker_daily",
                message = "Voice capture is temporarily unavailable. Please try again later."
            )
        }
    }

    private class SilentMicRecorder : VoiceMicRecorder {
        private val buffer = RecordingBuffer(inputSampleRate = 16000)
        private val anchor: Instant = Instant.parse("2026-08-21T23:59:30Z")

        @Volatile
        private var active = false

        override var onAutoStop: (() -> Unit)? = null
        override val isRecording: Boolean get() = active
        override val startedAt: Instant? get() = if (active) anchor else null

        override fun start() {
            active = true
        }

        override fun stop(): AudioRecordingResult {
            active = false
            return buffer.build()
        }

        override fun cancel() {
            active = false
            buffer.cancel()
        }

        fun emit() {
            check(active)
            buffer.append(FloatArray(1_600))
        }
    }

    private fun buildVoiceViewModel(api: CircuitBrokenVoiceApi, tmpFolder: java.io.File): VoiceViewModel =
        VoiceViewModel(
            authService = FakeAuthService(session, authSessionFlow),
            voiceCaptureApi = api,
            recordingStore = com.cras.app.voice.DirectoryVoiceRecordingStore(tmpFolder),
            micRecorderProvider = { SilentMicRecorder().also { createdRecorders.add(it) } },
            effectiveDefaultTimedPlanTypeProvider = { com.cras.app.domain.TimedPlanType.INSTANT },
            zoneIdProvider = { java.time.ZoneId.of("UTC") },
            timezoneProvider = { "UTC" },
            micPermissionProvider = { true },
            nowProvider = { Instant.parse("2026-08-22T00:01:00Z") },
            elapsedTickerIntervalMs = 0L,
        )

    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    @Test
    fun `circuit breaker trips Voice while creating Tasks keeps working`() = runTest {
        val taskService = StoringTaskService()
        val inboxViewModel = InboxViewModel(
            authService = FakeAuthService(session, authSessionFlow),
            taskService = taskService,
            labelService = NoopLabelService(),
            commentService = NoopCommentService(),
            outboxStore = InMemoryOutboxStore(),
        )
        val brokenApi = CircuitBrokenVoiceApi()
        val voiceViewModel = buildVoiceViewModel(brokenApi, tmp.newFolder("voice"))

        authSessionFlow.value = session
        advanceUntilIdle()

        // 1. Voice capture fails on the tripped circuit breaker.
        voiceViewModel.open(focusedTask = null)
        voiceViewModel.startRecording()
        advanceUntilIdle()
        createdRecorders.last().emit()
        voiceViewModel.stopAndProcess()
        advanceUntilIdle()
        assertEquals(1, brokenApi.calls)
        assertEquals(
            VoiceFailure.CircuitBreakerTripped,
            (voiceViewModel.uiState.value as VoiceUiState.Failed).failure
        )

        // 2. Ordinary Task creation is completely unaffected.
        var createSucceeded = false
        inboxViewModel.createTask(title = "Buy milk") { createSucceeded = true }
        advanceUntilIdle()

        assertTrue(createSucceeded)
        assertEquals(listOf("Buy milk"), inboxViewModel.allTasks.value.map { it.title })

        // 3. The failed capture did not disturb Voice or Task state.
        assertEquals(
            VoiceFailure.CircuitBreakerTripped,
            (voiceViewModel.uiState.value as VoiceUiState.Failed).failure
        )

        // 4. Retrying Voice still trips the breaker while Tasks remain intact.
        voiceViewModel.retryProcessing()
        advanceUntilIdle()
        assertEquals(2, brokenApi.calls)
        assertEquals(
            VoiceFailure.CircuitBreakerTripped,
            (voiceViewModel.uiState.value as VoiceUiState.Failed).failure
        )
        assertEquals(listOf("Buy milk"), inboxViewModel.allTasks.value.map { it.title })
    }

    @Test
    fun `allowance exhaustion blocks only Voice while completing Tasks continues`() = runTest {
        val taskService = StoringTaskService()
        val inboxViewModel = InboxViewModel(
            authService = FakeAuthService(session, authSessionFlow),
            taskService = taskService,
            labelService = NoopLabelService(),
            commentService = NoopCommentService(),
            outboxStore = InMemoryOutboxStore(),
        )
        val exhaustedApi = object : VoiceCaptureApi {
            override suspend fun sendVoiceCapture(
                session: OperatorSession,
                options: com.cras.app.voice.VoiceCaptureRequestOptions
            ): VoiceCaptureResult = throw VoiceError(
                status = 429,
                code = "daily_limit_exceeded",
                message = "Daily Voice allowance reached.",
                earliestRetryAt = "2026-08-22T06:00:00Z",
                retryAfterSeconds = 21600,
            )
        }
        val voiceViewModel = VoiceViewModel(
            authService = FakeAuthService(session, authSessionFlow),
            voiceCaptureApi = exhaustedApi,
            recordingStore = com.cras.app.voice.DirectoryVoiceRecordingStore(tmp.newFolder("voice2")),
            micRecorderProvider = { SilentMicRecorder().also { createdRecorders.add(it) } },
            effectiveDefaultTimedPlanTypeProvider = { com.cras.app.domain.TimedPlanType.INSTANT },
            zoneIdProvider = { java.time.ZoneId.of("UTC") },
            timezoneProvider = { "UTC" },
            micPermissionProvider = { true },
            elapsedTickerIntervalMs = 0L,
        )

        authSessionFlow.value = session
        advanceUntilIdle()

        // Create and accept a Task first.
        inboxViewModel.createTask(title = "Walk dog")
        advanceUntilIdle()
        val created = inboxViewModel.allTasks.value.single()

        // Voice hits the allowance wall.
        voiceViewModel.open(focusedTask = null)
        voiceViewModel.startRecording()
        advanceUntilIdle()
        createdRecorders.last().emit()
        voiceViewModel.stopAndProcess()
        advanceUntilIdle()

        val failed = voiceViewModel.uiState.value as VoiceUiState.Failed
        assertEquals(
            VoiceFailure.AllowanceExhausted(
                earliestRetryAt = "2026-08-22T06:00:00Z",
                retryAfterSeconds = 21600,
            ),
            failed.failure
        )
        assertTrue(failed.canRetryWithSavedAudio)

        // Ordinary completion still flows through the Outbox untouched.
        inboxViewModel.completeTask(created.id)
        advanceUntilIdle()

        assertTrue(inboxViewModel.allTasks.value.single().completedAt != null)
        assertEquals(
            VoiceFailure.AllowanceExhausted::class,
            voiceViewModel.uiState.value.let { it as? VoiceUiState.Failed }?.failure?.let { it::class }
        )
    }

    @Test
    fun `missing microphone permission disables Voice but not Task work`() = runTest {
        val taskService = StoringTaskService()
        val inboxViewModel = InboxViewModel(
            authService = FakeAuthService(session, authSessionFlow),
            taskService = taskService,
            labelService = NoopLabelService(),
            commentService = NoopCommentService(),
            outboxStore = InMemoryOutboxStore(),
        )
        val voiceViewModel = VoiceViewModel(
            authService = FakeAuthService(session, authSessionFlow),
            voiceCaptureApi = CircuitBrokenVoiceApi(),
            recordingStore = com.cras.app.voice.DirectoryVoiceRecordingStore(tmp.newFolder("voice3")),
            micRecorderProvider = { SilentMicRecorder() },
            effectiveDefaultTimedPlanTypeProvider = { com.cras.app.domain.TimedPlanType.INSTANT },
            zoneIdProvider = { java.time.ZoneId.of("UTC") },
            timezoneProvider = { "UTC" },
            micPermissionProvider = { false },
            elapsedTickerIntervalMs = 0L,
        )

        authSessionFlow.value = session
        advanceUntilIdle()

        voiceViewModel.open(focusedTask = null)
        voiceViewModel.startRecording()
        advanceUntilIdle()

        assertEquals(
            VoiceFailure.MicPermissionMissing,
            (voiceViewModel.uiState.value as VoiceUiState.Failed).failure
        )

        var createSucceeded = false
        inboxViewModel.createTask(title = "Post letter") { createSucceeded = true }
        advanceUntilIdle()

        assertTrue(createSucceeded)
        assertEquals(listOf("Post letter"), inboxViewModel.allTasks.value.map { it.title })
        assertEquals(
            VoiceFailure.MicPermissionMissing,
            (voiceViewModel.uiState.value as VoiceUiState.Failed).failure
        )
    }
}
