package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.domain.TimedPlanType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SettingsServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private val session = OperatorSession(
        accessToken = "test-token",
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "alice@cras.app"
    )

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun createService(): SettingsService {
        val url = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = url, publishableKey = "sb_key")
        return SupabaseSettingsService(config, OkHttpClient())
    }

    @Test
    fun `resolveEffectiveTimedPlanType prioritizes operator override over deployment default and defaults to instant`() {
        // 1. Explicit operator override wins
        assertEquals(
            TimedPlanType.FLOATING,
            resolveEffectiveTimedPlanType(
                settings = OperatorSettings(defaultTimedPlanType = TimedPlanType.FLOATING),
                deploymentConfig = DeploymentConfig(defaultTimedPlanType = TimedPlanType.INSTANT)
            )
        )
        assertEquals(
            TimedPlanType.INSTANT,
            resolveEffectiveTimedPlanType(
                settings = OperatorSettings(defaultTimedPlanType = TimedPlanType.INSTANT),
                deploymentConfig = DeploymentConfig(defaultTimedPlanType = TimedPlanType.FLOATING)
            )
        )

        // 2. Inherits deployment config if operator override is null
        assertEquals(
            TimedPlanType.FLOATING,
            resolveEffectiveTimedPlanType(
                settings = OperatorSettings(defaultTimedPlanType = null),
                deploymentConfig = DeploymentConfig(defaultTimedPlanType = TimedPlanType.FLOATING)
            )
        )
        assertEquals(
            TimedPlanType.INSTANT,
            resolveEffectiveTimedPlanType(
                settings = null,
                deploymentConfig = DeploymentConfig(defaultTimedPlanType = TimedPlanType.INSTANT)
            )
        )

        // 3. Fallback to instant if both are absent
        assertEquals(
            TimedPlanType.INSTANT,
            resolveEffectiveTimedPlanType(settings = null, deploymentConfig = null)
        )
    }

    @Test
    fun `fetchEffectiveTimedPlanType fetches remote settings and deployment config and caches value`() = runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/rest/v1/settings") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"operator_id":"550e8400-e29b-41d4-a716-446655440001","default_timed_plan_type":"floating","missed_delivery_enabled":false}"""
                        )
                    }
                    path.startsWith("/rest/v1/deployment_config") -> {
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":1,"default_timed_plan_type":"instant","voice_enabled":true}"""
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val service = createService()
        val effectiveType = service.fetchEffectiveTimedPlanType(session)
        assertEquals(TimedPlanType.FLOATING, effectiveType)
    }

    @Test
    fun `fetchEffectiveTimedPlanType falls back to cached value on network error`() = runTest {
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(500).setBody("Server error")
            }
        }

        val service = createService()
        val effectiveType = service.fetchEffectiveTimedPlanType(session)
        // Should fallback to default instant
        assertEquals(TimedPlanType.INSTANT, effectiveType)
    }

    @Test
    fun `updateOperatorTimedPlanType sends upsert to rest v1 settings`() = runTest {
        var recordedBody = ""
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/rest/v1/settings") == true) {
                    recordedBody = request.body.readUtf8()
                    return MockResponse().setResponseCode(200).setBody("""{"operator_id":"550e8400-e29b-41d4-a716-446655440001","default_timed_plan_type":"floating"}""")
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val service = createService()
        service.updateOperatorTimedPlanType(session, TimedPlanType.FLOATING)

        assertEquals("""{"default_timed_plan_type":"floating"}""", recordedBody)
    }
}
