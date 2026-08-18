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
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.createTask(
                    session = testSession,
                    params = CreateTaskParams(title = "   ")
                )
            }
        }
        assertEquals(0, mockWebServer.requestCount)
    }
}
