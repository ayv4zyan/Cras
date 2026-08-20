package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RealtimeServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var realtimeService: RealtimeService
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
        realtimeService = SupabaseRealtimeService(
            config = config,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
    }

    @Test
    fun `parseInvalidationPayload parses valid task invalidation payload`() {
        val jsonStr = """
            {
                "resource": "task",
                "id": "550e8400-e29b-41d4-a716-446655440010",
                "operation": "created",
                "parentId": "550e8400-e29b-41d4-a716-446655440000"
            }
        """.trimIndent()

        val parsed = parseInvalidationPayload(jsonStr)
        assertNotNull(parsed)
        assertEquals("task", parsed?.resource)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", parsed?.id)
        assertEquals("created", parsed?.operation)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", parsed?.parentId)
        assertNull(parsed?.taskId)
    }

    @Test
    fun `parseInvalidationPayload parses valid comment invalidation payload with taskId`() {
        val jsonStr = """
            {
                "resource": "comment",
                "id": "550e8400-e29b-41d4-a716-446655440020",
                "operation": "created",
                "taskId": "550e8400-e29b-41d4-a716-446655440010"
            }
        """.trimIndent()

        val parsed = parseInvalidationPayload(jsonStr)
        assertNotNull(parsed)
        assertEquals("comment", parsed?.resource)
        assertEquals("550e8400-e29b-41d4-a716-446655440020", parsed?.id)
        assertEquals("created", parsed?.operation)
        assertNull(parsed?.parentId)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", parsed?.taskId)
    }

    @Test
    fun `parseInvalidationPayload parses valid label invalidation payload`() {
        val jsonStr = """
            {
                "resource": "label",
                "id": "550e8400-e29b-41d4-a716-446655440030",
                "operation": "updated"
            }
        """.trimIndent()

        val parsed = parseInvalidationPayload(jsonStr)
        assertNotNull(parsed)
        assertEquals("label", parsed?.resource)
        assertEquals("550e8400-e29b-41d4-a716-446655440030", parsed?.id)
        assertEquals("updated", parsed?.operation)
        assertNull(parsed?.parentId)
        assertNull(parsed?.taskId)
    }

    @Test
    fun `parseInvalidationPayload strips extraneous sensitive fields`() {
        val jsonWithLeaks = """
            {
                "resource": "task",
                "id": "550e8400-e29b-41d4-a716-446655440010",
                "operation": "updated",
                "title": "Secret Task Title",
                "description": "Sensitive note contents",
                "email": "confidential@example.com"
            }
        """.trimIndent()

        val parsed = parseInvalidationPayload(jsonWithLeaks)
        assertNotNull(parsed)
        assertEquals("task", parsed?.resource)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", parsed?.id)
        assertEquals("updated", parsed?.operation)
    }

    @Test
    fun `parseInvalidationPayload returns null for invalid or missing inputs`() {
        assertNull(parseInvalidationPayload(null as String?))
        assertNull(parseInvalidationPayload(""))
        assertNull(parseInvalidationPayload("not json"))
        assertNull(parseInvalidationPayload("""{"resource": "unknown", "id": "123", "operation": "created"}"""))
        assertNull(parseInvalidationPayload("""{"resource": "task", "id": "", "operation": "created"}"""))
        assertNull(parseInvalidationPayload("""{"resource": "task", "id": "123", "operation": "invalid_op"}"""))
    }

    @Test
    fun `subscribeToInvalidations sends phx_join for authorized operator channel and receives invalidation broadcast`() {
        val serverReceivedMessages = CopyOnWriteArrayList<String>()
        val serverSocketLatch = CountDownLatch(1)
        val joinLatch = CountDownLatch(1)
        val serverWebSocket = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)

        mockWebServer.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverWebSocket.set(webSocket)
                    serverSocketLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    serverReceivedMessages.add(text)
                    if (text.contains("phx_join")) {
                        joinLatch.countDown()
                    }
                }
            })
        )

        val receivedInvalidations = mutableListOf<InvalidationPayload>()
        val invalidationLatch = CountDownLatch(1)

        val subscription = realtimeService.subscribeToInvalidations(
            session = testSession,
            onInvalidate = { payload ->
                receivedInvalidations.add(payload)
                invalidationLatch.countDown()
            }
        )

        assertTrue("Server socket opened", serverSocketLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Client sent phx_join", joinLatch.await(5, TimeUnit.SECONDS))

        // Verify join message contains expected operator topic, token, and private config
        val joinMsg = serverReceivedMessages.firstOrNull { it.contains("phx_join") }
        assertNotNull(joinMsg)
        val json = Json.parseToJsonElement(joinMsg!!).jsonObject
        assertEquals("realtime:operator:550e8400-e29b-41d4-a716-446655440001", json["topic"]?.jsonPrimitive?.content)
        assertEquals("phx_join", json["event"]?.jsonPrimitive?.content)

        val payload = json["payload"]?.jsonObject
        assertEquals("operator-alice-jwt", payload?.get("access_token")?.jsonPrimitive?.content)
        val config = payload?.get("config")?.jsonObject
        assertEquals("true", config?.get("private")?.jsonPrimitive?.content)

        // Server pushes a broadcast event
        val broadcastEvent = """
            {
                "topic": "realtime:operator:550e8400-e29b-41d4-a716-446655440001",
                "event": "broadcast",
                "payload": {
                    "type": "broadcast",
                    "event": "invalidate",
                    "payload": {
                        "resource": "task",
                        "id": "550e8400-e29b-41d4-a716-446655440010",
                        "operation": "updated"
                    }
                }
            }
        """.trimIndent()

        serverWebSocket.get()?.send(broadcastEvent)

        assertTrue("Client received invalidation", invalidationLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, receivedInvalidations.size)
        assertEquals("task", receivedInvalidations[0].resource)
        assertEquals("550e8400-e29b-41d4-a716-446655440010", receivedInvalidations[0].id)
        assertEquals("updated", receivedInvalidations[0].operation)

        subscription.unsubscribe()
    }
}
