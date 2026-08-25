package com.cras.app.ui.inbox

import com.cras.app.auth.AuthService
import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.data.AccountDeletionState
import com.cras.app.data.AccountLifecycleException
import com.cras.app.data.AccountService
import com.cras.app.data.AccountStatus
import com.cras.app.data.CommentService
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.DeletionConfirmation
import com.cras.app.data.InMemoryOutboxStore
import com.cras.app.data.LabelService
import com.cras.app.data.OutboxItem
import com.cras.app.data.RealtimeService
import com.cras.app.data.SettingsService
import com.cras.app.data.TaskService
import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Task
import com.cras.app.notification.AndroidNotificationStatus
import com.cras.app.notification.InMemoryNotificationPreferenceStore
import com.cras.app.notification.NotificationInstallationSync
import com.cras.app.notification.PlatformPermissionState
import com.cras.app.voice.VoiceRecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelAccountLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeAuthService(initialSession: OperatorSession? = null) : AuthService {
        private val _currentSession = MutableStateFlow<OperatorSession?>(initialSession)
        override val currentSession = _currentSession.asStateFlow()

        override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): OperatorSession {
            val session = OperatorSession(
                operatorId = "550e8400-e29b-41d4-a716-446655440001",
                email = "operator@cras.app",
                accessToken = "new-jwt-token"
            )
            _currentSession.value = session
            return session
        }

        override suspend fun restoreSession(): OperatorSession? = _currentSession.value

        override suspend fun signOut() {
            _currentSession.value = null
        }
    }

    private class FakeAccountService : AccountService {
        var statusToReturn = AccountStatus(AccountDeletionState.ACTIVE, null, false)
        var deletionConfirmationToReturn = DeletionConfirmation(true, AccountDeletionState.PENDING_DELETION, "2026-08-31T12:00:00Z", true)
        var exportDataToReturn = """{"exportedAt":"2026-08-25T10:00:00Z","tasks":[]}"""
        var recoverShouldFail = false
        var recoverCalled = false
        var deleteCalled = false
        var exportCalled = false

        override suspend fun fetchAccountStatus(session: OperatorSession): AccountStatus = statusToReturn

        override suspend fun requestAccountDeletion(session: OperatorSession): DeletionConfirmation {
            deleteCalled = true
            return deletionConfirmationToReturn
        }

        override suspend fun recoverAccount(session: OperatorSession) {
            recoverCalled = true
            if (recoverShouldFail) {
                throw AccountLifecycleException("Recovery window has closed", 403, "recovery_window_closed")
            }
            statusToReturn = AccountStatus(AccountDeletionState.ACTIVE, null, false)
        }

        override suspend fun exportOperatorData(session: OperatorSession): String {
            exportCalled = true
            return exportDataToReturn
        }
    }

    private class FakeTaskService : TaskService {
        val tasks = mutableListOf<Task>()
        override suspend fun fetchTasks(session: OperatorSession): List<Task> = tasks.toList()
        override suspend fun fetchTaskById(session: OperatorSession, id: String): Task? = tasks.find { it.id == id }
        override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
            val task = Task(
                id = params.id ?: UUID.randomUUID().toString(),
                title = params.title,
                description = params.description,
                priority = params.priority,
                plan = params.plan,
                labels = params.labels,
                parentId = params.parentId,
                completedAt = null,
                createdAt = "2026-08-25T10:00:00Z",
                updatedAt = "2026-08-25T10:00:00Z",
                version = 1
            )
            tasks.add(task)
            return task
        }
        override suspend fun updateTask(session: OperatorSession, params: com.cras.app.data.UpdateTaskParams): Task = tasks.first()
        override suspend fun completeTask(session: OperatorSession, taskId: String, expectedVersion: Int, completedAt: String?): Task = tasks.first()
        override suspend fun uncompleteTask(session: OperatorSession, taskId: String, expectedVersion: Int): Task = tasks.first()
    }

    private class FakeLabelService : LabelService {
        override suspend fun fetchLabels(session: OperatorSession) = emptyList<Label>()
        override suspend fun createLabel(session: OperatorSession, params: com.cras.app.data.CreateLabelParams) = throw NotImplementedError()
        override suspend fun updateLabel(session: OperatorSession, params: com.cras.app.data.UpdateLabelParams) = throw NotImplementedError()
        override suspend fun deleteLabel(session: OperatorSession, id: String) = throw NotImplementedError()
    }

    private class FakeCommentService : CommentService {
        override suspend fun fetchComments(session: OperatorSession, taskId: String?) = emptyList<Comment>()
        override suspend fun createComment(session: OperatorSession, params: com.cras.app.data.CreateCommentParams) = throw NotImplementedError()
    }

    private class FakeVoiceRecordingStore : VoiceRecordingStore {
        var clearCount = 0
        override fun save(wav: ByteArray, createdAtEpochMs: Long) = throw NotImplementedError()
        override fun list() = emptyList<com.cras.app.voice.RetainedRecording>()
        override fun latest() = null
        override fun readBytes(id: String) = null
        override fun delete(id: String) {}
        override fun clearAll() {
            clearCount++
        }
    }

    private lateinit var authService: FakeAuthService
    private lateinit var accountService: FakeAccountService
    private lateinit var taskService: FakeTaskService
    private lateinit var outboxStore: InMemoryOutboxStore
    private lateinit var voiceRecordingStore: FakeVoiceRecordingStore
    private lateinit var viewModel: InboxViewModel

    private val session = OperatorSession(
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "operator@cras.app",
        accessToken = "test-session-jwt"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authService = FakeAuthService()
        accountService = FakeAccountService()
        taskService = FakeTaskService()
        outboxStore = InMemoryOutboxStore()
        voiceRecordingStore = FakeVoiceRecordingStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): InboxViewModel {
        return InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = FakeLabelService(),
            commentService = FakeCommentService(),
            accountService = accountService,
            outboxStore = outboxStore,
            voiceRecordingStore = voiceRecordingStore,
            nowProvider = { Instant.parse("2026-08-25T10:00:00Z") },
            zoneIdProvider = { ZoneId.of("UTC") }
        )
    }

    @Test
    fun `observing pending deletion clears local cache, outbox, and voice recordings and presents frozen status`() = runTest {
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )

        val taskId = "550e8400-e29b-41d4-a716-446655440011"
        val testTask = Task(
            id = taskId,
            title = "Offline task",
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = null,
            completedAt = null,
            createdAt = "2026-08-25T09:00:00Z",
            updatedAt = "2026-08-25T09:00:00Z",
            version = 1
        )
        outboxStore.enqueue(
            session.operatorId,
            OutboxItem.Create(
                id = taskId,
                task = testTask,
                params = CreateTaskParams(id = taskId, title = "Offline task"),
                createdAt = "2026-08-25T09:00:00Z"
            )
        )

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        val status = viewModel.accountStatus.value
        assertNotNull(status)
        assertEquals(AccountDeletionState.PENDING_DELETION, status?.deletionState)
        assertEquals("2026-08-31T12:00:00Z", status?.deletionDeadline)
        assertTrue(status?.recoveryAvailable == true)

        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())
        assertEquals(1, voiceRecordingStore.clearCount)
        assertTrue(viewModel.allTasks.value.isEmpty())
    }

    @Test
    fun `requestAccountDeletion confirms deletion, wipes local data and signs out`() = runTest {
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        val taskId = "550e8400-e29b-41d4-a716-446655440022"
        val testTask = Task(
            id = taskId,
            title = "Active task",
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = null,
            completedAt = null,
            createdAt = "2026-08-25T09:00:00Z",
            updatedAt = "2026-08-25T09:00:00Z",
            version = 1
        )
        outboxStore.enqueue(
            session.operatorId,
            OutboxItem.Create(
                id = taskId,
                task = testTask,
                params = CreateTaskParams(id = taskId, title = "Active task"),
                createdAt = "2026-08-25T09:00:00Z"
            )
        )

        var confirmedResult: DeletionConfirmation? = null
        viewModel.requestAccountDeletion(
            onSuccess = { confirmedResult = it },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(accountService.deleteCalled)
        assertNotNull(confirmedResult)
        assertEquals(AccountDeletionState.PENDING_DELETION, confirmedResult?.deletionState)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())
        assertTrue(voiceRecordingStore.clearCount >= 1)
        assertNull(authService.currentSession.value)
    }

    @Test
    fun `exportOperatorData delegates to AccountService`() = runTest {
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        var exportOutput: String? = null
        viewModel.exportOperatorData(
            onSuccess = { exportOutput = it },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(accountService.exportCalled)
        assertEquals("""{"exportedAt":"2026-08-25T10:00:00Z","tasks":[]}""", exportOutput)
    }

    @Test
    fun `recoverAccount restores active state and reloads canonical tasks`() = runTest {
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)

        taskService.tasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440033",
                title = "Restored Task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-25T09:00:00Z",
                updatedAt = "2026-08-25T09:00:00Z",
                version = 1
            )
        )

        var recoverySuccess = false
        viewModel.recoverAccount(
            onSuccess = { recoverySuccess = true },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(accountService.recoverCalled)
        assertTrue(recoverySuccess)
        assertEquals(AccountDeletionState.ACTIVE, viewModel.accountStatus.value?.deletionState)
        assertEquals(1, viewModel.allTasks.value.size)
        assertEquals("Restored Task", viewModel.allTasks.value.first().title)
    }

    @Test
    fun `recoverAccount handles refusal after deadline`() = runTest {
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-25T09:00:00Z",
            recoveryAvailable = false
        )
        accountService.recoverShouldFail = true

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        var errorReceived: String? = null
        viewModel.recoverAccount(
            onSuccess = {},
            onError = { errorReceived = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.recoverCalled)
        assertNotNull(errorReceived)
        assertTrue(errorReceived!!.contains("Recovery window has closed"))
        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)
    }
}
