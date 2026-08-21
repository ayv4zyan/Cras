package com.cras.app

import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.InvalidationPayload
import com.cras.app.data.RealtimeService
import com.cras.app.data.RealtimeSubscription
import com.cras.app.data.SupabaseCommentService
import com.cras.app.data.SupabaseLabelService
import com.cras.app.data.SupabaseSettingsService
import com.cras.app.data.SupabaseTaskService
import com.cras.app.data.TaskConflictException
import com.cras.app.data.UpdateTaskParams
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.PlanSerializer
import com.cras.app.models.Task
import com.cras.app.ui.inbox.AuthUiState
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end convergence tests verifying safety between concurrent Android and Web sessions:
 * - Version CAS protection and explicit conflict failure with canonical refetch
 * - Reconnect recovery of missed remote changes
 * - Hierarchy/relationship updates (subtasks, labels, comments)
 * - Independent equal-title task creation
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConvergenceIntegrationTest {

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

    private data class SimulatedCommentDbRow(
        val id: String,
        val operatorId: String,
        val taskId: String,
        val content: String,
        val createdAt: String
    )

    private data class SimulatedLabelDbRow(
        val id: String,
        val operatorId: String,
        val name: String,
        val color: String,
        val createdAt: String,
        val updatedAt: String
    )

    private class ControlledRealtimeService : RealtimeService {
        var onInvalidateCallback: ((InvalidationPayload) -> Unit)? = null
        var onReconnectCallback: (() -> Unit)? = null
        var isSubscribed = false
        var subscribedSession: OperatorSession? = null

        override fun subscribeToInvalidations(
            session: OperatorSession,
            onInvalidate: (InvalidationPayload) -> Unit,
            onReconnect: (() -> Unit)?
        ): RealtimeSubscription {
            subscribedSession = session
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

        fun emit(payload: InvalidationPayload) {
            onInvalidateCallback?.invoke(payload)
        }

        fun reconnect() {
            onReconnectCallback?.invoke()
        }
    }

    private lateinit var mockWebServer: MockWebServer
    private val dbRows = CopyOnWriteArrayList<SimulatedDbRow>()
    private val labelDbRows = CopyOnWriteArrayList<SimulatedLabelDbRow>()
    private val commentDbRows = CopyOnWriteArrayList<SimulatedCommentDbRow>()
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var realtimeService: ControlledRealtimeService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dbRows.clear()
        labelDbRows.clear()
        commentDbRows.clear()
        realtimeService = ControlledRealtimeService()

        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                val method = request.method ?: "GET"
                val authHeader = request.getHeader("Authorization")
                val callerOperatorId = authHeader?.removePrefix("Bearer ")?.let { token ->
                    if (token.startsWith("jwt-")) token.removePrefix("jwt-") else null
                }

                // Auth endpoint
                if (path.startsWith("/auth/v1/token")) {
                    val reqBody = request.body.clone().readUtf8()
                    val operatorId = if (reqBody.contains("bob") || reqBody.contains("446655440002")) {
                        "550e8400-e29b-41d4-a716-446655440002"
                    } else {
                        "550e8400-e29b-41d4-a716-446655440001"
                    }
                    val email = if (operatorId.endsWith("0002")) "bob@cras.app" else "alice@cras.app"
                    val resp = """
                        {
                            "access_token": "jwt-$operatorId",
                            "token_type": "bearer",
                            "expires_in": 3600,
                            "user": {
                                "id": "$operatorId",
                                "email": "$email"
                            }
                        }
                    """.trimIndent()
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(resp)
                }

                // Tasks list and single task REST endpoints
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

                // Labels REST endpoints
                if (path.startsWith("/rest/v1/labels")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }
                    val operatorLabels = labelDbRows
                        .filter { it.operatorId == callerOperatorId }
                        .map { Label(id = it.id, name = it.name, color = it.color, createdAt = it.createdAt, updatedAt = it.updatedAt) }
                    val respBody = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(Label.serializer()),
                        operatorLabels
                    )
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Comments REST endpoints
                if (path.startsWith("/rest/v1/comments")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }
                    val operatorComments = commentDbRows
                        .filter { it.operatorId == callerOperatorId }
                        .map { Comment(id = it.id, taskId = it.taskId, content = it.content, createdAt = it.createdAt) }
                    val respBody = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(Comment.serializer()),
                        operatorComments
                    )
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.create_task RPC
                if (path.startsWith("/rest/v1/rpc/create_task")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val title = reqJson["title"]?.jsonPrimitive?.content ?: ""

                    if (title.trim().isEmpty()) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Task title cannot be empty"}""")
                    }

                    val parsedLabels = reqJson["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    val parentId = reqJson["parent_id"]?.jsonPrimitive?.content

                    if (parentId != null) {
                        val parent = dbRows.find { it.id == parentId && it.operatorId == callerOperatorId }
                        if (parent == null) {
                            return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Parent task not found or unauthorized"}""")
                        }
                        if (parent.parentId != null) {
                            return MockResponse().setResponseCode(400).setBody("""{"code":"P0001","message":"Subtasks cannot have children (one-level nesting only)"}""")
                        }
                    }

                    val parsedPlan: Plan? = if (reqJson.containsKey("plan") && reqJson["plan"] != null && reqJson["plan"] !is kotlinx.serialization.json.JsonNull) {
                        json.decodeFromJsonElement(PlanSerializer, reqJson["plan"]!!)
                    } else null

                    val taskId = reqJson["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    val existingIndex = dbRows.indexOfFirst { it.id == taskId && it.operatorId == callerOperatorId }
                    if (existingIndex != -1) {
                        val respBody = json.encodeToString(Task.serializer(), dbRows[existingIndex].toTask())
                        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                    }

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
                        createdAt = "2026-08-19T00:00:00Z",
                        updatedAt = "2026-08-19T00:00:00Z",
                        version = 1
                    )
                    dbRows.add(newRow)

                    val respBody = json.encodeToString(Task.serializer(), newRow.toTask())
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.update_task RPC
                if (path.startsWith("/rest/v1/rpc/update_task")) {
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

                    val newTitle = reqJson["title"]?.jsonPrimitive?.content
                    val newPriority = reqJson["priority"]?.jsonPrimitive?.content?.toIntOrNull()
                    val newLabels = if (reqJson.containsKey("labels")) {
                        reqJson["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    } else existing.labels
                    val newParentId = if (reqJson.containsKey("parent_id")) reqJson["parent_id"]?.jsonPrimitive?.content else existing.parentId

                    val updatedRow = existing.copy(
                        title = newTitle?.trim() ?: existing.title,
                        description = if (reqJson.containsKey("description")) reqJson["description"]?.jsonPrimitive?.content else existing.description,
                        priority = newPriority ?: existing.priority,
                        labels = newLabels,
                        parentId = newParentId,
                        updatedAt = "2026-08-19T00:10:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val respBody = json.encodeToString(Task.serializer(), updatedRow.toTask())
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.complete_task RPC
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

                    val completedAt = reqJson["completed_at"]?.jsonPrimitive?.content ?: "2026-08-19T10:00:00Z"
                    val updatedRow = existing.copy(
                        completedAt = completedAt,
                        updatedAt = "2026-08-19T10:00:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val respBody = json.encodeToString(Task.serializer(), updatedRow.toTask())
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.uncomplete_task RPC
                if (path.startsWith("/rest/v1/rpc/uncomplete_task")) {
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

                    val updatedRow = existing.copy(
                        completedAt = null,
                        updatedAt = "2026-08-19T10:05:00Z",
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

    private fun createViewModel(): InboxViewModel {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "sb_publishable_anon")
        val sessionStore = InMemorySessionStore()
        val authService = SupabaseAuthService(config, sessionStore, OkHttpClient())
        val taskService = SupabaseTaskService(config, OkHttpClient())
        val labelService = SupabaseLabelService(config, OkHttpClient())
        val commentService = SupabaseCommentService(config, OkHttpClient())
        val settingsService = SupabaseSettingsService(config, OkHttpClient())

        return InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            realtimeService = realtimeService
        )
    }

    @Test
    fun `concurrent edits between Android and Web fail explicitly on stale version and converge without overwrite`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // 1. Android creates a task
        viewModel.createTask("Shared document review")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(1, task.version)
        assertEquals("Shared document review", task.title)

        // 2. Web concurrently edits the task in the backend to version 2
        val rowIdx = dbRows.indexOfFirst { it.id == task.id }
        dbRows[rowIdx] = dbRows[rowIdx].copy(
            title = "Shared document review (Web edited)",
            description = "Web added notes",
            version = 2
        )

        // 3. Android attempts a mutation using the stale version 1
        var errorOccurred: String? = null
        viewModel.updateTask(
            UpdateTaskParams(
                id = task.id,
                title = "Android conflicting edit",
                expectedVersion = 1
            ),
            onError = { errorOccurred = it }
        )
        advanceUntilIdle()

        // Android mutation MUST fail explicitly with conflict error
        assertNotNull(errorOccurred)
        assertTrue(errorOccurred!!.contains("Task version conflict"))

        // Android triggers canonical refetch and converges onto Web's version 2
        val convergedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Shared document review (Web edited)", convergedTask.title)
        assertEquals("Web added notes", convergedTask.description)
        assertEquals(2, convergedTask.version)

        // 4. Android now performs an update with the latest version 2, successfully advancing to version 3
        var secondSuccess = false
        viewModel.updateTask(
            UpdateTaskParams(
                id = convergedTask.id,
                title = "Android final converged title",
                expectedVersion = convergedTask.version
            ),
            onSuccess = { secondSuccess = true }
        )
        advanceUntilIdle()

        assertTrue(secondSuccess)
        val finalTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Android final converged title", finalTask.title)
        assertEquals(3, finalTask.version)
    }

    @Test
    fun `reconnect refetches canonical state and catches up on all missed server changes`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createTask("Task Online")
        advanceUntilIdle()
        assertEquals(1, (viewModel.inboxState.value as InboxUiState.Success).tasks.size)

        // Simulate offline period where Web creates multiple tasks and completes one on server
        val aliceId = "550e8400-e29b-41d4-a716-446655440001"
        val webTask1 = SimulatedDbRow(
            id = UUID.randomUUID().toString(),
            operatorId = aliceId,
            title = "Web Offline Task 1",
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = null,
            completedAt = null,
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T00:00:00Z",
            version = 1
        )
        val webTask2 = SimulatedDbRow(
            id = UUID.randomUUID().toString(),
            operatorId = aliceId,
            title = "Web Offline Completed Task 2",
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = null,
            completedAt = "2026-08-19T10:00:00Z",
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T10:00:00Z",
            version = 2
        )
        dbRows.addAll(listOf(webTask1, webTask2))

        // Trigger Reconnect event
        realtimeService.reconnect()
        advanceUntilIdle()

        // Verify inbox and completed states converged completely
        val inboxTasks = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(2, inboxTasks.size)
        assertTrue(inboxTasks.any { it.title == "Task Online" })
        assertTrue(inboxTasks.any { it.title == "Web Offline Task 1" })

        val completedTasks = (viewModel.completedState.value as CompletedUiState.Success).tasks
        assertEquals(1, completedTasks.size)
        assertEquals("Web Offline Completed Task 2", completedTasks[0].title)
    }

    @Test
    fun `relationship changes and label assignments on Web converge on Android via invalidations`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        val aliceId = "550e8400-e29b-41d4-a716-446655440001"

        // 1. Android creates parent task
        viewModel.createTask("Parent Task")
        advanceUntilIdle()
        val parentTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]

        // 2. Web creates a subtask under Parent Task
        val subtaskRow = SimulatedDbRow(
            id = UUID.randomUUID().toString(),
            operatorId = aliceId,
            title = "Web Subtask",
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = parentTask.id,
            completedAt = null,
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T00:00:00Z",
            version = 1
        )
        dbRows.add(subtaskRow)

        // Web broadcasts task invalidation
        realtimeService.emit(
            InvalidationPayload(
                resource = "task",
                id = subtaskRow.id,
                operation = "created",
                parentId = parentTask.id
            )
        )
        advanceUntilIdle()

        // Android allTasks includes the subtask, but inbox only shows parent
        val allTasks = viewModel.allTasks.value
        assertEquals(2, allTasks.size)
        val fetchedSubtask = allTasks.find { it.id == subtaskRow.id }
        assertNotNull(fetchedSubtask)
        assertEquals(parentTask.id, fetchedSubtask?.parentId)

        // 3. Web creates a label
        val labelRow = SimulatedLabelDbRow(
            id = UUID.randomUUID().toString(),
            operatorId = aliceId,
            name = "Urgent",
            color = "#ef4444",
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T00:00:00Z"
        )
        labelDbRows.add(labelRow)

        realtimeService.emit(
            InvalidationPayload(
                resource = "label",
                id = labelRow.id,
                operation = "created"
            )
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.labels.value.size)
        assertEquals("Urgent", viewModel.labels.value[0].name)
    }

    @Test
    fun `concurrent independent equal-title creates converge safely without loss`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        val aliceId = "550e8400-e29b-41d4-a716-446655440001"

        // 1. Android creates task "Prepare Sprint Review"
        viewModel.createTask("Prepare Sprint Review", priority = 2)
        advanceUntilIdle()

        // 2. Web creates another task with the exact same title "Prepare Sprint Review"
        val webTask = SimulatedDbRow(
            id = UUID.randomUUID().toString(),
            operatorId = aliceId,
            title = "Prepare Sprint Review",
            description = "Created on Web",
            priority = 1,
            plan = null,
            labels = emptyList(),
            parentId = null,
            completedAt = null,
            createdAt = "2026-08-19T00:01:00Z",
            updatedAt = "2026-08-19T00:01:00Z",
            version = 1
        )
        dbRows.add(webTask)

        // Broadcast arrives for Web's task
        realtimeService.emit(
            InvalidationPayload(
                resource = "task",
                id = webTask.id,
                operation = "created"
            )
        )
        advanceUntilIdle()

        val inboxState = viewModel.inboxState.value
        assertTrue(inboxState is InboxUiState.Success)
        val tasks = (inboxState as InboxUiState.Success).tasks

        // Both tasks exist, with unique IDs, without colliding or discarding either
        assertEquals(2, tasks.size)
        assertEquals("Prepare Sprint Review", tasks[0].title)
        assertEquals("Prepare Sprint Review", tasks[1].title)
        assertNotEquals(tasks[0].id, tasks[1].id)
        assertTrue(tasks.any { it.description == "Created on Web" && it.priority == 1 })
        assertTrue(tasks.any { it.description == null && it.priority == 2 })
    }

    @Test
    fun `cross-operator isolation ensures operator only observes their own tasks, labels, and comments`() = runTest {
        val operatorAlice = "550e8400-e29b-41d4-a716-446655440001"
        val operatorBob = "550e8400-e29b-41d4-a716-446655440002"

        // Seed Bob's data in the database
        dbRows.add(
            SimulatedDbRow(
                id = "550e8400-e29b-41d4-a716-446655440099",
                operatorId = operatorBob,
                title = "Bob Private Task",
                description = "Confidential to Bob",
                priority = 2,
                plan = null,
                labels = listOf("550e8400-e29b-41d4-a716-446655440098"),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )
        labelDbRows.add(
            SimulatedLabelDbRow(
                id = "550e8400-e29b-41d4-a716-446655440098",
                operatorId = operatorBob,
                name = "Bob Label",
                color = "#FF0000",
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z"
            )
        )
        commentDbRows.add(
            SimulatedCommentDbRow(
                id = "550e8400-e29b-41d4-a716-446655440097",
                operatorId = operatorBob,
                taskId = "550e8400-e29b-41d4-a716-446655440099",
                content = "Bob's secret comment",
                createdAt = "2026-08-19T00:00:00Z"
            )
        )

        // Seed Alice's data
        dbRows.add(
            SimulatedDbRow(
                id = "550e8400-e29b-41d4-a716-446655440010",
                operatorId = operatorAlice,
                title = "Alice Task",
                description = "Alice Work",
                priority = 4,
                plan = null,
                labels = listOf("550e8400-e29b-41d4-a716-446655440030"),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )
        labelDbRows.add(
            SimulatedLabelDbRow(
                id = "550e8400-e29b-41d4-a716-446655440030",
                operatorId = operatorAlice,
                name = "Alice Label",
                color = "#00FF00",
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z"
            )
        )
        commentDbRows.add(
            SimulatedCommentDbRow(
                id = "550e8400-e29b-41d4-a716-446655440020",
                operatorId = operatorAlice,
                taskId = "550e8400-e29b-41d4-a716-446655440010",
                content = "Alice's comment",
                createdAt = "2026-08-19T00:00:00Z"
            )
        )

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "test-anon-key")
        val sessionStore = InMemorySessionStore()
        val authService = SupabaseAuthService(config, sessionStore, OkHttpClient())
        val taskService = SupabaseTaskService(config, OkHttpClient())
        val labelService = SupabaseLabelService(config, OkHttpClient())
        val commentService = SupabaseCommentService(config, OkHttpClient())
        val settingsService = SupabaseSettingsService(config, OkHttpClient())

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()

        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        assertEquals(operatorAlice, realtimeService.subscribedSession?.operatorId)

        val inboxState = viewModel.inboxState.value
        assertTrue(inboxState is InboxUiState.Success)
        val allTasks = (inboxState as InboxUiState.Success).tasks
        assertEquals(1, allTasks.size)
        assertEquals("Alice Task", allTasks[0].title)

        val labels = viewModel.labels.value
        assertEquals(1, labels.size)
        assertEquals("Alice Label", labels[0].name)

        viewModel.selectTask(allTasks[0])
        advanceUntilIdle()

        realtimeService.emit(
            InvalidationPayload(
                resource = "comment",
                id = "550e8400-e29b-41d4-a716-446655440020",
                operation = "created",
                taskId = allTasks[0].id
            )
        )
        advanceUntilIdle()

        val comments = viewModel.comments.value
        assertEquals(1, comments.size)
        assertEquals("Alice's comment", comments[0].content)
    }
}
