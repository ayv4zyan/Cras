package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class InstallationServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private val session = OperatorSession(
        accessToken = "test-token",
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "alice@cras.app"
    )
    private val installationId = "660e8400-e29b-41d4-a716-446655440002"

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun createService(): InstallationService {
        val url = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = url, publishableKey = "sb_key")
        return SupabaseInstallationService(config, OkHttpClient())
    }

    private fun enqueueInstallationRow(endpoint: String? = "fcm-registration-token") {
        val endpointJson = endpoint?.let { "\"$it\"" } ?: "null"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "$installationId",
                      "operator_id": "${session.operatorId}",
                      "platform": "android",
                      "local_enabled": true,
                      "permission_state": "granted",
                      "endpoint": $endpointJson,
                      "installation_timezone": "Europe/Berlin",
                      "timezone_observed_at": "2026-08-22T00:00:00+00:00",
                      "is_active": true,
                      "created_at": "2026-08-22T00:00:00+00:00",
                      "updated_at": "2026-08-22T00:00:00+00:00"
                    }
                    """.trimIndent()
                )
        )
    }

    @Test
    fun `registerOrUpdate posts android platform registration token as endpoint`() = runTest {
        enqueueInstallationRow()
        val service = createService()

        val record = service.registerOrUpdate(
            session,
            RegisterInstallationParams(
                id = installationId,
                localEnabled = true,
                permissionState = "granted",
                endpoint = "fcm-registration-token",
                installationTimezone = "Europe/Berlin"
            )
        )

        val recorded = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/register_or_update_installation", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("sb_key", recorded.getHeader("apikey"))
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))

        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(installationId, body["p_id"]!!.jsonPrimitive.content)
        assertEquals("android", body["p_platform"]!!.jsonPrimitive.content)
        assertEquals("true", body["p_local_enabled"]!!.jsonPrimitive.content)
        assertEquals("granted", body["p_permission_state"]!!.jsonPrimitive.content)
        assertEquals("fcm-registration-token", body["p_endpoint"]!!.jsonPrimitive.content)
        assertEquals("Europe/Berlin", body["p_installation_timezone"]!!.jsonPrimitive.content)
        assertEquals("false", body["p_clear_subscription"]!!.jsonPrimitive.content)

        assertEquals(installationId, record?.id)
        assertTrue(record?.isActive == true)
        assertEquals("fcm-registration-token", record?.endpoint)
    }

    @Test
    fun `registerOrUpdate can clear the subscription on endpoint loss`() = runTest {
        enqueueInstallationRow(endpoint = null)
        val service = createService()

        val record = service.registerOrUpdate(
            session,
            RegisterInstallationParams(
                id = installationId,
                localEnabled = true,
                permissionState = "granted",
                endpoint = null,
                installationTimezone = "UTC"
            )
        )

        val body = Json.parseToJsonElement(mockWebServer.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body["p_endpoint"] is JsonNull)
        assertEquals("true", body["p_clear_subscription"]!!.jsonPrimitive.content)
        assertFalse(record?.isActive == false)
        assertNull(record?.endpoint)
    }

    @Test
    fun `registerOrUpdate surfaces server-side deactivation after provider rejection`() = runTest {
        // Permanent FCM rejection disabled the endpoint server-side; reconcile must see it.
        val url = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = url, publishableKey = "sb_key")
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": "$installationId",
                      "platform": "android",
                      "local_enabled": true,
                      "permission_state": "granted",
                      "endpoint": null,
                      "installation_timezone": "UTC",
                      "is_active": false
                    }
                    """.trimIndent()
                )
        )
        val service = SupabaseInstallationService(config, OkHttpClient())

        val record = service.registerOrUpdate(
            session,
            RegisterInstallationParams(
                id = installationId,
                localEnabled = true,
                permissionState = "granted",
                endpoint = "stale-token",
                installationTimezone = "UTC"
            )
        )

        assertEquals(false, record?.isActive)
        assertNull(record?.endpoint)
    }

    @Test
    fun `deactivate posts the installation id and parses the boolean result`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("true")
        )
        val service = createService()

        val result = service.deactivate(session, installationId)

        assertTrue(result)
        val recorded = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/deactivate_installation", recorded.path)
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals(installationId, body["p_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deactivate returns false when the installation was not found`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("false")
        )
        val service = createService()

        assertFalse(service.deactivate(session, installationId))
    }

    @Test
    fun `registerOrUpdate throws on rpc error responses`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"code":"P0001","message":"Invalid platform"}""")
        )
        val service = createService()

        var thrown: Exception? = null
        try {
            service.registerOrUpdate(
                session,
                RegisterInstallationParams(
                    id = installationId,
                    localEnabled = true,
                    permissionState = "granted",
                    endpoint = "token",
                    installationTimezone = "UTC"
                )
            )
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is IOException)
    }
}
