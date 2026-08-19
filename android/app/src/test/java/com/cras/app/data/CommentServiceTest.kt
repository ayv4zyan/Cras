package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.Comment
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommentServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var commentService: CommentService
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
        commentService = SupabaseCommentService(
            config = config,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchComments sends headers with api profile and decodes valid comments`() = runTest {
        val commentsJson = """
            [
                {
                    "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "taskId": "550e8400-e29b-41d4-a716-446655440010",
                    "content": "Verified the staging run logs and all services healthy.",
                    "createdAt": "2026-08-19T10:00:00Z"
                }
            ]
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(commentsJson)
        )

        val result = commentService.fetchComments(testSession)
        assertEquals(1, result.size)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", result[0].id)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", result[0].taskId)
        assertEquals("Verified the staging run logs and all services healthy.", result[0].content)
        assertEquals("2026-08-19T10:00:00Z", result[0].createdAt)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/comments?select=*", request.path)
        assertEquals("GET", request.method)
        assertEquals("api", request.getHeader("Accept-Profile"))
        assertEquals("Bearer operator-alice-jwt", request.getHeader("Authorization"))
    }

    @Test
    fun `fetchComments with taskId query parameter sends taskId filter`() = runTest {
        val commentsJson = """
            [
                {
                    "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "taskId": "550e8400-e29b-41d4-a716-446655440010",
                    "content": "A task comment",
                    "createdAt": "2026-08-19T10:00:00Z"
                }
            ]
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(commentsJson)
        )

        val result = commentService.fetchComments(testSession, taskId = "550e8400-e29b-41d4-a716-446655440010")
        assertEquals(1, result.size)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", result[0].taskId)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/comments?select=*&taskId=eq.550e8400-e29b-41d4-a716-446655440010", request.path)
        assertEquals("GET", request.method)
        assertEquals("api", request.getHeader("Accept-Profile"))
    }

    @Test
    fun `createComment sends api create_comment RPC payload and validates returned comment`() = runTest {
        val createdJson = """
            {
                "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "taskId": "550e8400-e29b-41d4-a716-446655440010",
                "content": "New observation note",
                "createdAt": "2026-08-19T11:00:00Z"
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(createdJson)
        )

        val comment = commentService.createComment(
            session = testSession,
            params = CreateCommentParams(
                taskId = "550e8400-e29b-41d4-a716-446655440010",
                content = "New observation note"
            )
        )

        assertNotNull(comment)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", comment.id)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", comment.taskId)
        assertEquals("New observation note", comment.content)
        assertEquals("2026-08-19T11:00:00Z", comment.createdAt)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/create_comment", request.path)
        assertEquals("POST", request.method)
        assertEquals("api", request.getHeader("Content-Profile"))
        assertEquals("api", request.getHeader("Accept-Profile"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("\"task_id\":\"550e8400-e29b-41d4-a716-446655440010\""))
        assertTrue(body.contains("\"content\":\"New observation note\""))
    }

    @Test
    fun `createComment rejects empty content or empty taskId before network call`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                commentService.createComment(
                    session = testSession,
                    params = CreateCommentParams(
                        taskId = "550e8400-e29b-41d4-a716-446655440010",
                        content = "   "
                    )
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                commentService.createComment(
                    session = testSession,
                    params = CreateCommentParams(
                        taskId = "   ",
                        content = "Valid content"
                    )
                )
            }
        }

        assertEquals(0, mockWebServer.requestCount)
    }
}
