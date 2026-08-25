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
import com.cras.app.data.InstallationRecord
import com.cras.app.data.InstallationService
import com.cras.app.data.InvalidationPayload
import com.cras.app.data.LabelService
import com.cras.app.data.OperatorSettings
import com.cras.app.data.OutboxItem
import com.cras.app.data.RealtimeService
import com.cras.app.data.RealtimeSubscription
import com.cras.app.data.RegisterInstallationParams
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

        fun setSession(session: OperatorSession?) {
            _currentSession.value = session
        }

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

        override suspend fun restoreSession(session: OperatorSession): OperatorSession {
            _currentSession.value = session
            return session
        }

        override suspend fun signOut() {
            _currentSession.value = null
        }
    }

    private class FakeAccountService : AccountService {
        var statusToReturn = AccountStatus(AccountDeletionState.ACTIVE, null, false)
        var deletionConfirmationToReturn = DeletionConfirmation(true, AccountDeletionState.PENDING_DELETION, "2026-08-31T12:00:00Z", true)
        var exportDataToReturn = """{"exportedAt":"2026-08-25T10:00:00Z","tasks":[]}"""
        var fetchShouldFail = false
        var fetchExceptionToThrow: Exception? = null
        var fetchCalled = false
        var onFetchCallback: (suspend () -> Unit)? = null
        var recoverShouldFail = false
        var recoverCalled = false
        var onRecoverCallback: (suspend () -> Unit)? = null
        var deleteCalled = false
        var onDeleteCallback: (suspend () -> Unit)? = null
        var exportCalled = false

        override suspend fun fetchAccountStatus(session: OperatorSession): AccountStatus {
            fetchCalled = true
            onFetchCallback?.invoke()
            if (fetchShouldFail) {
                throw fetchExceptionToThrow ?: RuntimeException("Network error fetching status")
            }
            return statusToReturn
        }

        override suspend fun requestAccountDeletion(session: OperatorSession): DeletionConfirmation {
            deleteCalled = true
            onDeleteCallback?.invoke()
            return deletionConfirmationToReturn
        }

        override suspend fun recoverAccount(session: OperatorSession) {
            recoverCalled = true
            onRecoverCallback?.invoke()
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
        val tasksByOperator = mutableMapOf<String, MutableList<Task>>()
        var fetchTasksCalled = false
        var onFetchTasksCallback: (suspend () -> Unit)? = null
        var onFetchTaskByIdCallback: (suspend (String) -> Unit)? = null
        override suspend fun fetchTasks(session: OperatorSession): List<Task> {
            fetchTasksCalled = true
            onFetchTasksCallback?.invoke()
            return tasksByOperator[session.operatorId]?.toList() ?: tasks.toList()
        }
        override suspend fun fetchTaskById(session: OperatorSession, id: String): Task? {
            onFetchTaskByIdCallback?.invoke(id)
            return tasksByOperator[session.operatorId]?.find { it.id == id } ?: tasks.find { it.id == id }
        }
        var shouldFailWithNetworkError = false
        override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
            if (shouldFailWithNetworkError) throw java.io.IOException("Network error")
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
            tasksByOperator.getOrPut(session.operatorId) { mutableListOf() }.add(task)
            return task
        }
        var onUpdateCallback: (suspend () -> Unit)? = null
        override suspend fun updateTask(session: OperatorSession, params: com.cras.app.data.UpdateTaskParams): Task {
            if (shouldFailWithNetworkError) throw java.io.IOException("Network error")
            onUpdateCallback?.invoke()
            val list = tasksByOperator[session.operatorId] ?: tasks
            val existing = list.first { it.id == params.id }
            val updated = existing.copy(
                title = params.title ?: existing.title,
                description = if (params.description != null) params.description else existing.description,
                priority = params.priority ?: existing.priority,
                version = existing.version + 1
            )
            val index = list.indexOfFirst { it.id == params.id }
            if (index != -1) list[index] = updated
            val globalIndex = tasks.indexOfFirst { it.id == params.id }
            if (globalIndex != -1) tasks[globalIndex] = updated
            return updated
        }
        override suspend fun completeTask(session: OperatorSession, taskId: String, expectedVersion: Int, completedAt: String?): Task {
            if (shouldFailWithNetworkError) throw java.io.IOException("Network error")
            val list = tasksByOperator[session.operatorId] ?: tasks
            val existing = list.firstOrNull { it.id == taskId } ?: tasks.first { it.id == taskId }
            val completed = existing.copy(
                completedAt = completedAt ?: "2026-08-25T10:00:00Z",
                updatedAt = "2026-08-25T10:00:00Z",
                version = existing.version + 1
            )
            val index = list.indexOfFirst { it.id == taskId }
            if (index != -1) list[index] = completed
            val globalIndex = tasks.indexOfFirst { it.id == taskId }
            if (globalIndex != -1) tasks[globalIndex] = completed
            return completed
        }
        override suspend fun uncompleteTask(session: OperatorSession, taskId: String, expectedVersion: Int): Task {
            if (shouldFailWithNetworkError) throw java.io.IOException("Network error")
            val list = tasksByOperator[session.operatorId] ?: tasks
            val existing = list.firstOrNull { it.id == taskId } ?: tasks.first { it.id == taskId }
            val uncompleted = existing.copy(
                completedAt = null,
                updatedAt = "2026-08-25T10:00:00Z",
                version = existing.version + 1
            )
            val index = list.indexOfFirst { it.id == taskId }
            if (index != -1) list[index] = uncompleted
            val globalIndex = tasks.indexOfFirst { it.id == taskId }
            if (globalIndex != -1) tasks[globalIndex] = uncompleted
            return uncompleted
        }
    }

    private class FakeLabelService : LabelService {
        val labels = mutableListOf<Label>()
        val labelsByOperator = mutableMapOf<String, MutableList<Label>>()
        var onActionCallback: (suspend () -> Unit)? = null
        var onFetchCallback: (suspend () -> Unit)? = null
        override suspend fun fetchLabels(session: OperatorSession): List<Label> {
            onFetchCallback?.invoke()
            return labelsByOperator[session.operatorId]?.toList() ?: labels.toList()
        }
        override suspend fun createLabel(session: OperatorSession, params: com.cras.app.data.CreateLabelParams): Label {
            onActionCallback?.invoke()
            val label = Label(id = UUID.randomUUID().toString(), name = params.name, color = params.color)
            labels.add(label)
            labelsByOperator.getOrPut(session.operatorId) { mutableListOf() }.add(label)
            return label
        }
        override suspend fun updateLabel(session: OperatorSession, params: com.cras.app.data.UpdateLabelParams): Label {
            onActionCallback?.invoke()
            val label = Label(id = params.id, name = params.name ?: "Updated", color = params.color ?: "#000000")
            val list = labelsByOperator[session.operatorId] ?: labels
            val index = list.indexOfFirst { it.id == params.id }
            if (index != -1) list[index] = label else list.add(label)
            val globalIndex = labels.indexOfFirst { it.id == params.id }
            if (globalIndex != -1) labels[globalIndex] = label else labels.add(label)
            return label
        }
        override suspend fun deleteLabel(session: OperatorSession, id: String) {
            onActionCallback?.invoke()
            labels.removeAll { it.id == id }
            labelsByOperator[session.operatorId]?.removeAll { it.id == id }
        }
    }

    private class FakeCommentService : CommentService {
        val comments = mutableListOf<Comment>()
        val commentsByOperator = mutableMapOf<String, MutableList<Comment>>()
        var onActionCallback: (suspend () -> Unit)? = null
        var onFetchCallback: (suspend () -> Unit)? = null
        override suspend fun fetchComments(session: OperatorSession, taskId: String?): List<Comment> {
            onFetchCallback?.invoke()
            val list = commentsByOperator[session.operatorId]?.toList() ?: comments.toList()
            return if (taskId != null) list.filter { it.taskId == taskId } else list
        }
        override suspend fun createComment(session: OperatorSession, params: com.cras.app.data.CreateCommentParams): Comment {
            onActionCallback?.invoke()
            val comment = Comment(
                id = UUID.randomUUID().toString(),
                taskId = params.taskId,
                content = params.content,
                createdAt = "2026-08-25T10:00:00Z"
            )
            comments.add(comment)
            commentsByOperator.getOrPut(session.operatorId) { mutableListOf() }.add(comment)
            return comment
        }
    }

    private class FakeSettingsService : SettingsService {
        var timedPlanType: TimedPlanType? = null
        val timedPlanTypeByOperator = mutableMapOf<String, TimedPlanType?>()
        var onActionCallback: (suspend () -> Unit)? = null
        override suspend fun fetchOperatorSettings(session: OperatorSession): OperatorSettings? {
            val type = if (timedPlanTypeByOperator.containsKey(session.operatorId)) timedPlanTypeByOperator[session.operatorId] else timedPlanType
            return type?.let { OperatorSettings(session.operatorId, defaultTimedPlanType = it) }
        }
        override suspend fun fetchDeploymentConfig(session: OperatorSession) = null
        override suspend fun fetchEffectiveTimedPlanType(session: OperatorSession): TimedPlanType {
            val type = if (timedPlanTypeByOperator.containsKey(session.operatorId)) timedPlanTypeByOperator[session.operatorId] else timedPlanType
            return type ?: TimedPlanType.INSTANT
        }
        override suspend fun updateOperatorTimedPlanType(session: OperatorSession, type: TimedPlanType?) {
            onActionCallback?.invoke()
            if (timedPlanTypeByOperator.isNotEmpty()) {
                timedPlanTypeByOperator[session.operatorId] = type
            } else {
                timedPlanType = type
            }
        }
    }

    private class FakeVoiceRecordingStore : VoiceRecordingStore {
        var clearCount = 0
        override fun save(wav: ByteArray, createdAtEpochMs: Long) = throw NotImplementedError()
        override fun list() = emptyList<com.cras.app.voice.RetainedRecording>()
        override fun latest() = null
        override fun readBytes(id: String) = null
        override fun delete(id: String) {
            // No retained recordings in this fake; deletion is a no-op.
        }
        override fun clearAll() {
            clearCount++
        }
    }

    private class FakeRealtimeService : RealtimeService {
        var onInvalidateCallback: ((InvalidationPayload) -> Unit)? = null
        var onReconnectCallback: (() -> Unit)? = null
        var isSubscribed = false
        var unsubscribeCount = 0

        override fun subscribeToInvalidations(
            session: OperatorSession,
            onInvalidate: (InvalidationPayload) -> Unit,
            onReconnect: (() -> Unit)?
        ): RealtimeSubscription {
            onInvalidateCallback = onInvalidate
            onReconnectCallback = onReconnect
            isSubscribed = true

            return object : RealtimeSubscription {
                override fun unsubscribe() {
                    isSubscribed = false
                    unsubscribeCount++
                    onInvalidateCallback = null
                    onReconnectCallback = null
                }
            }
        }

        fun emitInvalidate(payload: InvalidationPayload) {
            onInvalidateCallback?.invoke(payload)
        }
    }

    private class FakeInstallationService : InstallationService {
        val deactivatedIds = mutableListOf<String>()
        var onDeactivateCallback: (() -> Unit)? = null

        override suspend fun registerOrUpdate(
            session: OperatorSession,
            params: RegisterInstallationParams
        ) = InstallationRecord(
            id = params.id,
            platform = "android",
            localEnabled = params.localEnabled,
            permissionState = params.permissionState,
            endpoint = params.endpoint,
            isActive = true
        )

        override suspend fun deactivate(session: OperatorSession, installationId: String): Boolean {
            deactivatedIds.add(installationId)
            onDeactivateCallback?.invoke()
            return true
        }
    }

    private lateinit var authService: FakeAuthService
    private lateinit var accountService: FakeAccountService
    private lateinit var taskService: FakeTaskService
    private lateinit var labelService: FakeLabelService
    private lateinit var commentService: FakeCommentService
    private lateinit var settingsService: FakeSettingsService
    private lateinit var outboxStore: InMemoryOutboxStore
    private lateinit var voiceRecordingStore: FakeVoiceRecordingStore
    private lateinit var realtimeService: FakeRealtimeService
    private lateinit var installationService: FakeInstallationService
    private lateinit var installationPreferenceStore: InMemoryNotificationPreferenceStore
    private lateinit var installationSync: NotificationInstallationSync
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
        labelService = FakeLabelService()
        commentService = FakeCommentService()
        settingsService = FakeSettingsService()
        outboxStore = InMemoryOutboxStore()
        voiceRecordingStore = FakeVoiceRecordingStore()
        realtimeService = FakeRealtimeService()
        installationService = FakeInstallationService()
        installationPreferenceStore = InMemoryNotificationPreferenceStore()
        installationSync = NotificationInstallationSync(
            installationService = installationService,
            preferences = installationPreferenceStore,
            permissionProvider = { PlatformPermissionState.GRANTED },
            fcmTokenProvider = { "test-fcm-token" }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): InboxViewModel {
        return InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            accountService = accountService,
            realtimeService = realtimeService,
            installationSync = installationSync,
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
        assertFalse(realtimeService.isSubscribed)
        assertEquals(1, installationService.deactivatedIds.size)
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, installationSync.status.value)

        // Emitting an invalidation while frozen must not repopulate the task cache
        taskService.tasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440088",
                title = "Repopulate attempt",
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
        realtimeService.emitInvalidate(
            InvalidationPayload(resource = "task", operation = "created", id = "550e8400-e29b-41d4-a716-446655440088")
        )
        advanceUntilIdle()
        assertTrue(viewModel.allTasks.value.isEmpty())
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
    }

    @Test
    fun `manual fetchAccountStatus observing pending deletion clears local data, unsubscribes realtime, deactivates installation, and blocks invalidations`() = runTest {
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.ACTIVE,
            deletionDeadline = null,
            recoveryAvailable = false
        )
        val initialTask = Task(
            id = "550e8400-e29b-41d4-a716-446655440011",
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
        taskService.tasks.add(initialTask)

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(1, viewModel.allTasks.value.size)
        assertTrue(realtimeService.isSubscribed)
        assertEquals(0, installationService.deactivatedIds.size)
        assertEquals(AndroidNotificationStatus.Enabled, installationSync.status.value)

        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )

        var successStatus: AccountStatus? = null
        viewModel.fetchAccountStatus(
            onSuccess = { successStatus = it },
            onError = {}
        )
        advanceUntilIdle()

        assertEquals(AccountDeletionState.PENDING_DELETION, successStatus?.deletionState)
        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)
        assertTrue(viewModel.allTasks.value.isEmpty())
        assertFalse(realtimeService.isSubscribed)
        assertEquals(1, installationService.deactivatedIds.size)
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, installationSync.status.value)

        // Invalidation and loadTasks must not repopulate tasks
        taskService.tasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440099",
                title = "New task",
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
        realtimeService.emitInvalidate(
            InvalidationPayload(resource = "task", operation = "created", id = "550e8400-e29b-41d4-a716-446655440099")
        )
        viewModel.loadTasks()
        advanceUntilIdle()

        assertTrue(viewModel.allTasks.value.isEmpty())
    }

    @Test
    fun `account and task mutations are blocked while account deletion is pending`() = runTest {
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        var createError: String? = null
        viewModel.createTask(
            title = "New Task",
            onError = { createError = it }
        )
        advanceUntilIdle()
        assertEquals("Account deletion is pending", createError)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())

        var completeError: String? = null
        viewModel.completeTask(
            taskId = "550e8400-e29b-41d4-a716-446655440011",
            onError = { completeError = it }
        )
        advanceUntilIdle()
        assertEquals("Account deletion is pending", completeError)

        var labelError: String? = null
        viewModel.createLabel(
            name = "Work",
            color = "#ff0000",
            onError = { labelError = it }
        )
        advanceUntilIdle()
        assertEquals("Account deletion is pending", labelError)

        var commentError: String? = null
        viewModel.createComment(
            taskId = "550e8400-e29b-41d4-a716-446655440011",
            content = "Comment",
            onError = { commentError = it }
        )
        advanceUntilIdle()
        assertEquals("Account deletion is pending", commentError)
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
    fun `requestAccountDeletion with unconfirmed response leaves local data intact and reports error`() = runTest {
        accountService.deletionConfirmationToReturn = DeletionConfirmation(
            confirmed = false,
            deletionState = AccountDeletionState.ACTIVE,
            deletionDeadline = null,
            sessionsRevoked = false
        )

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
        var errorMessage: String? = null
        viewModel.requestAccountDeletion(
            onSuccess = { confirmedResult = it },
            onError = { errorMessage = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.deleteCalled)
        assertNull(confirmedResult)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Account deletion was not confirmed"))
        assertEquals(1, outboxStore.getOutbox(session.operatorId).size)
        assertEquals(0, voiceRecordingStore.clearCount)
        assertNotNull(authService.currentSession.value)
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

    @Test
    fun `recoverAccount ignores outcome and does not activate session if user signed out in flight`() = runTest {
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        accountService.onRecoverCallback = {
            authService.signOut()
        }

        var recoverySuccess = false
        var errorReceived: String? = null
        viewModel.recoverAccount(
            onSuccess = { recoverySuccess = true },
            onError = { errorReceived = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.recoverCalled)
        assertFalse(recoverySuccess)
        assertNull(errorReceived)
        assertNull(viewModel.accountStatus.value)
        assertTrue(viewModel.authState.value is AuthUiState.Unauthenticated)
    }

    @Test
    fun `fetchAccountStatus ignores outcome and does not wipe local data if user signed out in flight`() = runTest {
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        val taskId = "550e8400-e29b-41d4-a716-446655440055"
        val testTask = Task(
            id = taskId,
            title = "Retained task",
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
                params = CreateTaskParams(id = taskId, title = "Retained task"),
                createdAt = "2026-08-25T09:00:00Z"
            )
        )

        accountService.onFetchCallback = {
            authService.signOut()
        }

        var fetchSuccess = false
        var errorReceived: String? = null
        viewModel.fetchAccountStatus(
            onSuccess = { fetchSuccess = true },
            onError = { errorReceived = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.fetchCalled)
        assertFalse(fetchSuccess)
        assertNull(errorReceived)
        assertNull(viewModel.accountStatus.value)
        assertEquals(1, outboxStore.getOutbox(session.operatorId).size)
    }

    @Test
    fun `fetchAccountStatus ignores outcome and does not overwrite new session if switched in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        accountService.onFetchCallback = {
            accountService.onFetchCallback = null
            accountService.statusToReturn = AccountStatus(AccountDeletionState.ACTIVE, null, false)
            authService.setSession(sessionB)
        }

        var fetchSuccessA = false
        var errorReceivedA: String? = null
        viewModel.fetchAccountStatus(
            onSuccess = { fetchSuccessA = true },
            onError = { errorReceivedA = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.fetchCalled)
        assertFalse(fetchSuccessA)
        assertNull(errorReceivedA)
        assertEquals(AccountDeletionState.ACTIVE, viewModel.accountStatus.value?.deletionState)
        assertEquals(sessionB, authService.currentSession.value)
        assertEquals(sessionB, (viewModel.authState.value as? AuthUiState.Authenticated)?.session)
    }

    @Test
    fun `requestAccountDeletion ignores outcome and does not wipe local data or sign out new session if user signed out in flight`() = runTest {
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        val taskId = "550e8400-e29b-41d4-a716-446655440077"
        val testTask = Task(
            id = taskId,
            title = "Task",
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
                params = CreateTaskParams(id = taskId, title = "Task"),
                createdAt = "2026-08-25T09:00:00Z"
            )
        )

        accountService.onDeleteCallback = {
            authService.signOut()
        }

        var deleteSuccess = false
        var errorReceived: String? = null
        viewModel.requestAccountDeletion(
            onSuccess = { deleteSuccess = true },
            onError = { errorReceived = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.deleteCalled)
        assertFalse(deleteSuccess)
        assertNull(errorReceived)
        assertEquals(1, outboxStore.getOutbox(session.operatorId).size)
    }

    @Test
    fun `requestAccountDeletion does not wipe new session data or sign out new session if account switched in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        accountService.onDeleteCallback = {
            accountService.onDeleteCallback = null
            authService.setSession(sessionB)
        }

        var deleteSuccess = false
        var errorReceived: String? = null
        viewModel.requestAccountDeletion(
            onSuccess = { deleteSuccess = true },
            onError = { errorReceived = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.deleteCalled)
        assertFalse(deleteSuccess)
        assertNull(errorReceived)
        assertEquals(sessionB, authService.currentSession.value)
        assertEquals(sessionB, (viewModel.authState.value as? AuthUiState.Authenticated)?.session)
    }

    @Test
    fun `checkAccountStatusInternal status-fetch failure fails closed and sets error state without starting authenticated services`() = runTest {
        accountService.fetchShouldFail = true
        accountService.fetchExceptionToThrow = RuntimeException("503 Service Unavailable")

        taskService.tasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440099",
                title = "Should Not Load",
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

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(accountService.fetchCalled)
        assertFalse(taskService.fetchTasksCalled)
        assertTrue(viewModel.allTasks.value.isEmpty())
        assertNull(viewModel.accountStatus.value)
        assertTrue(viewModel.inboxState.value is InboxUiState.Error)
        assertEquals("503 Service Unavailable", (viewModel.inboxState.value as InboxUiState.Error).message)
        assertTrue(viewModel.todayState.value is TodayUiState.Error)
        assertTrue(viewModel.upcomingState.value is UpcomingUiState.Error)
        assertTrue(viewModel.completedState.value is CompletedUiState.Error)
    }

    @Test
    fun `checkAccountStatusInternal does not apply status or start session if user signed out in flight`() = runTest {
        accountService.onFetchCallback = {
            authService.signOut()
        }

        taskService.tasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440099",
                title = "Should Not Load",
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

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(accountService.fetchCalled)
        assertFalse(taskService.fetchTasksCalled)
        assertTrue(viewModel.allTasks.value.isEmpty())
        assertNull(viewModel.accountStatus.value)
        assertTrue(viewModel.authState.value is AuthUiState.Unauthenticated)
    }

    @Test
    fun `createLabel ignores outcome and does not write state or invoke success if session changed or deleted in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        val sessionBLabel = Label(id = "550e8400-e29b-41d4-a716-446655440088", name = "Operator B Label", color = "#00ff00")
        labelService.labelsByOperator[session.operatorId] = mutableListOf()
        labelService.labelsByOperator[sessionB.operatorId] = mutableListOf(sessionBLabel)

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        labelService.onActionCallback = {
            labelService.onActionCallback = null
            authService.setSession(sessionB)
        }

        var labelCreated: Label? = null
        var labelError: String? = null
        viewModel.createLabel(
            name = "Urgent",
            color = "#ff0000",
            onSuccess = { labelCreated = it },
            onError = { labelError = it }
        )
        advanceUntilIdle()

        assertNull(labelCreated)
        assertNull(labelError)
        assertEquals(listOf(sessionBLabel), viewModel.labels.value)
    }

    @Test
    fun `updateLabel ignores outcome and does not write state or invoke success if session changed or deleted in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        val sessionBLabel = Label(id = "550e8400-e29b-41d4-a716-446655440088", name = "Operator B Label", color = "#00ff00")
        val labelId = "550e8400-e29b-41d4-a716-446655440091"
        val initialLabel = Label(id = labelId, name = "Initial", color = "#000000")
        labelService.labelsByOperator[session.operatorId] = mutableListOf(initialLabel)
        labelService.labelsByOperator[sessionB.operatorId] = mutableListOf(sessionBLabel)

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        labelService.onActionCallback = {
            labelService.onActionCallback = null
            authService.setSession(sessionB)
        }

        var labelUpdated: Label? = null
        var updateError: String? = null
        viewModel.updateLabel(
            id = labelId,
            name = "Renamed",
            onSuccess = { labelUpdated = it },
            onError = { updateError = it }
        )
        advanceUntilIdle()

        assertNull(labelUpdated)
        assertNull(updateError)
        assertEquals(listOf(sessionBLabel), viewModel.labels.value)
    }

    @Test
    fun `deleteLabel ignores outcome and does not write state or invoke success if session changed or deleted in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        val sessionBLabel = Label(id = "550e8400-e29b-41d4-a716-446655440088", name = "Operator B Label", color = "#00ff00")
        val labelId = "550e8400-e29b-41d4-a716-446655440092"
        val toDelete = Label(id = labelId, name = "ToDelete", color = "#000000")
        labelService.labelsByOperator[session.operatorId] = mutableListOf(toDelete)
        labelService.labelsByOperator[sessionB.operatorId] = mutableListOf(sessionBLabel)

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        labelService.onActionCallback = {
            labelService.onActionCallback = null
            authService.setSession(sessionB)
        }

        var deleteSuccess = false
        var deleteError: String? = null
        viewModel.deleteLabel(
            id = labelId,
            onSuccess = { deleteSuccess = true },
            onError = { deleteError = it }
        )
        advanceUntilIdle()

        assertFalse(deleteSuccess)
        assertNull(deleteError)
        assertEquals(listOf(sessionBLabel), viewModel.labels.value)
    }

    @Test
    fun `createComment ignores outcome and does not write state or invoke success if session changed or deleted in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        val sessionBComment = Comment(
            id = "550e8400-e29b-41d4-a716-446655440089",
            taskId = "550e8400-e29b-41d4-a716-446655440093",
            content = "Operator B Comment",
            createdAt = "2026-08-25T10:00:00Z"
        )
        commentService.commentsByOperator[session.operatorId] = mutableListOf()
        commentService.commentsByOperator[sessionB.operatorId] = mutableListOf(sessionBComment)

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        commentService.onActionCallback = {
            commentService.onActionCallback = null
            authService.setSession(sessionB)
        }

        val taskId = "550e8400-e29b-41d4-a716-446655440093"
        var commentCreated: Comment? = null
        var commentError: String? = null
        viewModel.createComment(
            taskId = taskId,
            content = "New comment",
            onSuccess = { commentCreated = it },
            onError = { commentError = it }
        )
        advanceUntilIdle()

        assertNull(commentCreated)
        assertNull(commentError)
        assertEquals(listOf(sessionBComment), viewModel.comments.value)
    }

    @Test
    fun `updateOperatorTimedPlanType ignores outcome and does not write state or invoke success if session changed or deleted in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        settingsService.timedPlanTypeByOperator[session.operatorId] = null
        settingsService.timedPlanTypeByOperator[sessionB.operatorId] = TimedPlanType.INSTANT

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        settingsService.onActionCallback = {
            settingsService.onActionCallback = null
            authService.setSession(sessionB)
        }

        var updateSuccess = false
        var updateError: String? = null
        viewModel.updateOperatorTimedPlanType(
            type = TimedPlanType.FLOATING,
            onSuccess = { updateSuccess = true },
            onError = { updateError = it }
        )
        advanceUntilIdle()

        assertFalse(updateSuccess)
        assertNull(updateError)
        assertEquals(TimedPlanType.INSTANT, viewModel.operatorTimedPlanType.value)
    }

    @Test
    fun `updateTask ignores outcome and does not write state or invoke success if session changed or deleted in flight`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        val task = Task(
            id = "550e8400-e29b-41d4-a716-446655440055",
            title = "Initial Title",
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
        val taskB = Task(
            id = "550e8400-e29b-41d4-a716-446655440056",
            title = "Session B Task",
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
        taskService.tasksByOperator[session.operatorId] = mutableListOf(task)
        taskService.tasksByOperator[sessionB.operatorId] = mutableListOf(taskB)

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(1, viewModel.allTasks.value.size)

        taskService.onUpdateCallback = {
            taskService.onUpdateCallback = null
            authService.setSession(sessionB)
        }

        var updateSuccess = false
        var updateError: String? = null
        viewModel.updateTask(
            params = com.cras.app.data.UpdateTaskParams(
                id = task.id,
                title = "Changed Title",
                expectedVersion = 1
            ),
            onSuccess = { updateSuccess = true },
            onError = { updateError = it }
        )
        advanceUntilIdle()

        assertFalse(updateSuccess)
        assertNull(updateError)
        assertEquals(listOf(taskB), viewModel.allTasks.value)
    }

    @Test
    fun `direct operator switch from Operator A to Operator B clears in-memory tasks and resets account status before publishing authenticated state while keeping persisted outbox`() = runTest {
        val sessionA = session
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )

        val taskAId = "550e8400-e29b-41d4-a716-446655440094"
        val serverAId = "550e8400-e29b-41d4-a716-446655440096"
        val serverBId = "550e8400-e29b-41d4-a716-446655440097"

        taskService.tasks.add(
            Task(
                id = serverAId,
                title = "Task Server A",
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

        authService = FakeAuthService(sessionA)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(1, viewModel.allTasks.value.size)
        assertEquals(sessionA, (viewModel.authState.value as? AuthUiState.Authenticated)?.session)

        // Queue an outbox item for Operator A after initial drain
        val outboxA = OutboxItem.Create(
            id = taskAId,
            task = Task(
                id = taskAId,
                title = "Outbox Task A",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-25T09:00:00Z",
                updatedAt = "2026-08-25T09:00:00Z",
                version = 1
            ),
            params = CreateTaskParams(id = taskAId, title = "Outbox Task A"),
            createdAt = "2026-08-25T09:00:00Z"
        )
        outboxStore.enqueue(sessionA.operatorId, outboxA)

        // Prepare server tasks for Operator B
        taskService.tasks.clear()
        taskService.tasks.add(
            Task(
                id = serverBId,
                title = "Task Server B",
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

        // Switch directly from Operator A to Operator B
        authService.setSession(sessionB)
        advanceUntilIdle()

        assertEquals(sessionB, (viewModel.authState.value as? AuthUiState.Authenticated)?.session)
        // Operator A's persisted outbox is preserved in store
        assertEquals(1, outboxStore.getOutbox(sessionA.operatorId).size)
        assertEquals(taskAId, outboxStore.getOutbox(sessionA.operatorId).first().id)
        // Memory has only Operator B's reconciled tasks
        val taskIds = viewModel.allTasks.value.map { it.id }
        assertTrue(taskIds.contains(serverBId))
        assertFalse(taskIds.contains(serverAId))
        assertFalse(taskIds.contains(taskAId))
    }

    @Test
    fun `requestAccountDeletion immediately sets PENDING_DELETION and clears outbox so task actions during suspended deactivation cannot write outbox items`() = runTest {
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        installationService.deactivatedIds.clear()

        var createTaskError: String? = null
        installationService.onDeactivateCallback = {
            // Attempt to create a task while requestAccountDeletion is suspended in network deactivation
            viewModel.createTask(
                title = "Task During Deletion",
                onError = { createTaskError = it }
            )
        }

        var deletionConfirmation: DeletionConfirmation? = null
        viewModel.requestAccountDeletion(
            onSuccess = { deletionConfirmation = it },
            onError = {}
        )
        advanceUntilIdle()

        assertNotNull(deletionConfirmation)
        assertTrue(deletionConfirmation!!.confirmed)
        assertEquals("Account deletion is pending", createTaskError)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())
        assertNull(authService.currentSession.value)
    }

    @Test
    fun `fetchAccountStatus ignores outcome and does not invoke onSuccess if session switched during handlePendingDeletion`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        accountService.statusToReturn = AccountStatus(
            deletionState = AccountDeletionState.PENDING_DELETION,
            deletionDeadline = "2026-08-31T12:00:00Z",
            recoveryAvailable = true
        )

        installationService.onDeactivateCallback = {
            installationService.onDeactivateCallback = null
            authService.setSession(sessionB)
        }

        var fetchSuccess = false
        var errorReceived: String? = null
        viewModel.fetchAccountStatus(
            onSuccess = { fetchSuccess = true },
            onError = { errorReceived = it }
        )
        advanceUntilIdle()

        assertFalse(fetchSuccess)
        assertNull(errorReceived)
        assertEquals(sessionB, authService.currentSession.value)
        assertEquals(sessionB, (viewModel.authState.value as? AuthUiState.Authenticated)?.session)
    }

    @Test
    fun `requestAccountDeletion does not sign out replacement session or invoke onSuccess if session switched during deactivation`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        installationService.onDeactivateCallback = {
            installationService.onDeactivateCallback = null
            authService.setSession(sessionB)
        }

        var deleteSuccess = false
        var errorReceived: String? = null
        viewModel.requestAccountDeletion(
            onSuccess = { deleteSuccess = true },
            onError = { errorReceived = it }
        )
        advanceUntilIdle()

        assertTrue(accountService.deleteCalled)
        assertFalse(deleteSuccess)
        assertNull(errorReceived)
        assertEquals(sessionB, authService.currentSession.value)
        assertEquals(sessionB, (viewModel.authState.value as? AuthUiState.Authenticated)?.session)
    }

    @Test
    fun `handleInvalidationEvent task created or updated does not apply freshTask to new session if session switched during fetchTaskById`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        val staleTaskId = "550e8400-e29b-41d4-a716-446655440099"
        val staleTask = Task(
            id = staleTaskId,
            title = "Stale Task Session A",
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
        taskService.tasksByOperator[session.operatorId] = mutableListOf(staleTask)
        taskService.tasksByOperator[sessionB.operatorId] = mutableListOf()

        taskService.onFetchTaskByIdCallback = {
            taskService.onFetchTaskByIdCallback = null
            authService.setSession(sessionB)
        }

        realtimeService.emitInvalidate(
            InvalidationPayload(resource = "task", operation = "created", id = staleTaskId)
        )
        advanceUntilIdle()

        val taskIds = viewModel.allTasks.value.map { it.id }
        assertFalse(taskIds.contains(staleTaskId))
        assertEquals(sessionB, authService.currentSession.value)
    }

    @Test
    fun `handleInvalidationEvent label does not overwrite labels if session switched during fetchLabels`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        val staleLabelId = "550e8400-e29b-41d4-a716-446655440091"
        val staleLabel = Label(id = staleLabelId, name = "Label A", color = "#ff0000")
        labelService.labelsByOperator[session.operatorId] = mutableListOf(staleLabel)
        labelService.labelsByOperator[sessionB.operatorId] = mutableListOf()

        labelService.onFetchCallback = {
            labelService.onFetchCallback = null
            authService.setSession(sessionB)
        }

        realtimeService.emitInvalidate(
            InvalidationPayload(resource = "label", operation = "created", id = staleLabelId)
        )
        advanceUntilIdle()

        assertFalse(viewModel.labels.value.any { it.id == staleLabelId })
        assertEquals(sessionB, authService.currentSession.value)
    }

    @Test
    fun `handleInvalidationEvent comment does not overwrite comments if session switched during fetchComments`() = runTest {
        val sessionB = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "operator-b@cras.app",
            accessToken = "jwt-session-b"
        )
        val selectedTask = Task(
            id = "550e8400-e29b-41d4-a716-446655440011",
            title = "Selected Task",
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
        taskService.tasksByOperator[session.operatorId] = mutableListOf(selectedTask)
        taskService.tasksByOperator[sessionB.operatorId] = mutableListOf()
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectTask(selectedTask)

        val staleCommentId = "550e8400-e29b-41d4-a716-446655440092"
        val staleComment = Comment(id = staleCommentId, taskId = selectedTask.id, content = "Stale comment", createdAt = "2026-08-25T09:00:00Z")
        commentService.commentsByOperator[session.operatorId] = mutableListOf(staleComment)
        commentService.commentsByOperator[sessionB.operatorId] = mutableListOf()

        commentService.onFetchCallback = {
            commentService.onFetchCallback = null
            authService.setSession(sessionB)
        }

        realtimeService.emitInvalidate(
            InvalidationPayload(resource = "comment", operation = "created", id = staleCommentId, taskId = selectedTask.id)
        )
        advanceUntilIdle()

        assertFalse(viewModel.comments.value.any { it.id == staleCommentId })
        assertEquals(sessionB, authService.currentSession.value)
    }

    @Test
    fun `task operations and mutations fail closed while account status verification is suspended and in-flight`() = runTest {
        val fetchGate = CompletableDeferred<Unit>()
        accountService.onFetchCallback = {
            fetchGate.await()
        }

        authService = FakeAuthService(session)
        viewModel = createViewModel()

        // Give coroutines time to start checkAccountStatusInternal and suspend at fetchGate
        testDispatcher.scheduler.runCurrent()

        // Session is published as Authenticated, but accountStatus is still null (verification in-flight)
        assertEquals(session, (viewModel.authState.value as? AuthUiState.Authenticated)?.session)
        assertNull(viewModel.accountStatus.value)

        // 1. loadTasks does not fetch tasks while account status is unverified
        viewModel.loadTasks()
        testDispatcher.scheduler.runCurrent()
        assertFalse(taskService.fetchTasksCalled)

        // 2. createTask rejects operation and does not write to outbox
        var createError: String? = null
        viewModel.createTask(
            title = "Task During Verification",
            onError = { createError = it }
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals("Account verification in progress", createError)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())

        // 3. createSubtask rejects operation
        var subtaskError: String? = null
        viewModel.createSubtask(
            parentId = "parent-id",
            title = "Subtask During Verification",
            onError = { subtaskError = it }
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals("Account verification in progress", subtaskError)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())

        // 4. completeTask rejects operation
        var completeError: String? = null
        viewModel.completeTask(
            taskId = "task-id",
            expectedVersion = 1,
            onError = { completeError = it }
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals("Account verification in progress", completeError)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())

        // 5. createLabel rejects operation
        var labelError: String? = null
        viewModel.createLabel(
            name = "Label During Verification",
            color = "#ff0000",
            onError = { labelError = it }
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals("Account verification in progress", labelError)
        assertTrue(labelService.labels.isEmpty())

        // 6. createComment rejects operation
        var commentError: String? = null
        viewModel.createComment(
            taskId = "task-id",
            content = "Comment During Verification",
            onError = { commentError = it }
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals("Account verification in progress", commentError)
        assertTrue(commentService.comments.isEmpty())

        // 7. requestAccountDeletion rejects operation
        var deleteError: String? = null
        viewModel.requestAccountDeletion(
            onError = { deleteError = it }
        )
        testDispatcher.scheduler.runCurrent()
        assertEquals("Account verification in progress", deleteError)

        // Now resume fetchAccountStatus
        fetchGate.complete(Unit)
        advanceUntilIdle()

        // Account status verified as ACTIVE, authenticated session now started
        assertEquals(AccountDeletionState.ACTIVE, viewModel.accountStatus.value?.deletionState)
        assertTrue(taskService.fetchTasksCalled)

        // Task operations now operate normally
        var createSuccess = false
        viewModel.createTask(
            title = "Verified Task",
            onSuccess = { createSuccess = true }
        )
        advanceUntilIdle()
        assertTrue(createSuccess)
        assertEquals(1, viewModel.allTasks.value.size)
        assertEquals("Verified Task", viewModel.allTasks.value.first().title)
    }

    @Test
    fun `focusRoutedTask retains taskId while account status verification is in-flight and resolves upon status verification`() = runTest {
        val targetTaskId = "550e8400-e29b-41d4-a716-446655440077"
        val routedTask = Task(
            id = targetTaskId,
            title = "Routed Task",
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
        taskService.tasks.add(routedTask)

        val fetchGate = CompletableDeferred<Unit>()
        accountService.onFetchCallback = {
            fetchGate.await()
        }

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        testDispatcher.scheduler.runCurrent()

        // Account status is not verified yet
        assertNull(viewModel.accountStatus.value)

        // Call focusRoutedTask while status verification is gated
        viewModel.focusRoutedTask(targetTaskId)
        assertNull(viewModel.selectedTask.value)

        // Release the gate
        fetchGate.complete(Unit)
        advanceUntilIdle()

        // Once account status is verified and tasks are loaded, selectedTask resolves
        assertNotNull(viewModel.selectedTask.value)
        assertEquals(targetTaskId, viewModel.selectedTask.value?.id)
        assertEquals("Routed Task", viewModel.selectedTask.value?.title)
    }

    @Test
    fun `completeRoutedTask retains taskId while account status verification is in-flight and completes upon status verification`() = runTest {
        val targetTaskId = "550e8400-e29b-41d4-a716-446655440078"
        val routedTask = Task(
            id = targetTaskId,
            title = "Routed Task To Complete",
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
        taskService.tasks.add(routedTask)

        val fetchGate = CompletableDeferred<Unit>()
        accountService.onFetchCallback = {
            fetchGate.await()
        }

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        testDispatcher.scheduler.runCurrent()

        // Account status is not verified yet
        assertNull(viewModel.accountStatus.value)

        // Call completeRoutedTask while status verification is gated
        viewModel.completeRoutedTask(targetTaskId)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())

        // Release the gate
        fetchGate.complete(Unit)
        advanceUntilIdle()

        // Once account status is verified and tasks are loaded, pending completion is executed
        val completedState = viewModel.completedState.value
        assertTrue(completedState is CompletedUiState.Success)
        assertEquals(1, (completedState as CompletedUiState.Success).tasks.size)
        assertEquals(targetTaskId, (completedState as CompletedUiState.Success).tasks.first().id)
    }

    @Test
    fun `focusRoutedTask and completeRoutedTask discard intent when account is pending deletion`() = runTest {
        val targetTaskId = "550e8400-e29b-41d4-a716-446655440079"
        val routedTask = Task(
            id = targetTaskId,
            title = "Frozen Account Task",
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
        taskService.tasks.add(routedTask)
        accountService.statusToReturn = AccountStatus(AccountDeletionState.PENDING_DELETION, "2026-08-31T12:00:00Z", true)

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)

        viewModel.focusRoutedTask(targetTaskId)
        viewModel.completeRoutedTask(targetTaskId)
        advanceUntilIdle()

        assertNull(viewModel.selectedTask.value)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())
    }

    @Test
    fun `offline-first task mutations can enqueue locally to outbox when account status verification fails with network error`() = runTest {
        accountService.fetchShouldFail = true
        accountService.fetchExceptionToThrow = AccountLifecycleException(
            message = "Network error: unable to reach Cras account services.",
            statusCode = 0,
            code = "network_error",
            isNetworkError = true
        )

        taskService.shouldFailWithNetworkError = true
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        // Account status remains null due to transport failure (non-authoritative)
        assertNull(viewModel.accountStatus.value)
        assertTrue(viewModel.inboxState.value is InboxUiState.Error)

        // 1. createTask succeeds locally into Outbox
        var createSuccess = false
        viewModel.createTask(
            title = "Offline Created Task",
            onSuccess = { createSuccess = true }
        )
        testDispatcher.scheduler.runCurrent()

        assertTrue(createSuccess)
        val outbox = outboxStore.getOutbox(session.operatorId)
        assertTrue(outbox.any { it is OutboxItem.Create && it.task.title == "Offline Created Task" })
        assertEquals(1, viewModel.allTasks.value.size)
        assertEquals("Offline Created Task", viewModel.allTasks.value.first().title)

        advanceUntilIdle()

        // 2. createSubtask succeeds locally into Outbox
        val parentId = viewModel.allTasks.value.first().id
        var subtaskCreated: Task? = null
        viewModel.createSubtask(
            parentId = parentId,
            title = "Offline Subtask",
            onSuccess = { subtaskCreated = it }
        )
        testDispatcher.scheduler.runCurrent()

        assertNotNull(subtaskCreated)
        val updatedOutbox = outboxStore.getOutbox(session.operatorId)
        assertTrue(updatedOutbox.any { it is OutboxItem.Create && it.task.parentId == parentId })

        advanceUntilIdle()

        // 3. completeTask succeeds locally into Outbox
        var completeSuccess = false
        viewModel.completeTask(
            taskId = parentId,
            expectedVersion = 1,
            onSuccess = { completeSuccess = true }
        )
        testDispatcher.scheduler.runCurrent()

        assertTrue(completeSuccess)
        val finalOutbox = outboxStore.getOutbox(session.operatorId)
        assertTrue(finalOutbox.any { it is OutboxItem.Complete && it.taskId == parentId })

        advanceUntilIdle()
    }

    @Test
    fun `non-offline mutations fail when account status verification fails with network error`() = runTest {
        accountService.fetchShouldFail = true
        accountService.fetchExceptionToThrow = AccountLifecycleException(
            message = "Network error: unable to reach Cras account services.",
            statusCode = 0,
            code = "network_error",
            isNetworkError = true
        )

        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        var labelError: String? = null
        viewModel.createLabel(
            name = "Label",
            color = "#ff0000",
            onError = { labelError = it }
        )
        advanceUntilIdle()
        assertEquals("Account verification in progress", labelError)

        var commentError: String? = null
        viewModel.createComment(
            taskId = "task-id",
            content = "Comment",
            onError = { commentError = it }
        )
        advanceUntilIdle()
        assertEquals("Account verification in progress", commentError)

        var deleteError: String? = null
        viewModel.requestAccountDeletion(
            onError = { deleteError = it }
        )
        advanceUntilIdle()
        assertEquals("Account verification in progress", deleteError)
    }

    @Test
    fun `pending deletion account remains blocked from enqueueing work even when status verification throws network error`() = runTest {
        // First verify status as PENDING_DELETION
        accountService.statusToReturn = AccountStatus(AccountDeletionState.PENDING_DELETION, "2026-08-31T12:00:00Z", true)
        authService = FakeAuthService(session)
        viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)

        // Trigger a fetchAccountStatus that fails with network error
        accountService.fetchShouldFail = true
        accountService.fetchExceptionToThrow = AccountLifecycleException(
            message = "Network error",
            isNetworkError = true
        )

        viewModel.fetchAccountStatus(onError = {})
        advanceUntilIdle()

        // Account is still recognized as pending deletion; createTask is blocked
        var createError: String? = null
        viewModel.createTask(
            title = "Task On Frozen Account",
            onError = { createError = it }
        )
        advanceUntilIdle()

        assertEquals("Account deletion is pending", createError)
        assertTrue(outboxStore.getOutbox(session.operatorId).isEmpty())
    }
}
