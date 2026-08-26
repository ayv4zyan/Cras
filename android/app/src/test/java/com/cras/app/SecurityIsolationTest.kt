package com.cras.app

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.SupabaseCommentService
import com.cras.app.data.SupabaseTaskService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityIsolationTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var config: PublicSupabaseConfig
    private val httpClient = OkHttpClient()

    private val operatorAId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val operatorBId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    private val sessionA = OperatorSession(
        operatorId = operatorAId,
        email = "op.a@example.com",
        accessToken = "token-a"
    )
    private val sessionB = OperatorSession(
        operatorId = operatorBId,
        email = "op.b@example.com",
        accessToken = "token-b"
    )
    private val sessionAnon = OperatorSession(
        operatorId = "00000000-0000-0000-0000-000000000000",
        email = null,
        accessToken = "invalid-token"
    )

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        config = PublicSupabaseConfig(
            url = mockServer.url("/").toString().removeSuffix("/"),
            publishableKey = "dummy-anon-key"
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `unauthenticated caller cannot fetch or mutate tasks`() = runTest {
        val taskService = SupabaseTaskService(
            config = config,
            httpClient = httpClient
        )

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val authHeader = request.getHeader("Authorization")
                return if (authHeader == null || !authHeader.startsWith("Bearer token-")) {
                    MockResponse().setResponseCode(401).setBody("""{"message":"Invalid JWT / unauthenticated"}""")
                } else {
                    MockResponse().setResponseCode(200).setBody("[]")
                }
            }
        }

        val ex = assertFailsWith<IOException> {
            taskService.fetchTasks(sessionAnon)
        }
        assertTrue(
            "Expected 401 unauthenticated error, got: ${ex.message}",
            ex.message?.contains("401") == true || ex.message?.contains("unauthenticated") == true
        )
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun `two operators have strict data and mutation boundaries`() = runTest {
        val taskService = SupabaseTaskService(
            config = config,
            httpClient = httpClient
        )

        val taskBId = "bbbb1111-1111-1111-1111-111111111111"
        val matchedPaths = CopyOnWriteArrayList<String>()

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val authHeader = request.getHeader("Authorization") ?: ""
                val isOpA = authHeader == "Bearer token-a"
                val isOpB = authHeader == "Bearer token-b"

                val path = request.path ?: ""
                matchedPaths.add(path)

                if (path.contains("complete_task") || path.contains("update_task_details")) {
                    val body = request.body.readUtf8()
                    if (body.contains(taskBId)) {
                        return if (isOpB) {
                            MockResponse().setResponseCode(200).setBody("""{"id":"$taskBId","title":"B Task","description":null,"priority":4,"plan":null,"labels":[],"parentId":null,"completedAt":"2026-08-20T00:00:00Z","createdAt":"2026-08-19T00:00:00Z","updatedAt":"2026-08-20T00:00:00Z","version":2}""")
                        } else {
                            MockResponse().setResponseCode(404).setBody("""{"message":"Task not found or owned by another Operator"}""")
                        }
                    }
                }

                return MockResponse().setResponseCode(200).setBody("[]")
            }
        }

        // Operator A attempting to complete Operator B's task must fail with 404
        val exA = assertFailsWith<IOException> {
            taskService.completeTask(sessionA, taskBId, 1)
        }
        assertTrue(
            "Expected 404 cross-operator rejection, got: ${exA.message}",
            exA.message?.contains("404") == true || exA.message?.contains("owned by another Operator") == true
        )

        // Operator B completing own task succeeds
        val completed = taskService.completeTask(sessionB, taskBId, 1)
        assertNotNull(completed)
        assertEquals(taskBId, completed.id)

        assertTrue("Expected complete_task request to have been observed", matchedPaths.any { it.contains("complete_task") })
        assertEquals(2, mockServer.requestCount)
    }

    @Test
    fun `cross-operator subtask and comment relationships are rejected`() = runTest {
        val commentService = SupabaseCommentService(
            config = config,
            httpClient = httpClient
        )

        val taskBId = "bbbb1111-1111-1111-1111-111111111111"
        val matchedPaths = CopyOnWriteArrayList<String>()

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val authHeader = request.getHeader("Authorization") ?: ""
                val isOpA = authHeader == "Bearer token-a"
                val path = request.path ?: ""
                matchedPaths.add(path)

                if (path.contains("create_comment")) {
                    val body = request.body.readUtf8()
                    if (body.contains(taskBId) && isOpA) {
                        return MockResponse().setResponseCode(403).setBody("""{"message":"Cross-operator foreign key violation"}""")
                    }
                }
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }

        val ex = assertFailsWith<IOException> {
            commentService.createComment(
                sessionA,
                CreateCommentParams(
                    id = UUID.randomUUID().toString(),
                    taskId = taskBId,
                    content = "Illegal cross-operator comment"
                )
            )
        }
        assertTrue(
            "Expected 403 cross-operator rejection, got: ${ex.message}",
            ex.message?.contains("403") == true || ex.message?.contains("Cross-operator") == true
        )
        assertTrue("Expected create_comment request to have been observed", matchedPaths.any { it.contains("create_comment") })
        assertEquals(1, mockServer.requestCount)
    }
}
