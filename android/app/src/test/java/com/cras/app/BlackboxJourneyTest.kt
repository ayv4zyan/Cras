package com.cras.app

import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.config.getPublicSupabaseConfig
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.SupabaseTaskService
import com.cras.app.data.UpdateTaskParams
import com.cras.app.models.Plan
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
import kotlinx.serialization.json.encodeToJsonElement
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

    private lateinit var mockWebServer: MockWebServer
    private val dbRows = mutableListOf<SimulatedDbRow>()
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dbRows.clear()

        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
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

                    val newRow = SimulatedDbRow(
                        id = UUID.randomUUID().toString(),
                        operatorId = callerOperatorId,
                        title = title.trim(),
                        description = reqJson["description"]?.jsonPrimitive?.content,
                        priority = reqJson["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                        plan = null,
                        labels = emptyList(),
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

                    val updatedRow = existing.copy(
                        title = newTitle?.trim() ?: existing.title,
                        description = if (reqJson.containsKey("description")) reqJson["description"]?.jsonPrimitive?.content else existing.description,
                        priority = newPriority ?: existing.priority,
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
        return InboxViewModel(authService, taskService)
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
    fun `proves Criterion 5 & 6 - Operator isolation between two Operators and unauthenticated callers`() = runTest {
        // Alice creates a task
        val aliceViewModel = createViewModel()
        advanceUntilIdle()
        aliceViewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()
        aliceViewModel.createTask("Alice Confidential Strategy")
        advanceUntilIdle()

        // Bob signs in
        val bobViewModel = createViewModel()
        advanceUntilIdle()
        bobViewModel.signInWithGoogleIdToken("google-token-bob")
        advanceUntilIdle()

        val bobState = bobViewModel.inboxState.value
        // Bob must NOT see Alice's task
        assertTrue(bobState is InboxUiState.Empty)

        bobViewModel.createTask("Bob Project Review")
        advanceUntilIdle()

        val bobSuccess = bobViewModel.inboxState.value
        assertTrue(bobSuccess is InboxUiState.Success)
        val bobTasks = (bobSuccess as InboxUiState.Success).tasks
        assertEquals(1, bobTasks.size)
        assertEquals("Bob Project Review", bobTasks[0].title)

        // Unauthenticated direct call
        val unauthedSession = OperatorSession(
            operatorId = "hacker-uuid",
            email = "hacker@evil.com",
            accessToken = "invalid-token"
        )
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "anon")
        val taskService = SupabaseTaskService(config, OkHttpClient())

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.fetchTasks(unauthedSession)
            }
        }
    }
}
