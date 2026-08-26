package com.cras.app

import com.cras.app.auth.AuthService
import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.data.AccountDeletionState
import com.cras.app.data.AccountLifecycleException
import com.cras.app.data.AccountService
import com.cras.app.data.AccountStatus
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.DeletionConfirmation
import com.cras.app.data.InMemoryOutboxStore
import com.cras.app.data.InstallationRecord
import com.cras.app.data.InstallationService
import com.cras.app.data.OutboxItem
import com.cras.app.data.RegisterInstallationParams
import com.cras.app.data.SupabaseAccountService
import com.cras.app.data.SupabaseCommentService
import com.cras.app.data.SupabaseLabelService
import com.cras.app.data.SupabaseRealtimeService
import com.cras.app.data.SupabaseSettingsService
import com.cras.app.data.SupabaseTaskService
import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.Task
import com.cras.app.notification.AndroidNotificationStatus
import com.cras.app.notification.InMemoryNotificationPreferenceStore
import com.cras.app.notification.NotificationInstallationSync
import com.cras.app.notification.PlatformPermissionState
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.InboxUiState
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.voice.RetainedRecording
import com.cras.app.voice.VoiceRecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
class AccountLifecycleJourneyTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var mockWebServer: MockWebServer
    private lateinit var config: PublicSupabaseConfig
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var outboxStore: InMemoryOutboxStore
    private lateinit var notifPrefs: InMemoryNotificationPreferenceStore
    private lateinit var voiceRecordingStore: InMemoryVoiceRecordingStore
    private lateinit var fakeInstallationService: FakeInstallationService
    private lateinit var httpClient: OkHttpClient

    private var currentDeletionState = AccountDeletionState.ACTIVE
    private var currentDeletionDeadline: String? = null
    private var currentRecoveryAvailable = false
    private var currentGoogleAuthSession: OperatorSession? = null
    private var serverTasks = mutableListOf<Task>()
    private var serverLabels = mutableListOf<Label>()
    private var serverComments = mutableListOf<Comment>()
    private val recordedLifecycleActions = mutableListOf<String>()

    private val operatorAlice = OperatorSession(
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "alice@cras.app",
        accessToken = "jwt-550e8400-e29b-41d4-a716-446655440001",
        refreshToken = "refresh-alice"
    )

    private class FakeInstallationService : InstallationService {
        val registered = mutableListOf<RegisterInstallationParams>()
        val deactivated = mutableListOf<String>()

        override suspend fun registerOrUpdate(session: OperatorSession, params: RegisterInstallationParams): InstallationRecord {
            registered.add(params)
            return InstallationRecord(
                id = params.id,
                platform = "android",
                localEnabled = params.localEnabled,
                permissionState = params.permissionState,
                endpoint = params.endpoint
            )
        }

        override suspend fun deactivate(session: OperatorSession, installationId: String): Boolean {
            deactivated.add(installationId)
            return true
        }
    }

    private class InMemoryVoiceRecordingStore : VoiceRecordingStore {
        val recordings = mutableListOf<RetainedRecording>()
        var clearAllCount = 0
        var owner: String? = null

        override fun save(wav: ByteArray, createdAtEpochMs: Long): RetainedRecording {
            val id = UUID.randomUUID().toString()
            val rec = RetainedRecording(
                id = id,
                fileName = "$id.wav",
                sizeBytes = wav.size.toLong(),
                createdAtEpochMs = createdAtEpochMs
            )
            recordings.add(rec)
            return rec
        }

        override fun list(): List<RetainedRecording> = recordings.toList()
        override fun latest(): RetainedRecording? = recordings.maxByOrNull { it.createdAtEpochMs }
        override fun readBytes(id: String): ByteArray? = byteArrayOf(1, 2, 3)
        override fun delete(id: String): Boolean {
            recordings.removeIf { it.id == id }
            return true
        }
        override fun clearAll(): Boolean {
            clearAllCount++
            recordings.clear()
            owner = null
            return true
        }
        override fun getRecordingOwner(): String? = owner
        override fun setRecordingOwner(operatorId: String?) {
            owner = operatorId
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        config = PublicSupabaseConfig(url = baseUrl, publishableKey = "test-anon-key")
        sessionStore = InMemorySessionStore()
        outboxStore = InMemoryOutboxStore()
        notifPrefs = InMemoryNotificationPreferenceStore()
        voiceRecordingStore = InMemoryVoiceRecordingStore()
        fakeInstallationService = FakeInstallationService()
        httpClient = OkHttpClient()

        currentDeletionState = AccountDeletionState.ACTIVE
        currentDeletionDeadline = null
        currentRecoveryAvailable = false
        serverTasks.clear()
        serverLabels.clear()
        serverComments.clear()
        recordedLifecycleActions.clear()

        setupMockServer()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        Dispatchers.resetMain()
    }

    private fun setupMockServer() {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                val method = request.method ?: ""

                if (path.startsWith("/functions/v1/account-lifecycle")) {
                    val body = request.body.readUtf8()
                    return when {
                        body.contains(""""action":"status"""") -> {
                            recordedLifecycleActions.add("status")
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{
                                        "deletionState": "${currentDeletionState.value}",
                                        "deletionDeadline": ${if (currentDeletionDeadline != null) "\"$currentDeletionDeadline\"" else "null"},
                                        "recoveryAvailable": $currentRecoveryAvailable
                                    }""".trimIndent()
                                )
                        }
                        body.contains(""""action":"request-deletion"""") -> {
                            recordedLifecycleActions.add("request-deletion")
                            currentDeletionState = AccountDeletionState.PENDING_DELETION
                            currentDeletionDeadline = "2026-08-31T12:00:00Z"
                            currentRecoveryAvailable = true
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """{
                                        "confirmed": true,
                                        "deletionState": "pending_deletion",
                                        "deletionDeadline": "2026-08-31T12:00:00Z",
                                        "sessionsRevoked": true
                                    }""".trimIndent()
                                )
                        }
                        body.contains(""""action":"recover-account"""") -> {
                            recordedLifecycleActions.add("recover-account")
                            if (!currentRecoveryAvailable) {
                                MockResponse()
                                    .setResponseCode(403)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("""{"error":"Recovery window has closed","code":"recovery_window_closed"}""")
                            } else {
                                currentDeletionState = AccountDeletionState.ACTIVE
                                currentDeletionDeadline = null
                                currentRecoveryAvailable = false
                                MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("""{"recovered": true}""")
                            }
                        }
                        else -> MockResponse().setResponseCode(400).setBody("""{"error":"unknown action"}""")
                    }
                }

                if (path.startsWith("/rest/v1/rpc/export_operator_data")) {
                    val exportJson = """{
                        "exportedAt": "2026-08-25T10:00:00Z",
                        "tasks": [],
                        "labels": [],
                        "taskLabels": [],
                        "comments": [],
                        "settings": {"defaultTimedPlanType": "instant"}
                    }""".trimIndent()
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(exportJson)
                }

                if (path.startsWith("/rest/v1/tasks")) {
                    if (currentDeletionState == AccountDeletionState.PENDING_DELETION) {
                        return MockResponse().setResponseCode(401).setBody("""{"error":"Account is pending deletion"}""")
                    }
                    if (method == "GET") {
                        val taskListJson = json.encodeToString(
                            ListSerializer(Task.serializer()),
                            serverTasks
                        )
                        return MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(taskListJson)
                    }
                }

                if (path.startsWith("/rest/v1/labels")) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("[]")
                }

                if (path.startsWith("/rest/v1/comments")) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("[]")
                }

                if (path.startsWith("/rest/v1/settings") || path.startsWith("/rest/v1/deployment_config")) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("[]")
                }

                if (path.startsWith("/auth/v1/token")) {
                    val grantType = request.requestUrl?.queryParameter("grant_type")
                    val body = request.body.readUtf8()
                    val reqJson = try {
                        json.parseToJsonElement(body).jsonObject
                    } catch (_: Exception) {
                        null
                    }
                    val provider = reqJson?.get("provider")?.jsonPrimitive?.content
                    val idToken = reqJson?.get("id_token")?.jsonPrimitive?.content

                    if (grantType != "id_token" || provider != "google" || idToken != "google-id-token-alice") {
                        return MockResponse()
                            .setResponseCode(400)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"error":"invalid_request","error_description":"Invalid token exchange request"}""")
                    }

                    val session = currentGoogleAuthSession ?: operatorAlice
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{
                                "access_token": "${session.accessToken}",
                                "token_type": "bearer",
                                "expires_in": 3600,
                                "refresh_token": "${session.refreshToken ?: "refresh-token"}",
                                "user": {
                                    "id": "${session.operatorId}",
                                    "aud": "authenticated",
                                    "role": "authenticated",
                                    "email": "${session.email ?: "alice@cras.app"}"
                                }
                            }""".trimIndent()
                        )
                }

                if (path.startsWith("/rest/v1/installations")) {
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("[]")
                }

                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }
    }

    private fun createEnvironment(initialSession: OperatorSession? = null): Pair<InboxViewModel, NotificationInstallationSync> {
        val authService = SupabaseAuthService(config, sessionStore, httpClient)
        val taskService = SupabaseTaskService(config, httpClient)
        val labelService = SupabaseLabelService(config, httpClient)
        val commentService = SupabaseCommentService(config, httpClient)
        val settingsService = SupabaseSettingsService(config, httpClient)
        val accountService = SupabaseAccountService(config, httpClient, ioDispatcher = testDispatcher)

        val sync = NotificationInstallationSync(
            installationService = fakeInstallationService,
            preferences = notifPrefs,
            permissionProvider = { PlatformPermissionState.GRANTED },
            fcmTokenProvider = { "mock-fcm-token-android" }
        )

        if (initialSession != null) {
            sessionStore.saveSession(initialSession)
        }

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            accountService = accountService,
            outboxStore = outboxStore,
            voiceRecordingStore = voiceRecordingStore,
            installationSync = sync,
            nowProvider = { Instant.parse("2026-08-25T10:00:00Z") },
            zoneIdProvider = { ZoneId.of("UTC") }
        )

        return Pair(viewModel, sync)
    }

    @Test
    fun `1 - Sign out distinguishes from deletion leaving server data intact`() = runTest {
        serverTasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440010",
                title = "Keep on server task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-25T08:00:00Z",
                updatedAt = "2026-08-25T08:00:00Z",
                version = 1
            )
        )

        val (viewModel, sync) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        assertEquals(1, viewModel.allTasks.value.size)
        assertEquals(AccountDeletionState.ACTIVE, viewModel.accountStatus.value?.deletionState)

        viewModel.signOut()
        advanceUntilIdle()

        assertNull(sessionStore.loadSession())
        assertEquals(AccountDeletionState.ACTIVE, currentDeletionState)
        assertEquals(1, serverTasks.size)
    }

    @Test
    fun `2 - Operator can request and save canonical data export before confirmation`() = runTest {
        val (viewModel, _) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        var exportOutput: String? = null
        viewModel.exportOperatorData(
            onSuccess = { exportOutput = it },
            onError = {}
        )
        advanceUntilIdle()

        assertNotNull(exportOutput)
        assertTrue(exportOutput!!.contains(""""exportedAt": "2026-08-25T10:00:00Z""""))
        assertTrue(exportOutput!!.contains(""""settings""""))
    }

    @Test
    fun `3 - Confirmation clears local Task cache, Outbox, Drafts, and retained recordings`() = runTest {
        val (viewModel, _) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        val outboxTaskId = "550e8400-e29b-41d4-a716-446655440011"
        outboxStore.enqueue(
            operatorAlice.operatorId,
            OutboxItem.Create(
                id = outboxTaskId,
                task = Task(
                    id = outboxTaskId,
                    title = "Pending offline task",
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
                params = CreateTaskParams(id = outboxTaskId, title = "Pending offline task"),
                createdAt = "2026-08-25T09:00:00Z"
            )
        )
        voiceRecordingStore.save(byteArrayOf(1, 2, 3, 4), System.currentTimeMillis())
        assertEquals(1, voiceRecordingStore.list().size)
        assertFalse(outboxStore.getOutbox(operatorAlice.operatorId).isEmpty())

        var deletionDone = false
        viewModel.requestAccountDeletion(
            onSuccess = { deletionDone = true },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(deletionDone)
        assertEquals(AccountDeletionState.PENDING_DELETION, currentDeletionState)
        assertTrue(outboxStore.getOutbox(operatorAlice.operatorId).isEmpty())
        assertTrue(voiceRecordingStore.list().isEmpty())
        assertTrue(viewModel.allTasks.value.isEmpty())
        assertNull(sessionStore.loadSession())
    }

    @Test
    fun `4 - Access remains blocked with unexpired JWT and local data is wiped when pending deletion observed`() = runTest {
        currentDeletionState = AccountDeletionState.PENDING_DELETION
        currentDeletionDeadline = "2026-08-31T12:00:00Z"
        currentRecoveryAvailable = true

        val (viewModel, _) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        val status = viewModel.accountStatus.value
        assertNotNull(status)
        assertEquals(AccountDeletionState.PENDING_DELETION, status?.deletionState)
        assertEquals("2026-08-31T12:00:00Z", status?.deletionDeadline)
        assertTrue(status?.recoveryAvailable == true)

        assertTrue(viewModel.allTasks.value.isEmpty())
    }

    @Test
    fun `5 - Recovery before boundary restores access and reactivates only authenticating installation`() = runTest {
        currentDeletionState = AccountDeletionState.PENDING_DELETION
        currentDeletionDeadline = "2026-08-31T12:00:00Z"
        currentRecoveryAvailable = true

        serverTasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440012",
                title = "Recovered Task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-25T08:00:00Z",
                updatedAt = "2026-08-25T08:00:00Z",
                version = 1
            )
        )

        val (viewModel, sync) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)

        val initialDeactivations = fakeInstallationService.deactivated.size
        assertEquals(0, fakeInstallationService.registered.size)

        var recoverySuccess = false
        viewModel.recoverAccount(
            onSuccess = { recoverySuccess = true },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(recoverySuccess)
        assertEquals(AccountDeletionState.ACTIVE, currentDeletionState)
        assertEquals(AccountDeletionState.ACTIVE, viewModel.accountStatus.value?.deletionState)
        assertEquals(1, viewModel.allTasks.value.size)
        assertEquals("Recovered Task", viewModel.allTasks.value.first().title)

        assertEquals(AndroidNotificationStatus.Enabled, sync.status.value)
        assertEquals(1, fakeInstallationService.registered.size)
        assertEquals(notifPrefs.getOrCreateInstallationId(), fakeInstallationService.registered.single().id)
        assertTrue(fakeInstallationService.registered.single().localEnabled)
        assertEquals(initialDeactivations, fakeInstallationService.deactivated.size)
    }

    @Test
    fun `6 - Recovery is refused at deadline when window has closed`() = runTest {
        currentDeletionState = AccountDeletionState.PENDING_DELETION
        currentDeletionDeadline = "2026-08-25T09:00:00Z"
        currentRecoveryAvailable = false

        val (viewModel, _) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        var errorMsg: String? = null
        viewModel.recoverAccount(
            onSuccess = {},
            onError = { errorMsg = it }
        )
        advanceUntilIdle()

        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Recovery window has closed"))
        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)
    }

    @Test
    fun `7 - Offline device limitation discards local data once connected to frozen account`() = runTest {
        val offlineTaskId = "550e8400-e29b-41d4-a716-446655440099"
        outboxStore.enqueue(
            operatorAlice.operatorId,
            OutboxItem.Create(
                id = offlineTaskId,
                task = Task(
                    id = offlineTaskId,
                    title = "Offline Unsynced Task",
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
                params = CreateTaskParams(id = offlineTaskId, title = "Offline Unsynced Task"),
                createdAt = "2026-08-25T09:00:00Z"
            )
        )

        currentDeletionState = AccountDeletionState.PENDING_DELETION
        currentDeletionDeadline = "2026-08-31T12:00:00Z"
        currentRecoveryAvailable = true

        val (viewModel, _) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        assertTrue(outboxStore.getOutbox(operatorAlice.operatorId).isEmpty())
        assertTrue(viewModel.allTasks.value.isEmpty())
        assertEquals(AccountDeletionState.PENDING_DELETION, viewModel.accountStatus.value?.deletionState)
    }

    @Test
    fun `8 - Clean registration with same Google identity after purge sweep creates pristine empty account`() = runTest {
        // Start with original Alice account active with a server task
        serverTasks.add(
            Task(
                id = "550e8400-e29b-41d4-a716-446655440020",
                title = "Original Alice Task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-25T08:00:00Z",
                updatedAt = "2026-08-25T08:00:00Z",
                version = 1
            )
        )
        currentGoogleAuthSession = operatorAlice
        val (viewModel, _) = createEnvironment(operatorAlice)
        advanceUntilIdle()

        assertEquals(AccountDeletionState.ACTIVE, viewModel.accountStatus.value?.deletionState)
        assertEquals(1, viewModel.allTasks.value.size)

        // 1. Drive the original account through deletion
        var deletionDone = false
        viewModel.requestAccountDeletion(
            onSuccess = { deletionDone = true },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(deletionDone)
        assertEquals(AccountDeletionState.PENDING_DELETION, currentDeletionState)
        assertNull(sessionStore.loadSession())

        // 2. Simulate backend purge sweep after deadline
        serverTasks.clear()
        currentDeletionState = AccountDeletionState.ACTIVE
        currentDeletionDeadline = null
        currentRecoveryAvailable = false

        // 3. Create replacement session via Google reauthentication for the same identity
        val freshOperator = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440999",
            email = "alice@cras.app",
            accessToken = "jwt-550e8400-e29b-41d4-a716-446655440999",
            refreshToken = "refresh-fresh-alice"
        )
        currentGoogleAuthSession = freshOperator

        viewModel.signInWithGoogleIdToken("google-id-token-alice")
        advanceUntilIdle()

        assertEquals(freshOperator, sessionStore.loadSession())

        // 4. Assert pristine empty active account
        assertEquals(AccountDeletionState.ACTIVE, viewModel.accountStatus.value?.deletionState)
        assertTrue(viewModel.allTasks.value.isEmpty())
        assertTrue(outboxStore.getOutbox(freshOperator.operatorId).isEmpty())
    }
}
