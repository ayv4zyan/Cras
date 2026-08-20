package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var taskService: TaskService
    private val testSession = OperatorSession(
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "alice@cras.app",
        accessToken = "operator-alice-jwt"
    )

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(
            url = baseUrl,
            publishableKey = "test-anon-key"
        )
        taskService = SupabaseTaskService(
            config = config,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchTasks sends headers with api profile and decodes valid tasks`() = runTest {
        val tasksJson = """
            [
                {
                    "id": "550e8400-e29b-41d4-a716-446655440010",
                    "title": "Buy oat milk",
                    "description": null,
                    "priority": 4,
                    "plan": null,
                    "labels": [],
                    "parentId": null,
                    "completedAt": null,
                    "createdAt": "2026-08-19T00:00:00Z",
                    "updatedAt": "2026-08-19T00:00:00Z",
                    "version": 1
                }
            ]
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(tasksJson)
        )

        val result = taskService.fetchTasks(testSession)
        assertEquals(1, result.size)
        assertEquals("Buy oat milk", result[0].title)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", result[0].id)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/tasks?select=*", request.path)
        assertEquals("GET", request.method)
        assertEquals("api", request.getHeader("Accept-Profile"))
        assertEquals("Bearer operator-alice-jwt", request.getHeader("Authorization"))
    }

    @Test
    fun `fetchTaskById sends query filter and returns single task`() = runTest {
        val tasksJson = """
            [
                {
                    "id": "550e8400-e29b-41d4-a716-446655440010",
                    "title": "Buy oat milk",
                    "description": null,
                    "priority": 4,
                    "plan": null,
                    "labels": [],
                    "parentId": null,
                    "completedAt": null,
                    "createdAt": "2026-08-19T00:00:00Z",
                    "updatedAt": "2026-08-19T00:00:00Z",
                    "version": 1
                }
            ]
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(tasksJson)
        )

        val result = taskService.fetchTaskById(testSession, "550e8400-e29b-41d4-a716-446655440010")
        assertNotNull(result)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", result?.id)
        assertEquals("Buy oat milk", result?.title)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/tasks?id=eq.550e8400-e29b-41d4-a716-446655440010&select=*", request.path)
        assertEquals("GET", request.method)
    }

    @Test
    fun `createTask sends api create_task RPC payload and validates returned task`() = runTest {
        val createdJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Clean desk",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": [],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:00:00Z",
                "version": 1
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(createdJson)
        )

        val task = taskService.createTask(
            session = testSession,
            params = CreateTaskParams(title = "Clean desk")
        )

        assertNotNull(task)
        assertEquals("Clean desk", task.title)
        assertEquals("550e8400-e29b-41d4-a716-446655440011", task.id)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/create_task", request.path)
        assertEquals("POST", request.method)
        assertEquals("api", request.getHeader("Content-Profile"))
        assertEquals("api", request.getHeader("Accept-Profile"))
    }

    @Test
    fun `createTask with plan sends plan in RPC payload`() = runTest {
        val createdJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Floating meeting",
                "description": null,
                "priority": 4,
                "plan": {"type":"floating","date":"2026-08-19","time":"14:00"},
                "labels": [],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:00:00Z",
                "version": 1
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(createdJson)
        )

        val task = taskService.createTask(
            session = testSession,
            params = CreateTaskParams(
                title = "Floating meeting",
                plan = Plan.Floating("2026-08-19", "14:00")
            )
        )

        assertNotNull(task)
        assertTrue(task.plan is Plan.Floating)

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("/rest/v1/rpc/create_task", request.path)
        assertTrue(body.contains("\"plan\":{\"type\":\"floating\",\"date\":\"2026-08-19\",\"time\":\"14:00\"}"))
    }

    @Test
    fun `createTask rejects empty title before network call`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.createTask(
                    session = testSession,
                    params = CreateTaskParams(title = "   ")
                )
            }
        }
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `createTask rejects invalid or duplicate label IDs before network call`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.createTask(
                    session = testSession,
                    params = CreateTaskParams(
                        title = "Invalid label task",
                        labels = listOf("not-a-uuid")
                    )
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.createTask(
                    session = testSession,
                    params = CreateTaskParams(
                        title = "Duplicate label task",
                        labels = listOf(
                            "22222222-2222-2222-2222-222222222222",
                            "22222222-2222-2222-2222-222222222222"
                        )
                    )
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.createTask(
                    session = testSession,
                    params = CreateTaskParams(
                        title = "Mixed-case duplicate label task",
                        labels = listOf(
                            "22222222-2222-2222-2222-222222222222",
                            "22222222-2222-2222-2222-222222222222".uppercase()
                        )
                    )
                )
            }
        }
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `createTask normalizes uppercase label UUIDs to lowercase`() = runTest {
        val createdJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Uppercase label task",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": ["22222222-2222-2222-2222-222222222222"],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:00:00Z",
                "version": 1
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(createdJson)
        )

        val task = taskService.createTask(
            session = testSession,
            params = CreateTaskParams(
                title = "Uppercase label task",
                labels = listOf("22222222-2222-2222-2222-222222222222".uppercase())
            )
        )

        assertNotNull(task)
        val request = mockWebServer.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val labelsArray = body["labels"]
        assertEquals("[\"22222222-2222-2222-2222-222222222222\"]", labelsArray.toString())
    }

    @Test
    fun `updateTask sends api update_task RPC payload and validates returned task`() = runTest {
        val updatedJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Updated desk cleaning",
                "description": "Thoroughly wipe down surface",
                "priority": 1,
                "plan": null,
                "labels": [],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:10:00Z",
                "version": 2
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(updatedJson)
        )

        val task = taskService.updateTask(
            session = testSession,
            params = UpdateTaskParams(
                id = "550e8400-e29b-41d4-a716-446655440011",
                title = "Updated desk cleaning",
                description = "Thoroughly wipe down surface",
                priority = 1,
                expectedVersion = 1
            )
        )

        assertNotNull(task)
        assertEquals("Updated desk cleaning", task.title)
        assertEquals("Thoroughly wipe down surface", task.description)
        assertEquals(1, task.priority)
        assertEquals(2, task.version)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/update_task", request.path)
        assertEquals("POST", request.method)
        assertEquals("api", request.getHeader("Content-Profile"))
        assertEquals("api", request.getHeader("Accept-Profile"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("550e8400-e29b-41d4-a716-446655440011", body["id"]?.jsonPrimitive?.content)
        assertEquals("Updated desk cleaning", body["title"]?.jsonPrimitive?.content)
        assertEquals("Thoroughly wipe down surface", body["description"]?.jsonPrimitive?.content)
        assertEquals(1, body["priority"]?.jsonPrimitive?.int)
        assertEquals(1, body["expected_version"]?.jsonPrimitive?.int)
    }

    @Test
    fun `updateTask throws TaskConflictException on version conflict rejection`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"P0003","message":"Task version conflict: expected 1, found 2"}""")
        )

        val exception = assertThrows(TaskConflictException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.updateTask(
                    session = testSession,
                    params = UpdateTaskParams(
                        id = "550e8400-e29b-41d4-a716-446655440011",
                        title = "Stale Edit",
                        expectedVersion = 1
                    )
                )
            }
        }

        assertEquals("P0003", exception.code)
        assertTrue(exception.message!!.contains("Task version conflict"))
    }

    @Test
    fun `updateTask with plan or clearPlan sends plan and clear_plan in RPC payload`() = runTest {
        val updatedJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Updated task",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": [],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:10:00Z",
                "version": 2
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(updatedJson)
        )

        val task = taskService.updateTask(
            session = testSession,
            params = UpdateTaskParams(
                id = "550e8400-e29b-41d4-a716-446655440011",
                clearPlan = true
            )
        )

        assertNotNull(task)
        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("/rest/v1/rpc/update_task", request.path)
        assertTrue(body.contains("\"clear_plan\":true"))
    }

    @Test
    fun `updateTask with clearDescription sends clear_description in RPC payload`() = runTest {
        val updatedJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Updated task",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": [],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:10:00Z",
                "version": 2
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(updatedJson)
        )

        val task = taskService.updateTask(
            session = testSession,
            params = UpdateTaskParams(
                id = "550e8400-e29b-41d4-a716-446655440011",
                clearDescription = true
            )
        )

        assertNotNull(task)
        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("/rest/v1/rpc/update_task", request.path)
        assertTrue(body.contains("\"clear_description\":true"))
    }

    @Test
    fun `updateTask with labels sends labels array in RPC payload`() = runTest {
        val updatedJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Labeled task",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": ["22222222-2222-2222-2222-222222222222"],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:10:00Z",
                "version": 2
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(updatedJson)
        )

        val task = taskService.updateTask(
            session = testSession,
            params = UpdateTaskParams(
                id = "550e8400-e29b-41d4-a716-446655440011",
                labels = listOf("22222222-2222-2222-2222-222222222222")
            )
        )

        assertNotNull(task)
        assertEquals(1, task.labels.size)
        assertEquals("22222222-2222-2222-2222-222222222222", task.labels[0])

        val request = mockWebServer.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("/rest/v1/rpc/update_task", request.path)
        assertEquals(true, body.contains("\"labels\":[\"22222222-2222-2222-2222-222222222222\"]"))
    }

    @Test
    fun `updateTask rejects empty title or invalid priority before network call`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.updateTask(
                    session = testSession,
                    params = UpdateTaskParams(
                        id = "550e8400-e29b-41d4-a716-446655440011",
                        title = "  "
                    )
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.updateTask(
                    session = testSession,
                    params = UpdateTaskParams(
                        id = "550e8400-e29b-41d4-a716-446655440011",
                        priority = 5
                    )
                )
            }
        }

        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `updateTask rejects invalid or duplicate label IDs before network call`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.updateTask(
                    session = testSession,
                    params = UpdateTaskParams(
                        id = "550e8400-e29b-41d4-a716-446655440011",
                        labels = listOf("not-a-uuid")
                    )
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.updateTask(
                    session = testSession,
                    params = UpdateTaskParams(
                        id = "550e8400-e29b-41d4-a716-446655440011",
                        labels = listOf(
                            "22222222-2222-2222-2222-222222222222",
                            "22222222-2222-2222-2222-222222222222"
                        )
                    )
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.updateTask(
                    session = testSession,
                    params = UpdateTaskParams(
                        id = "550e8400-e29b-41d4-a716-446655440011",
                        labels = listOf(
                            "22222222-2222-2222-2222-222222222222",
                            "22222222-2222-2222-2222-222222222222".uppercase()
                        )
                    )
                )
            }
        }

        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `updateTask normalizes uppercase label UUIDs to lowercase`() = runTest {
        val updatedJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Labeled task",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": ["22222222-2222-2222-2222-222222222222"],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:10:00Z",
                "version": 2
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(updatedJson)
        )

        val task = taskService.updateTask(
            session = testSession,
            params = UpdateTaskParams(
                id = "550e8400-e29b-41d4-a716-446655440011",
                labels = listOf("22222222-2222-2222-2222-222222222222".uppercase())
            )
        )

        assertNotNull(task)
        val request = mockWebServer.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val labelsArray = body["labels"]
        assertEquals("[\"22222222-2222-2222-2222-222222222222\"]", labelsArray.toString())
    }

    @Test
    fun `completeTask sends api complete_task RPC payload with expected_version`() = runTest {
        val completedJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Clean desk",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": [],
                "parentId": null,
                "completedAt": "2026-08-19T10:00:00Z",
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T10:00:00Z",
                "version": 2
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(completedJson)
        )

        val task = taskService.completeTask(
            session = testSession,
            taskId = "550e8400-e29b-41d4-a716-446655440011",
            expectedVersion = 1,
            completedAt = "2026-08-19T10:00:00Z"
        )

        assertNotNull(task)
        assertEquals("2026-08-19T10:00:00Z", task.completedAt)
        assertEquals(2, task.version)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/complete_task", request.path)
        assertEquals("POST", request.method)
        assertEquals("api", request.getHeader("Content-Profile"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("550e8400-e29b-41d4-a716-446655440011", body["id"]?.jsonPrimitive?.content)
        assertEquals(1, body["expected_version"]?.jsonPrimitive?.int)
        assertEquals("2026-08-19T10:00:00Z", body["completed_at"]?.jsonPrimitive?.content)
    }

    @Test
    fun `completeTask throws TaskConflictException on version conflict rejection`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"P0003","message":"Task version conflict: expected 1, found 2"}""")
        )

        val exception = assertThrows(TaskConflictException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.completeTask(
                    session = testSession,
                    taskId = "550e8400-e29b-41d4-a716-446655440011",
                    expectedVersion = 1
                )
            }
        }

        assertEquals("P0003", exception.code)
    }

    @Test
    fun `uncompleteTask sends api uncomplete_task RPC payload with expected_version`() = runTest {
        val uncompletedJson = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440011",
                "title": "Clean desk",
                "description": null,
                "priority": 4,
                "plan": null,
                "labels": [],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T10:05:00Z",
                "version": 3
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(uncompletedJson)
        )

        val task = taskService.uncompleteTask(
            session = testSession,
            taskId = "550e8400-e29b-41d4-a716-446655440011",
            expectedVersion = 2
        )

        assertNotNull(task)
        assertNull(task.completedAt)
        assertEquals(3, task.version)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/uncomplete_task", request.path)
        assertEquals("POST", request.method)
        assertEquals("api", request.getHeader("Content-Profile"))

        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("550e8400-e29b-41d4-a716-446655440011", body["id"]?.jsonPrimitive?.content)
        assertEquals(2, body["expected_version"]?.jsonPrimitive?.int)
    }

    @Test
    fun `uncompleteTask throws TaskConflictException on version conflict rejection`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"P0003","message":"Task version conflict: expected 2, found 3"}""")
        )

        val exception = assertThrows(TaskConflictException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.uncompleteTask(
                    session = testSession,
                    taskId = "550e8400-e29b-41d4-a716-446655440011",
                    expectedVersion = 2
                )
            }
        }

        assertEquals("P0003", exception.code)
    }
}
