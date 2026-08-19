package com.cras.app

import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.config.getPublicSupabaseConfig
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.SupabaseLabelService
import com.cras.app.data.SupabaseTaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.Task
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.CompletedUiState
import com.cras.app.ui.inbox.InboxUiState
import com.cras.app.ui.inbox.InboxViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BlackboxJourneyTest {

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

    private data class SimulatedLabelDbRow(
        val id: String,
        val operatorId: String,
        val name: String,
        val color: String,
        val createdAt: String,
        val updatedAt: String
    )

    private lateinit var mockWebServer: MockWebServer
    private val dbRows = mutableListOf<SimulatedDbRow>()
    private val labelDbRows = mutableListOf<SimulatedLabelDbRow>()
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dbRows.clear()
        labelDbRows.clear()

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
                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val idToken = reqJson["id_token"]?.jsonPrimitive?.content ?: "anon"
                    val operatorId = if (idToken.contains("bob")) {
                        "550e8400-e29b-41d4-a716-446655440002"
                    } else {
                        "550e8400-e29b-41d4-a716-446655440001"
                    }
                    val email = if (idToken.contains("bob")) "bob@cras.app" else "alice@cras.app"

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

                // Labels REST endpoints
                if (path.startsWith("/rest/v1/labels")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    when (method) {
                        "GET" -> {
                            val operatorLabels = labelDbRows
                                .filter { it.operatorId == callerOperatorId }
                                .sortedBy { it.createdAt }
                                .map {
                                    Label(
                                        id = it.id,
                                        name = it.name,
                                        color = it.color,
                                        createdAt = it.createdAt,
                                        updatedAt = it.updatedAt
                                    )
                                }
                            val respBody = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(Label.serializer()),
                                operatorLabels
                            )
                            return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                        "POST" -> {
                            val body = request.body.readUtf8()
                            val reqJson = json.parseToJsonElement(body).jsonObject
                            val name = reqJson["name"]?.jsonPrimitive?.content?.trim() ?: ""
                            val color = reqJson["color"]?.jsonPrimitive?.content?.trim() ?: ""
                            val id = reqJson["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()

                            if (name.isEmpty() || color.isEmpty()) {
                                return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Invalid label params"}""")
                            }

                            // Duplicate name check per operator
                            if (labelDbRows.any { it.operatorId == callerOperatorId && it.name.equals(name, ignoreCase = true) }) {
                                return MockResponse().setResponseCode(409).setBody("""{"code":"23505","message":"duplicate key value violates unique constraint \"uq_labels_name_operator\""}""")
                            }

                            val newLabel = SimulatedLabelDbRow(
                                id = id,
                                operatorId = callerOperatorId,
                                name = name,
                                color = color,
                                createdAt = "2026-08-19T00:00:00Z",
                                updatedAt = "2026-08-19T00:00:00Z"
                            )
                            labelDbRows.add(newLabel)

                            val created = Label(
                                id = newLabel.id,
                                name = newLabel.name,
                                color = newLabel.color,
                                createdAt = newLabel.createdAt,
                                updatedAt = newLabel.updatedAt
                            )
                            val respBody = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(Label.serializer()),
                                listOf(created)
                            )
                            return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                        "PATCH" -> {
                            val labelId = path.substringAfter("id=eq.", "").substringBefore("&")
                            val index = labelDbRows.indexOfFirst { it.id == labelId && it.operatorId == callerOperatorId }
                            if (index == -1) {
                                return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Label not found"}""")
                            }

                            val body = request.body.readUtf8()
                            val reqJson = json.parseToJsonElement(body).jsonObject
                            val newName = reqJson["name"]?.jsonPrimitive?.content?.trim()
                            val newColor = reqJson["color"]?.jsonPrimitive?.content?.trim()

                            if (newName != null && newName.isEmpty()) {
                                return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Label name cannot be empty"}""")
                            }

                            if (newName != null && labelDbRows.any { it.operatorId == callerOperatorId && it.id != labelId && it.name.equals(newName, ignoreCase = true) }) {
                                return MockResponse().setResponseCode(409).setBody("""{"code":"23505","message":"duplicate key value violates unique constraint \"uq_labels_name_operator\""}""")
                            }

                            val existing = labelDbRows[index]
                            val updated = existing.copy(
                                name = newName ?: existing.name,
                                color = newColor ?: existing.color,
                                updatedAt = "2026-08-19T00:15:00Z"
                            )
                            labelDbRows[index] = updated

                            val updatedLabel = Label(
                                id = updated.id,
                                name = updated.name,
                                color = updated.color,
                                createdAt = updated.createdAt,
                                updatedAt = updated.updatedAt
                            )
                            val respBody = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(Label.serializer()),
                                listOf(updatedLabel)
                            )
                            return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                        "DELETE" -> {
                            val labelId = path.substringAfter("id=eq.", "").substringBefore("&")
                            labelDbRows.removeAll { it.id == labelId && it.operatorId == callerOperatorId }
                            // Cascade remove from tasks
                            for (i in dbRows.indices) {
                                if (dbRows[i].operatorId == callerOperatorId && dbRows[i].labels.contains(labelId)) {
                                    dbRows[i] = dbRows[i].copy(labels = dbRows[i].labels.filterNot { it == labelId })
                                }
                            }
                            return MockResponse().setResponseCode(204)
                        }
                    }
                }

                // Data API: api.tasks view
                if (path.startsWith("/rest/v1/tasks")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    // RLS: operator_id = auth.uid()
                    val operatorTasks = dbRows.filter { it.operatorId == callerOperatorId }.map { row ->
                        Task(
                            id = row.id,
                            title = row.title,
                            description = row.description,
                            priority = row.priority,
                            plan = row.plan,
                            labels = row.labels,
                            parentId = row.parentId,
                            completedAt = row.completedAt,
                            createdAt = row.createdAt,
                            updatedAt = row.updatedAt,
                            version = row.version
                        )
                    }

                    val respBody = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(Task.serializer()),
                        operatorTasks
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

                    val newRow = SimulatedDbRow(
                        id = UUID.randomUUID().toString(),
                        operatorId = callerOperatorId,
                        title = title.trim(),
                        description = reqJson["description"]?.jsonPrimitive?.content,
                        priority = reqJson["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                        plan = null,
                        labels = parsedLabels,
                        parentId = null,
                        completedAt = null,
                        createdAt = "2026-08-19T00:00:00Z",
                        updatedAt = "2026-08-19T00:00:00Z",
                        version = 1
                    )
                    dbRows.add(newRow)

                    val createdTask = Task(
                        id = newRow.id,
                        title = newRow.title,
                        description = newRow.description,
                        priority = newRow.priority,
                        plan = newRow.plan,
                        labels = newRow.labels,
                        parentId = newRow.parentId,
                        completedAt = newRow.completedAt,
                        createdAt = newRow.createdAt,
                        updatedAt = newRow.updatedAt,
                        version = newRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), createdTask)
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
                    if (existing.completedAt != null) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"P0001","message":"Completed tasks cannot be edited. Uncomplete first."}""")
                    }

                    val expectedVersion = reqJson["expected_version"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (expectedVersion != null && existing.version != expectedVersion) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"P0001","message":"Task version conflict: expected $expectedVersion, found ${existing.version}"}""")
                    }

                    val newTitle = reqJson["title"]?.jsonPrimitive?.content
                    if (newTitle != null && newTitle.trim().isEmpty()) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Task title cannot be empty"}""")
                    }

                    val newPriority = reqJson["priority"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (newPriority != null && (newPriority < 1 || newPriority > 4)) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Priority must be between 1 and 4"}""")
                    }

                    val newLabels = if (reqJson.containsKey("labels")) {
                        reqJson["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    } else {
                        existing.labels
                    }

                    val updatedRow = existing.copy(
                        title = newTitle?.trim() ?: existing.title,
                        description = if (reqJson.containsKey("description")) reqJson["description"]?.jsonPrimitive?.content else existing.description,
                        priority = newPriority ?: existing.priority,
                        labels = newLabels,
                        updatedAt = "2026-08-19T00:10:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val updatedTask = Task(
                        id = updatedRow.id,
                        title = updatedRow.title,
                        description = updatedRow.description,
                        priority = updatedRow.priority,
                        plan = updatedRow.plan,
                        labels = updatedRow.labels,
                        parentId = updatedRow.parentId,
                        completedAt = updatedRow.completedAt,
                        createdAt = updatedRow.createdAt,
                        updatedAt = updatedRow.updatedAt,
                        version = updatedRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), updatedTask)
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
                    val completedAt = reqJson["completed_at"]?.jsonPrimitive?.content ?: "2026-08-19T10:00:00Z"

                    val updatedRow = existing.copy(
                        completedAt = completedAt,
                        updatedAt = "2026-08-19T10:00:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val completedTask = Task(
                        id = updatedRow.id,
                        title = updatedRow.title,
                        description = updatedRow.description,
                        priority = updatedRow.priority,
                        plan = updatedRow.plan,
                        labels = updatedRow.labels,
                        parentId = updatedRow.parentId,
                        completedAt = updatedRow.completedAt,
                        createdAt = updatedRow.createdAt,
                        updatedAt = updatedRow.updatedAt,
                        version = updatedRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), completedTask)
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
                    val updatedRow = existing.copy(
                        completedAt = null,
                        updatedAt = "2026-08-19T10:05:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val uncompletedTask = Task(
                        id = updatedRow.id,
                        title = updatedRow.title,
                        description = updatedRow.description,
                        priority = updatedRow.priority,
                        plan = updatedRow.plan,
                        labels = updatedRow.labels,
                        parentId = updatedRow.parentId,
                        completedAt = updatedRow.completedAt,
                        createdAt = updatedRow.createdAt,
                        updatedAt = updatedRow.updatedAt,
                        version = updatedRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), uncompletedTask)
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

    private fun createViewModel(sessionStore: InMemorySessionStore = InMemorySessionStore()): InboxViewModel {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "sb_publishable_anon")
        val authService = SupabaseAuthService(config, sessionStore, OkHttpClient())
        val taskService = SupabaseTaskService(config, OkHttpClient())
        val labelService = SupabaseLabelService(config, OkHttpClient())
        return InboxViewModel(authService, taskService, labelService)
    }

    @Test
    fun `proves Criterion 4 and ADR 0005 - configuration purity`() {
        val env = mapOf(
            "SUPABASE_URL" to "https://cras-mvp.supabase.co",
            "SUPABASE_ANON_KEY" to "sb_publishable_anon_token"
        )
        val config = getPublicSupabaseConfig(env)
        assertEquals("https://cras-mvp.supabase.co", config.url)
        assertEquals("sb_publishable_anon_token", config.publishableKey)
        assertFalse(config.publishableKey.contains("service_role"))
    }

    @Test
    fun `proves Criterion 1 - Google Sign-In establishes and restores operator session`() = runTest {
        val sessionStore = InMemorySessionStore()

        // 1. Fresh login
        val viewModel = createViewModel(sessionStore)
        advanceUntilIdle()
        assertTrue(viewModel.authState.value is AuthUiState.Unauthenticated)

        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        val auth = viewModel.authState.value
        assertTrue(auth is AuthUiState.Authenticated)
        val session = (auth as AuthUiState.Authenticated).session
        assertEquals("alice@cras.app", session.email)
        assertEquals("550e8400-e29b-41d4-a716-446655440001", session.operatorId)

        // 2. Returning session restores
        val restoredViewModel = createViewModel(sessionStore)
        advanceUntilIdle()

        val restoredAuth = restoredViewModel.authState.value
        assertTrue(restoredAuth is AuthUiState.Authenticated)
        assertEquals("alice@cras.app", (restoredAuth as AuthUiState.Authenticated).session.email)
    }

    @Test
    fun `proves Criterion 2 & 3 - creates titled tasks with distinct identities and filters inbox`() = runTest {
        val sessionStore = InMemorySessionStore()
        val viewModel = createViewModel(sessionStore)
        advanceUntilIdle()

        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create Task 1
        viewModel.createTask("Prepare launch checklist")
        advanceUntilIdle()

        // Create Task 2 with duplicate title
        viewModel.createTask("Prepare launch checklist")
        advanceUntilIdle()

        val state = viewModel.inboxState.value
        assertTrue(state is InboxUiState.Success)
        val tasks = (state as InboxUiState.Success).tasks

        assertEquals(2, tasks.size)
        assertEquals("Prepare launch checklist", tasks[0].title)
        assertEquals("Prepare launch checklist", tasks[1].title)
        assertNotEquals(tasks[0].id, tasks[1].id)

        // Add non-inbox tasks directly to database for Alice
        val aliceId = "550e8400-e29b-41d4-a716-446655440001"
        dbRows.add(
            SimulatedDbRow(
                id = UUID.randomUUID().toString(),
                operatorId = aliceId,
                title = "Subtask task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = tasks[0].id, // Subtask
                completedAt = null,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )
        dbRows.add(
            SimulatedDbRow(
                id = UUID.randomUUID().toString(),
                operatorId = aliceId,
                title = "Tomorrow meeting",
                description = null,
                priority = 4,
                plan = Plan.DateOnly("2026-08-20"), // Dated
                labels = emptyList(),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )
        dbRows.add(
            SimulatedDbRow(
                id = UUID.randomUUID().toString(),
                operatorId = aliceId,
                title = "Done task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = null,
                completedAt = "2026-08-19T01:00:00Z", // Completed
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )

        viewModel.loadTasks()
        advanceUntilIdle()

        val refreshedState = viewModel.inboxState.value
        assertTrue(refreshedState is InboxUiState.Success)
        val refreshedTasks = (refreshedState as InboxUiState.Success).tasks

        // Inbox contains ONLY the 2 open top-level undated tasks
        assertEquals(2, refreshedTasks.size)
        assertTrue(refreshedTasks.none { it.title == "Subtask task" })
        assertTrue(refreshedTasks.none { it.title == "Tomorrow meeting" })
        assertTrue(refreshedTasks.none { it.title == "Done task" })
    }

    @Test
    fun `proves Issue 42 AC 1 - Compose flows edit Description and all Priority states`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create with initial description and priority 4
        viewModel.createTask("Spec review", description = "Initial notes", priority = 4)
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Initial notes", task.description)
        assertEquals(4, task.priority)
        assertEquals(1, task.version)

        // Cycle through all Priority states: P1, P2, P3, P4
        for (p in listOf(1, 2, 3, 4)) {
            var updated = false
            val currentTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
            viewModel.updateTask(
                UpdateTaskParams(
                    id = currentTask.id,
                    title = "Spec review P$p",
                    description = "Updated description for priority $p",
                    priority = p,
                    expectedVersion = currentTask.version
                ),
                onSuccess = { updated = true }
            )
            advanceUntilIdle()

            assertTrue(updated)
            val updatedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
            assertEquals("Spec review P$p", updatedTask.title)
            assertEquals("Updated description for priority $p", updatedTask.description)
            assertEquals(p, updatedTask.priority)
        }
    }

    @Test
    fun `proves Issue 42 AC 2 & 3 - Completing and uncompleting produce canonical persistence and newest-first Completed view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createTask("Alpha task")
        advanceUntilIdle()
        viewModel.createTask("Beta task")
        advanceUntilIdle()

        val initialInbox = (viewModel.inboxState.value as InboxUiState.Success).tasks
        val alpha = initialInbox.find { it.title == "Alpha task" }!!
        val beta = initialInbox.find { it.title == "Beta task" }!!

        // Complete Alpha at earlier timestamp
        viewModel.completeTask(alpha.id, completedAt = "2026-08-19T09:00:00Z")
        advanceUntilIdle()

        // Complete Beta at later timestamp
        viewModel.completeTask(beta.id, completedAt = "2026-08-19T11:00:00Z")
        advanceUntilIdle()

        // 1. Both completed tasks absent from Inbox view
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)

        // 2. Completed view has both tasks ordered newest-first (Beta at 11:00 before Alpha at 09:00)
        val completedState = viewModel.completedState.value
        assertTrue(completedState is CompletedUiState.Success)
        val completedList = (completedState as CompletedUiState.Success).tasks
        assertEquals(2, completedList.size)
        assertEquals("Beta task", completedList[0].title)
        assertEquals("2026-08-19T11:00:00Z", completedList[0].completedAt)
        assertEquals("Alpha task", completedList[1].title)
        assertEquals("2026-08-19T09:00:00Z", completedList[1].completedAt)

        // 3. Uncomplete Beta task -> returns to Inbox and removed from Completed
        viewModel.uncompleteTask(beta.id)
        advanceUntilIdle()

        val inboxAfterUncomplete = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxAfterUncomplete.size)
        assertEquals("Beta task", inboxAfterUncomplete[0].title)
        assertNull(inboxAfterUncomplete[0].completedAt)

        val completedAfterUncomplete = (viewModel.completedState.value as CompletedUiState.Success).tasks
        assertEquals(1, completedAfterUncomplete.size)
        assertEquals("Alpha task", completedAfterUncomplete[0].title)
    }

    @Test
    fun `proves Issue 42 AC 4 - Field edits on completed Tasks are rejected until uncompletion`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createTask("Immutable when done")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        viewModel.completeTask(task.id)
        advanceUntilIdle()

        // Attempt to edit description and title while completed
        var errorMessage: String? = null
        viewModel.updateTask(
            UpdateTaskParams(
                id = task.id,
                title = "Attempted modified title",
                description = "Attempted description"
            ),
            onError = { errorMessage = it }
        )
        advanceUntilIdle()

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Completed tasks cannot be edited. Uncomplete first."))

        // Verify task in db was not modified
        val completedTask = (viewModel.completedState.value as CompletedUiState.Success).tasks[0]
        assertEquals("Immutable when done", completedTask.title)
        assertNull(completedTask.description)

        // Uncomplete task
        viewModel.uncompleteTask(task.id)
        advanceUntilIdle()

        // Now field edits succeed
        var successMessage = false
        viewModel.updateTask(
            UpdateTaskParams(
                id = task.id,
                title = "Modified title after uncompletion",
                description = "Valid description now"
            ),
            onSuccess = { successMessage = true }
        )
        advanceUntilIdle()

        assertTrue(successMessage)
        val uncompletedInbox = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Modified title after uncompletion", uncompletedInbox.title)
        assertEquals("Valid description now", uncompletedInbox.description)
    }

    @Test
    fun `proves Issue 42 AC 5 - UI tests cover success, failure, and stale rendered state recovery`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createTask("Base task")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]

        // 1. Success case
        var success = false
        viewModel.updateTask(
            UpdateTaskParams(id = task.id, description = "Updated desc", expectedVersion = task.version),
            onSuccess = { success = true }
        )
        advanceUntilIdle()
        assertTrue(success)

        val updatedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, updatedTask.version)

        // 2. Failure due to stale version (CAS conflict)
        var conflictError: String? = null
        viewModel.updateTask(
            UpdateTaskParams(id = task.id, title = "Stale Edit", expectedVersion = 1), // Version is already 2
            onError = { conflictError = it }
        )
        advanceUntilIdle()

        assertNotNull(conflictError)
        assertTrue(conflictError!!.contains("version conflict"))

        // Stale rendered state recovery: viewmodel reloaded canonical state from database
        val recoveredTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Base task", recoveredTask.title)
        assertEquals(2, recoveredTask.version)

        // Subsequent edit with corrected version succeeds
        var recoverySuccess = false
        viewModel.updateTask(
            UpdateTaskParams(id = task.id, title = "Fresh edit", expectedVersion = recoveredTask.version),
            onSuccess = { recoverySuccess = true }
        )
        advanceUntilIdle()

        assertTrue(recoverySuccess)
        val finalList = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Fresh edit", finalList.title)
        assertEquals(3, finalList.version)
    }

    @Test
    fun `proves Issue 44 AC 1 & 2 - Android can create, rename, recolor, and remove Labels with duplicate name error handling`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // 1. Create label
        var createdUrgent: Label? = null
        viewModel.createLabel("Urgent", "#ef4444", onSuccess = { createdUrgent = it })
        advanceUntilIdle()

        assertNotNull(createdUrgent)
        assertEquals("Urgent", createdUrgent!!.name)
        assertEquals("#ef4444", createdUrgent!!.color)
        assertEquals(1, viewModel.labels.value.size)

        // 2. Create another label
        var createdBackend: Label? = null
        viewModel.createLabel("Backend", "#3b82f6", onSuccess = { createdBackend = it })
        advanceUntilIdle()

        assertNotNull(createdBackend)
        assertEquals(2, viewModel.labels.value.size)

        // 3. Duplicate name error - clear recoverable error
        var duplicateError: String? = null
        viewModel.createLabel("urgent", "#10b981", onError = { duplicateError = it })
        advanceUntilIdle()

        assertNotNull(duplicateError)
        assertEquals("A label with this name already exists", duplicateError)
        assertEquals(2, viewModel.labels.value.size)

        // 4. Rename and recolor label
        var updatedUrgent: Label? = null
        viewModel.updateLabel(
            id = createdUrgent!!.id,
            name = "Critical",
            color = "#f97316",
            onSuccess = { updatedUrgent = it }
        )
        advanceUntilIdle()

        assertNotNull(updatedUrgent)
        assertEquals("Critical", updatedUrgent!!.name)
        assertEquals("#f97316", updatedUrgent!!.color)
        assertEquals(createdUrgent!!.id, updatedUrgent!!.id) // Stable UUID identity

        // 5. Remove label
        var removed = false
        viewModel.deleteLabel(createdBackend!!.id, onSuccess = { removed = true })
        advanceUntilIdle()

        assertTrue(removed)
        assertEquals(1, viewModel.labels.value.size)
        assertEquals("Critical", viewModel.labels.value[0].name)
    }

    @Test
    fun `proves Issue 44 AC 3 - Label identity and Task associations remain stable across rename and other label deletion`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create 2 labels
        var labelUrgent: Label? = null
        var labelDev: Label? = null
        viewModel.createLabel("Urgent", "#ef4444", onSuccess = { labelUrgent = it })
        viewModel.createLabel("Dev", "#10b981", onSuccess = { labelDev = it })
        advanceUntilIdle()

        assertNotNull(labelUrgent)
        assertNotNull(labelDev)

        // Create Task with both labels
        viewModel.createTask(
            title = "Fix production regression",
            priority = 1,
            labels = listOf(labelUrgent!!.id, labelDev!!.id)
        )
        advanceUntilIdle()

        val initialTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, initialTask.labels.size)
        assertTrue(initialTask.labels.contains(labelUrgent!!.id))
        assertTrue(initialTask.labels.contains(labelDev!!.id))

        // Rename Urgent to Blocker
        viewModel.updateLabel(
            id = labelUrgent!!.id,
            name = "Blocker",
            color = "#ef4444"
        )
        advanceUntilIdle()

        // Task association remains stable: task still points to labelUrgent.id!
        val taskAfterRename = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, taskAfterRename.labels.size)
        assertTrue(taskAfterRename.labels.contains(labelUrgent!!.id))

        // Label name in canonical label list is now "Blocker" with same id
        val renamedLabel = viewModel.labels.value.find { it.id == labelUrgent!!.id }!!
        assertEquals("Blocker", renamedLabel.name)

        // Delete "Dev" label -> associations to "Dev" are cascaded, "Blocker" remains
        viewModel.deleteLabel(labelDev!!.id)
        advanceUntilIdle()

        val taskAfterDelete = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(1, taskAfterDelete.labels.size)
        assertEquals(labelUrgent!!.id, taskAfterDelete.labels[0])
    }

    @Test
    fun `proves Issue 44 AC 4 - Compose UI state assigns and unassigns multiple labels and renders across Inbox and Completed`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create labels
        var labelA: Label? = null
        var labelB: Label? = null
        var labelC: Label? = null
        viewModel.createLabel("Label A", "#ef4444", onSuccess = { labelA = it })
        viewModel.createLabel("Label B", "#3b82f6", onSuccess = { labelB = it })
        viewModel.createLabel("Label C", "#10b981", onSuccess = { labelC = it })
        advanceUntilIdle()

        // 1. Create task with Label A and Label B
        viewModel.createTask(
            title = "Task with labels",
            labels = listOf(labelA!!.id, labelB!!.id)
        )
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(listOf(labelA!!.id, labelB!!.id), task.labels)

        // 2. Edit task: unassign Label A, assign Label C -> [Label B, Label C]
        viewModel.updateTask(
            UpdateTaskParams(
                id = task.id,
                labels = listOf(labelB!!.id, labelC!!.id),
                expectedVersion = task.version
            )
        )
        advanceUntilIdle()

        val updatedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(listOf(labelB!!.id, labelC!!.id), updatedTask.labels)

        // 3. Complete task -> Completed view renders canonical labels [Label B, Label C]
        viewModel.completeTask(updatedTask.id)
        advanceUntilIdle()

        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
        val completedTask = (viewModel.completedState.value as CompletedUiState.Success).tasks[0]
        assertEquals(listOf(labelB!!.id, labelC!!.id), completedTask.labels)

        // 4. Uncomplete task -> Restored to inbox with intact label associations
        viewModel.uncompleteTask(completedTask.id)
        advanceUntilIdle()

        val restoredTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(listOf(labelB!!.id, labelC!!.id), restoredTask.labels)
    }

    @Test
    fun `proves Issue 44 AC 5 - Cross-client convergence after remote updates and later refetch`() = runTest {
        val aliceId = "550e8400-e29b-41d4-a716-446655440001"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createLabel("Web Label", "#a855f7")
        viewModel.createTask("Web Synchronized Task")
        advanceUntilIdle()

        val initialLabel = viewModel.labels.value[0]
        val initialTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(0, initialTask.labels.size)

        // Simulate web client creating a new label and associating it to the task in database
        val remoteLabelId = UUID.randomUUID().toString()
        labelDbRows.add(
            SimulatedLabelDbRow(
                id = remoteLabelId,
                operatorId = aliceId,
                name = "Web Created Label",
                color = "#14b8a6",
                createdAt = "2026-08-19T02:00:00Z",
                updatedAt = "2026-08-19T02:00:00Z"
            )
        )

        // Remote web client associates both labels to the task
        val taskIndex = dbRows.indexOfFirst { it.id == initialTask.id }
        dbRows[taskIndex] = dbRows[taskIndex].copy(
            labels = listOf(initialLabel.id, remoteLabelId),
            version = initialTask.version + 1,
            updatedAt = "2026-08-19T02:05:00Z"
        )

        // Android client refetches
        viewModel.loadTasks()
        advanceUntilIdle()

        // Android converges to the canonical state
        val convergedLabels = viewModel.labels.value
        assertEquals(2, convergedLabels.size)
        assertTrue(convergedLabels.any { it.id == remoteLabelId && it.name == "Web Created Label" })

        val convergedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, convergedTask.labels.size)
        assertTrue(convergedTask.labels.contains(initialLabel.id))
        assertTrue(convergedTask.labels.contains(remoteLabelId))
    }

    @Test
    fun `proves Criterion 5 & 6 & Issue 44 AC - Operator isolation between two Operators for labels and tasks`() = runTest {
        // Alice creates a task with a private label
        val aliceViewModel = createViewModel()
        advanceUntilIdle()
        aliceViewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        var aliceLabel: Label? = null
        aliceViewModel.createLabel("Alice Secret Label", "#ef4444", onSuccess = { aliceLabel = it })
        advanceUntilIdle()

        aliceViewModel.createTask("Alice Confidential Strategy", labels = listOf(aliceLabel!!.id))
        advanceUntilIdle()

        // Bob signs in
        val bobViewModel = createViewModel()
        advanceUntilIdle()
        bobViewModel.signInWithGoogleIdToken("google-token-bob")
        advanceUntilIdle()

        // Bob must NOT see Alice's task or Alice's labels
        assertTrue(bobViewModel.inboxState.value is InboxUiState.Empty)
        assertEquals(0, bobViewModel.labels.value.size)

        // Bob can create a label with the SAME name as Alice in his own isolated task space
        var bobLabel: Label? = null
        bobViewModel.createLabel("Alice Secret Label", "#3b82f6", onSuccess = { bobLabel = it })
        advanceUntilIdle()

        assertNotNull(bobLabel)
        assertNotEquals(aliceLabel!!.id, bobLabel!!.id) // Distinct UUIDs
        assertEquals("#3b82f6", bobLabel!!.color) // Bob's distinct color

        bobViewModel.createTask("Bob Project Review", labels = listOf(bobLabel!!.id))
        advanceUntilIdle()

        val bobSuccess = bobViewModel.inboxState.value
        assertTrue(bobSuccess is InboxUiState.Success)
        val bobTasks = (bobSuccess as InboxUiState.Success).tasks
        assertEquals(1, bobTasks.size)
        assertEquals("Bob Project Review", bobTasks[0].title)
        assertEquals(listOf(bobLabel!!.id), bobTasks[0].labels)

        // Unauthenticated direct call
        val unauthedSession = OperatorSession(
            operatorId = "hacker-uuid",
            email = "hacker@evil.com",
            accessToken = "invalid-token"
        )
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "anon")
        val taskService = SupabaseTaskService(config, OkHttpClient())
        val labelService = SupabaseLabelService(config, OkHttpClient())

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.fetchTasks(unauthedSession)
            }
        }

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                labelService.fetchLabels(unauthedSession)
            }
        }
    }
}
