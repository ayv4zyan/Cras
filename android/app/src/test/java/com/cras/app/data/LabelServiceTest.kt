package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.Label
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
import java.io.IOException

class LabelServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var labelService: LabelService
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
        labelService = SupabaseLabelService(
            config = config,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchLabels sends GET request and decodes list of labels ordered by created_at`() = runTest {
        val labelsJson = """
            [
                {
                    "id": "22222222-2222-2222-2222-222222222221",
                    "name": "Backend",
                    "color": "#3b82f6",
                    "created_at": "2026-08-19T00:00:00Z",
                    "updated_at": "2026-08-19T00:00:00Z"
                },
                {
                    "id": "22222222-2222-2222-2222-222222222222",
                    "name": "Frontend",
                    "color": "#10b981",
                    "created_at": "2026-08-19T01:00:00Z",
                    "updated_at": "2026-08-19T01:00:00Z"
                }
            ]
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(labelsJson)
        )

        val result = labelService.fetchLabels(testSession)
        assertEquals(2, result.size)
        assertEquals("Backend", result[0].name)
        assertEquals("#3b82f6", result[0].color)
        assertEquals("Frontend", result[1].name)
        assertEquals("#10b981", result[1].color)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/labels?select=*&order=created_at.asc", request.path)
        assertEquals("GET", request.method)
        assertEquals("test-anon-key", request.getHeader("apikey"))
        assertEquals("Bearer operator-alice-jwt", request.getHeader("Authorization"))
    }

    @Test
    fun `createLabel sends POST request with name and color and parses response`() = runTest {
        val createdJson = """
            [
                {
                    "id": "22222222-2222-2222-2222-222222222223",
                    "name": "Urgent",
                    "color": "#ef4444",
                    "created_at": "2026-08-19T02:00:00Z",
                    "updated_at": "2026-08-19T02:00:00Z"
                }
            ]
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(createdJson)
        )

        val label = labelService.createLabel(
            session = testSession,
            params = CreateLabelParams(name = "Urgent", color = "#ef4444")
        )

        assertNotNull(label)
        assertEquals("22222222-2222-2222-2222-222222222223", label.id)
        assertEquals("Urgent", label.name)
        assertEquals("#ef4444", label.color)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/labels", request.path)
        assertEquals("POST", request.method)
        assertEquals("return=representation", request.getHeader("Prefer"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"name\":\"Urgent\""))
        assertTrue(body.contains("\"color\":\"#ef4444\""))
    }

    @Test
    fun `createLabel throws clear error on duplicate label name conflict`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"23505","message":"duplicate key value violates unique constraint \"uq_labels_name_operator\""}""")
        )

        val exception = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                labelService.createLabel(
                    session = testSession,
                    params = CreateLabelParams(name = "Urgent", color = "#ef4444")
                )
            }
        }

        assertEquals("A label with this name already exists", exception.message)
    }

    @Test
    fun `createLabel rejects empty name or color before network call`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                labelService.createLabel(
                    session = testSession,
                    params = CreateLabelParams(name = "   ", color = "#ef4444")
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                labelService.createLabel(
                    session = testSession,
                    params = CreateLabelParams(name = "Urgent", color = "   ")
                )
            }
        }
        assertEquals(0, mockWebServer.requestCount)
    }

    @Test
    fun `updateLabel sends PATCH request with updated fields`() = runTest {
        val updatedJson = """
            [
                {
                    "id": "22222222-2222-2222-2222-222222222223",
                    "name": "Critical",
                    "color": "#f97316",
                    "created_at": "2026-08-19T02:00:00Z",
                    "updated_at": "2026-08-19T02:30:00Z"
                }
            ]
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(updatedJson)
        )

        val updated = labelService.updateLabel(
            session = testSession,
            params = UpdateLabelParams(
                id = "22222222-2222-2222-2222-222222222223",
                name = "Critical",
                color = "#f97316"
            )
        )

        assertEquals("Critical", updated.name)
        assertEquals("#f97316", updated.color)
        assertEquals("22222222-2222-2222-2222-222222222223", updated.id)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/labels?id=eq.22222222-2222-2222-2222-222222222223", request.path)
        assertEquals("PATCH", request.method)
        assertEquals("return=representation", request.getHeader("Prefer"))
    }

    @Test
    fun `updateLabel throws clear error on duplicate label name conflict`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"23505","message":"duplicate key value violates unique constraint \"uq_labels_name_operator\""}""")
        )

        val exception = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                labelService.updateLabel(
                    session = testSession,
                    params = UpdateLabelParams(
                        id = "22222222-2222-2222-2222-222222222223",
                        name = "ExistingName"
                    )
                )
            }
        }

        assertEquals("A label with this name already exists", exception.message)
    }

    @Test
    fun `deleteLabel sends DELETE request`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(204)
        )

        labelService.deleteLabel(
            session = testSession,
            labelId = "22222222-2222-2222-2222-222222222223"
        )

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/labels?id=eq.22222222-2222-2222-2222-222222222223", request.path)
        assertEquals("DELETE", request.method)
    }
}
