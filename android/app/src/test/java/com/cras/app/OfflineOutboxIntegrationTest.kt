package com.cras.app

import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.auth.SessionStore
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.DeploymentConfig
import com.cras.app.data.InMemoryOutboxStore
import com.cras.app.data.InvalidationPayload
import com.cras.app.data.OperatorSettings
import com.cras.app.data.OutboxItem
import com.cras.app.data.OutboxStore
import com.cras.app.data.RealtimeService
import com.cras.app.data.RealtimeSubscription
import com.cras.app.data.SettingsService
import com.cras.app.data.SupabaseCommentService
import com.cras.app.data.SupabaseLabelService
import com.cras.app.data.SupabaseSettingsService
import com.cras.app.data.SupabaseTaskService
import com.cras.app.data.TaskConflictException
import com.cras.app.data.UpdateTaskParams
import com.cras.app.domain.CreatePlanParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.createPlanFromInputs
import com.cras.app.models.Plan
import com.cras.app.models.PlanSerializer
import com.cras.app.models.Task
import com.cras.app.ui.inbox.CompletedUiState
import com.cras.app.ui.inbox.InboxUiState
import com.cras.app.ui.inbox.InboxViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineOutboxIntegrationTest {

    private data class SimulatedDbRow(
        val id: String,
        val operatorId: String,
        val title: String,
        val description: String?,
        val priority: Int,
        val plan: Plan?,
        val labels: List<String>,
        val parentId: String?,
        val completedAt: String?,
        val createdAt: String,
        val updatedAt: String,
        val version: Int
    )

    private fun SimulatedDbRow.toTask(): Task = Task(
        id = id,
        title = title,
        description = description,
        priority = priority,
        plan = plan,
        labels = labels,
        parentId = parentId,
        completedAt = completedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version
    )

    private class ControlledRealtimeService : RealtimeService {
        var onInvalidateCallback: ((InvalidationPayload) -> Unit)? = null
        var onReconnectCallback: (() -> Unit)? = null
        var isSubscribed = false

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
                    onInvalidateCallback = null
                    onReconnectCallback = null
                }
            }
        }

        fun reconnect() {
            onReconnectCallback?.invoke()
        }
    }

    private val operatorAlice = "550e8400-e29b-41d4-a716-446655440001"
    private lateinit var mockWebServer: MockWebServer
    private val dbRows = CopyOnWriteArrayList<SimulatedDbRow>()
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var realtimeService: ControlledRealtimeService
    private var isNetworkOnline = true
    private var rejectCreateWithPermissionDenied = false

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dbRows.clear()
        realtimeService = ControlledRealtimeService()
        isNetworkOnline = true
        rejectCreateWithPermissionDenied = false

        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!isNetworkOnline) {
                    return MockResponse().setResponseCode(503).setBody("""{"message":"Service Unavailable"}""")
                }

                val path = request.path ?: "/"
                val authHeader = request.getHeader("Authorization")
                val callerOperatorId = authHeader?.removePrefix("Bearer ")?.let { token ->
                    if (token.startsWith("jwt-")) token.removePrefix("jwt-") else null
                }

                if (path.startsWith("/auth/v1/token")) {
                    val resp = """
                        {
                            "access_token": "jwt-$operatorAlice",
                            "token_type": "bearer",
                            "expires_in": 3600,
                            "user": {
                                "id": "$operatorAlice",
                                "email": "alice@cras.app"
                            }
                        }
                    """.trimIndent()
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(resp)
                }

                if (path.startsWith("/rest/v1/tasks")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    if (path.contains("id=eq.")) {
                        val taskId = path.substringAfter("id=eq.", "").substringBefore("&")
                        val row = dbRows.find { it.id == taskId && it.operatorId == callerOperatorId }
                        val tasks = if (row != null) listOf(row.toTask()) else emptyList()
                        val respBody = json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(Task.serializer()),
                            tasks
                        )
                        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                    }

                    val operatorTasks = dbRows.filter { it.operatorId == callerOperatorId }.map { it.toTask() }
                    val respBody = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(Task.serializer()),
                        operatorTasks
                    )
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                if (path.startsWith("/rest/v1/labels") || path.startsWith("/rest/v1/comments")) {
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("[]")
                }

                if (path.startsWith("/rest/v1/settings") || path.startsWith("/rest/v1/deployment_config")) {
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}")
                }

                if (path.startsWith("/rest/v1/rpc/create_task")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    if (rejectCreateWithPermissionDenied) {
                        return MockResponse().setResponseCode(403).setBody("""{"code":"42501","message":"Permission denied for create_task"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val title = reqJson["title"]?.jsonPrimitive?.content ?: ""

                    if (title.trim().isEmpty()) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Task title cannot be empty"}""")
                    }

                    val taskId = reqJson["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()

                    // Check for idempotent retry
                    val existing = dbRows.find { it.id == taskId && it.operatorId == callerOperatorId }
                    if (existing != null) {
                        val respBody = json.encodeToString(Task.serializer(), existing.toTask())
                        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                    }

                    val parsedLabels = reqJson["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    val parentId = reqJson["parent_id"]?.jsonPrimitive?.content

                    val parsedPlan: Plan? = if (reqJson.containsKey("plan") && reqJson["plan"] != null && reqJson["plan"] !is kotlinx.serialization.json.JsonNull) {
                        json.decodeFromJsonElement(PlanSerializer, reqJson["plan"]!!)
                    } else null

                    val newRow = SimulatedDbRow(
                        id = taskId,
                        operatorId = callerOperatorId,
                        title = title.trim(),
                        description = reqJson["description"]?.jsonPrimitive?.content,
                        priority = reqJson["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                        plan = parsedPlan,
                        labels = parsedLabels,
                        parentId = parentId,
                        completedAt = null,
                        createdAt = "2026-08-21T10:00:00Z",
                        updatedAt = "2026-08-21T10:00:00Z",
                        version = 1
                    )
                    dbRows.add(newRow)

                    val respBody = json.encodeToString(Task.serializer(), newRow.toTask())
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                if (path.startsWith("/rest/v1/rpc/complete_task")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val id = reqJson["id"]?.jsonPrimitive?.content ?: ""

                    val index = dbRows.indexOfFirst { it.id == id && it.operatorId == callerOperatorId }
                    if (index == -1) {
                        return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Task not found or unauthorized"}""")
                    }

                    val existing = dbRows[index]
                    val expectedVersion = reqJson["expected_version"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (expectedVersion != null && existing.version != expectedVersion) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"P0003","message":"Task version conflict: expected $expectedVersion, found ${existing.version}"}""")
                    }

                    val completedAt = reqJson["completed_at"]?.jsonPrimitive?.content ?: "2026-08-21T10:00:00Z"
                    val updatedRow = existing.copy(
                        completedAt = completedAt,
                        updatedAt = "2026-08-21T10:00:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val respBody = json.encodeToString(Task.serializer(), updatedRow.toTask())
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                return MockResponse().setResponseCode(404)
            }
        }
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockWebServer.shutdown()
    }

    private fun createViewModel(
        sessionStore: SessionStore = InMemorySessionStore(),
        outboxStore: OutboxStore = InMemoryOutboxStore(),
        settingsService: SettingsService? = null
    ): InboxViewModel {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "sb_publishable_anon")
        val authService = SupabaseAuthService(config, sessionStore, OkHttpClient())
        val taskService = SupabaseTaskService(config, OkHttpClient())
        val labelService = SupabaseLabelService(config, OkHttpClient())
        val commentService = SupabaseCommentService(config, OkHttpClient())
        val effectiveSettingsService = settingsService ?: SupabaseSettingsService(config, OkHttpClient())

        return InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = effectiveSettingsService,
            realtimeService = realtimeService,
            outboxStore = outboxStore,
            nowProvider = { Instant.parse("2026-08-21T10:00:00Z") },
            zoneIdProvider = { ZoneOffset.UTC }
        )
    }

    @Test
    fun `enters persistent Outbox locally before network acknowledgement for create and complete`() = runTest {
        val outboxStore = InMemoryOutboxStore()
        val viewModel = createViewModel(outboxStore = outboxStore)
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Disable network so request does not complete immediately
        isNetworkOnline = false

        // 1. Create task while offline
        viewModel.createTask(title = "Task entering outbox")
        advanceUntilIdle()

        // Task is visible in UI state immediately
        val inboxTasks = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxTasks.size)
        assertEquals("Task entering outbox", inboxTasks[0].title)

        // Persistent outbox contains the create item before network acknowledgement
        val outboxItems = outboxStore.getOutbox(operatorAlice)
        assertEquals(1, outboxItems.size)
        val createItem = outboxItems[0]
        assertTrue(createItem is OutboxItem.Create)
        assertEquals("Task entering outbox", (createItem as OutboxItem.Create).task.title)

        // 2. Complete task while offline
        val createdTaskId = (createItem as OutboxItem.Create).task.id
        viewModel.completeTask(taskId = createdTaskId)
        advanceUntilIdle()

        // Task marked completed in UI state
        val completedTasks = (viewModel.completedState.value as CompletedUiState.Success).tasks
        assertEquals(1, completedTasks.size)
        assertEquals(createdTaskId, completedTasks[0].id)

        // Persistent outbox now contains both create and complete items
        val updatedOutbox = outboxStore.getOutbox(operatorAlice)
        assertEquals(2, updatedOutbox.size)
        assertTrue(updatedOutbox[0] is OutboxItem.Create)
        assertTrue(updatedOutbox[1] is OutboxItem.Complete)

        // 3. Network reconnects and drains outbox
        isNetworkOnline = true
        realtimeService.reconnect()
        advanceUntilIdle()

        // Outbox drained completely
        assertEquals(0, outboxStore.getOutbox(operatorAlice).size)
        assertEquals(1, dbRows.size)
        assertEquals("Task entering outbox", dbRows[0].title)
        assertNotNull(dbRows[0].completedAt)
    }

    @Test
    fun `retains two independently accepted equal-title creates with distinct IDs without collapsing`() = runTest {
        val outboxStore = InMemoryOutboxStore()
        val viewModel = createViewModel(outboxStore = outboxStore)
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        isNetworkOnline = false

        // Create first "Identical Title"
        viewModel.createTask("Identical Title")
        advanceUntilIdle()

        // Create second "Identical Title"
        viewModel.createTask("Identical Title")
        advanceUntilIdle()

        val inboxTasks = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(2, inboxTasks.size)
        assertEquals("Identical Title", inboxTasks[0].title)
        assertEquals("Identical Title", inboxTasks[1].title)
        assertNotEquals(inboxTasks[0].id, inboxTasks[1].id)

        val outbox = outboxStore.getOutbox(operatorAlice)
        assertEquals(2, outbox.size)
        assertNotEquals(outbox[0].id, outbox[1].id)

        // Reconnect and drain
        isNetworkOnline = true
        realtimeService.reconnect()
        advanceUntilIdle()

        assertEquals(0, outboxStore.getOutbox(operatorAlice).size)
        assertEquals(2, dbRows.size)
        assertNotEquals(dbRows[0].id, dbRows[1].id)
    }

    @Test
    fun `survives process death or ViewModel recreation with queued outbox work and drains upon reconnect`() = runTest {
        val sharedSessionStore = InMemorySessionStore()
        val sharedOutboxStore = InMemoryOutboxStore()

        // 1. Initial ViewModel instance while offline
        val viewModel1 = createViewModel(sessionStore = sharedSessionStore, outboxStore = sharedOutboxStore)
        advanceUntilIdle()
        viewModel1.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        isNetworkOnline = false

        viewModel1.createTask("Surviving Task")
        advanceUntilIdle()

        assertEquals(1, sharedOutboxStore.getOutbox(operatorAlice).size)

        // 2. Simulate process death / new ViewModel instance mounting
        val viewModel2 = createViewModel(sessionStore = sharedSessionStore, outboxStore = sharedOutboxStore)
        advanceUntilIdle()

        // Survives: visible in new ViewModel from outbox overlay even while offline
        val inboxTasks = (viewModel2.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxTasks.size)
        assertEquals("Surviving Task", inboxTasks[0].title)

        // 3. Reconnect network
        isNetworkOnline = true
        realtimeService.reconnect()
        advanceUntilIdle()

        assertEquals(0, sharedOutboxStore.getOutbox(operatorAlice).size)
        assertEquals(1, dbRows.size)
        assertEquals("Surviving Task", dbRows[0].title)
    }

    @Test
    fun `uses cached effective default or Instant fallback deterministically for timed offline create`() = runTest {
        val outboxStore = InMemoryOutboxStore()
        val fakeSettingsService = object : SettingsService {
            override suspend fun fetchOperatorSettings(session: OperatorSession): OperatorSettings? = null
            override suspend fun fetchDeploymentConfig(session: OperatorSession): DeploymentConfig? = null
            override suspend fun fetchEffectiveTimedPlanType(session: OperatorSession): TimedPlanType = TimedPlanType.FLOATING
            override suspend fun updateOperatorTimedPlanType(session: OperatorSession, type: TimedPlanType?) {}
        }

        val viewModel = createViewModel(outboxStore = outboxStore, settingsService = fakeSettingsService)
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()
        assertEquals(TimedPlanType.FLOATING, viewModel.effectiveTimedPlanType.value)

        isNetworkOnline = false

        val timedPlan = createPlanFromInputs(
            CreatePlanParams(
                date = "2026-08-25",
                time = "14:30",
                effectiveDefault = viewModel.effectiveTimedPlanType.value,
                zoneId = ZoneOffset.UTC
            )
        )

        viewModel.createTask(
            title = "Timed Floating Task",
            plan = timedPlan
        )
        advanceUntilIdle()

        val outbox = outboxStore.getOutbox(operatorAlice)
        assertEquals(1, outbox.size)
        val created = (outbox[0] as OutboxItem.Create).task
        assertTrue(created.plan is Plan.Floating)
        assertEquals("2026-08-25", (created.plan as Plan.Floating).date)
        assertEquals("14:30", (created.plan as Plan.Floating).time)
    }

    @Test
    fun `falls back to instant plan type deterministically when cached effective default is absent for timed offline create`() = runTest {
        val outboxStore = InMemoryOutboxStore()
        val viewModel = createViewModel(outboxStore = outboxStore)
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()
        assertEquals(TimedPlanType.INSTANT, viewModel.effectiveTimedPlanType.value)

        isNetworkOnline = false

        val timedPlan = createPlanFromInputs(
            CreatePlanParams(
                date = "2026-08-25",
                time = "14:30",
                effectiveDefault = viewModel.effectiveTimedPlanType.value,
                zoneId = ZoneOffset.UTC
            )
        )

        viewModel.createTask(
            title = "Timed Instant Task",
            plan = timedPlan
        )
        advanceUntilIdle()

        val outbox = outboxStore.getOutbox(operatorAlice)
        assertEquals(1, outbox.size)
        val created = (outbox[0] as OutboxItem.Create).task
        assertTrue(created.plan is Plan.Instant)
        assertEquals("2026-08-25T14:30:00Z", (created.plan as Plan.Instant).at)
    }

    @Test
    fun `reports version conflict on completion retry rather than merging silently`() = runTest {
        val outboxStore = InMemoryOutboxStore()
        val viewModel = createViewModel(outboxStore = outboxStore)
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create base task online
        viewModel.createTask("Task to complete")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(1, task.version)

        // Go offline and complete task
        isNetworkOnline = false
        viewModel.completeTask(taskId = task.id)
        advanceUntilIdle()

        assertEquals(1, outboxStore.getOutbox(operatorAlice).size)

        // Meanwhile, another client modified the task on server to version 2
        val rowIdx = dbRows.indexOfFirst { it.id == task.id }
        dbRows[rowIdx] = dbRows[rowIdx].copy(
            title = "Modified on Web",
            version = 2
        )

        // Reconnect network
        isNetworkOnline = true
        realtimeService.reconnect()
        advanceUntilIdle()

        // Conflict is reported explicitly and outbox item is discarded
        assertEquals(0, outboxStore.getOutbox(operatorAlice).size)
        assertNull(viewModel.createTaskError.value)

        // Canonical state refetched to show server's version 2
        val inboxTasks = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxTasks.size)
        assertEquals("Modified on Web", inboxTasks[0].title)
        assertEquals(2, inboxTasks[0].version)
    }

    @Test
    fun `removes optimistic task and reports error on permanent create drain failure`() = runTest {
        val outboxStore = InMemoryOutboxStore()
        val viewModel = createViewModel(outboxStore = outboxStore)
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        isNetworkOnline = false
        viewModel.createTask("Unauthorized Task")
        advanceUntilIdle()

        assertEquals(1, (viewModel.inboxState.value as InboxUiState.Success).tasks.size)
        assertEquals(1, outboxStore.getOutbox(operatorAlice).size)

        // Server responds with permanent permission error
        isNetworkOnline = true
        rejectCreateWithPermissionDenied = true

        realtimeService.reconnect()
        advanceUntilIdle()

        // Outbox cleared, optimistic task removed, error recorded
        assertEquals(0, outboxStore.getOutbox(operatorAlice).size)
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
        assertNotNull(viewModel.createTaskError.value)
        assertTrue(viewModel.createTaskError.value!!.contains("Permission denied"))
    }

    @Test
    fun `retry interruption retains remaining queued work when network drops mid-drain`() = runTest {
        val outboxStore = InMemoryOutboxStore()
        val viewModel = createViewModel(outboxStore = outboxStore)
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        isNetworkOnline = false

        viewModel.createTask("Task A")
        viewModel.createTask("Task B")
        advanceUntilIdle()

        assertEquals(2, outboxStore.getOutbox(operatorAlice).size)

        // Drain first item successfully, then drop network before second item
        var requestIndex = 0
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                if (path.startsWith("/rest/v1/rpc/create_task")) {
                    requestIndex++
                    if (requestIndex == 1) {
                        val body = request.body.readUtf8()
                        val reqJson = json.parseToJsonElement(body).jsonObject
                        val taskId = reqJson["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                        val newRow = SimulatedDbRow(
                            id = taskId,
                            operatorId = operatorAlice,
                            title = "Task A",
                            description = null,
                            priority = 4,
                            plan = null,
                            labels = emptyList(),
                            parentId = null,
                            completedAt = null,
                            createdAt = "2026-08-21T10:00:00Z",
                            updatedAt = "2026-08-21T10:00:00Z",
                            version = 1
                        )
                        dbRows.add(newRow)
                        val respBody = json.encodeToString(Task.serializer(), newRow.toTask())
                        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                    } else {
                        // Drop network on second request
                        return MockResponse().setResponseCode(503).setBody("""{"message":"Service Unavailable"}""")
                    }
                }
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("[]")
            }
        }

        realtimeService.reconnect()
        advanceUntilIdle()

        // First item was drained, second item retained in outbox
        val remaining = outboxStore.getOutbox(operatorAlice)
        assertEquals(1, remaining.size)
        assertEquals("Task B", (remaining[0] as OutboxItem.Create).task.title)
    }
}
