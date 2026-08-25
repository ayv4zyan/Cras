package com.cras.app.ui.inbox

import com.cras.app.auth.AuthService
import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CommentService
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.InstallationService
import com.cras.app.data.InMemoryOutboxStore
import com.cras.app.data.LabelService
import com.cras.app.data.RegisterInstallationParams
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Task
import com.cras.app.notification.InMemoryNotificationPreferenceStore
import com.cras.app.notification.NotificationInstallationSync
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelInstallationTest {

    private val dispatcher = StandardTestDispatcher()

    private val sessionA = OperatorSession(
        accessToken = "token-a",
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "alice@cras.app"
    )

    private lateinit var authSessionFlow: MutableStateFlow<OperatorSession?>
    private val events = mutableListOf<String>()

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
        private val sessionFlow: MutableStateFlow<OperatorSession?>,
        private val authenticatedSession: OperatorSession,
        private val events: MutableList<String>
    ) : AuthService {
        override val currentSession: StateFlow<OperatorSession?> = sessionFlow.asStateFlow()
        private val store = InMemorySessionStore()

        override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): OperatorSession {
            events.add("auth-sign-in")
            return authenticatedSession
        }

        override suspend fun restoreSession(): OperatorSession? = store.loadSession()

        override suspend fun signOut() {
            events.add("auth-sign-out")
            store.clearSession()
            sessionFlow.value = null
        }
    }

    private open class NoopTaskService : TaskService {
        override suspend fun fetchTasks(session: OperatorSession): List<Task> = emptyList()
        override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? = null
        override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task =
            throw UnsupportedOperationException()

        override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task =
            throw UnsupportedOperationException()

        override suspend fun completeTask(
            session: OperatorSession,
            taskId: String,
            expectedVersion: Int,
            completedAt: String?
        ): Task = throw UnsupportedOperationException()

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

    private open inner class RecordingInstallationService : InstallationService {
        val deactivatedIds = mutableListOf<String>()

        override suspend fun registerOrUpdate(
            session: OperatorSession,
            params: RegisterInstallationParams
        ) = com.cras.app.data.InstallationRecord(
            id = params.id,
            platform = "android",
            localEnabled = params.localEnabled,
            permissionState = params.permissionState,
            endpoint = params.endpoint,
            isActive = true
        )

        override suspend fun deactivate(session: OperatorSession, installationId: String): Boolean {
            events.add("installation-deactivated")
            deactivatedIds.add(installationId)
            return true
        }
    }

    private fun createViewModel(sync: NotificationInstallationSync): InboxViewModel =
        InboxViewModel(
            authService = FakeAuthService(authSessionFlow, sessionA, events),
            taskService = NoopTaskService(),
            labelService = NoopLabelService(),
            commentService = NoopCommentService(),
            settingsService = null,
            realtimeService = null,
            outboxStore = InMemoryOutboxStore(),
            installationSync = sync
        )

    @Test
    fun `authentication reconciles the FCM installation for the session`() = runTest(dispatcher) {
        var registeredSession: OperatorSession? = null
        val service = object : RecordingInstallationService() {
            override suspend fun registerOrUpdate(
                session: OperatorSession,
                params: RegisterInstallationParams
            ): com.cras.app.data.InstallationRecord {
                registeredSession = session
                events.add("installation-reconciled")
                return super.registerOrUpdate(session, params)
            }
        }
        val preferences = InMemoryNotificationPreferenceStore()
        val sync = NotificationInstallationSync(service, preferences, { com.cras.app.notification.PlatformPermissionState.GRANTED }, { "fcm-token" })
        val viewModel = createViewModel(sync)

        authSessionFlow.value = sessionA
        advanceUntilIdle()

        assertEquals(sessionA.operatorId, registeredSession?.operatorId)
        assertTrue(events.contains("installation-reconciled"))
        assertEquals(
            com.cras.app.notification.AndroidNotificationStatus.Enabled,
            sync.status.value
        )
    }

    @Test
    fun `sign-out disables the installation before the auth session is cleared`() = runTest(dispatcher) {
        val service = RecordingInstallationService()
        val sync = NotificationInstallationSync(service, InMemoryNotificationPreferenceStore(), { com.cras.app.notification.PlatformPermissionState.GRANTED }, { "fcm-token" })
        val viewModel = createViewModel(sync)

        authSessionFlow.value = sessionA
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(1, service.deactivatedIds.size)
        assertTrue(
            "installation deactivation must happen before auth sign-out",
            events.indexOf("installation-deactivated") < events.indexOf("auth-sign-out")
        )
    }

    @Test
    fun `reconcileInstallation and setNotificationsEnabled do not invoke service with stale session after signOut`() = runTest(dispatcher) {
        var registerCallCount = 0
        val service = object : RecordingInstallationService() {
            override suspend fun registerOrUpdate(
                session: OperatorSession,
                params: RegisterInstallationParams
            ): com.cras.app.data.InstallationRecord {
                registerCallCount++
                events.add("installation-reconciled")
                return super.registerOrUpdate(session, params)
            }
        }
        val sync = NotificationInstallationSync(service, InMemoryNotificationPreferenceStore(), { com.cras.app.notification.PlatformPermissionState.GRANTED }, { "fcm-token" })
        val viewModel = createViewModel(sync)

        authSessionFlow.value = sessionA
        advanceUntilIdle()

        val initialReconciliations = registerCallCount

        // Trigger reconcile and notifications enabled before & after signOut
        viewModel.signOut()
        viewModel.reconcileInstallation()
        viewModel.setNotificationsEnabled(true)
        advanceUntilIdle()

        assertEquals(initialReconciliations, registerCallCount)
        assertEquals(1, service.deactivatedIds.size)
        assertNull(authSessionFlow.value)
    }
}
