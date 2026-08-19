package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.Task
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `completeTask sends api complete_task RPC payload with task id and timestamp`() = runTest {
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
            completedAt = "2026-08-19T10:00:00Z"
        )

        assertNotNull(task)
        assertEquals("2026-08-19T10:00:00Z", task.completedAt)
        assertEquals(2, task.version)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/complete_task", request.path)
        assertEquals("POST", request.method)
        assertEquals("api", request.getHeader("Content-Profile"))
    }

    @Test
    fun `uncompleteTask sends api uncomplete_task RPC payload with task id`() = runTest {
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
            taskId = "550e8400-e29b-41d4-a716-446655440011"
        )

        assertNotNull(task)
        assertNull(task.completedAt)
        assertEquals(3, task.version)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/uncomplete_task", request.path)
        assertEquals("POST", request.method)
        assertEquals("api", request.getHeader("Content-Profile"))
    }
}
