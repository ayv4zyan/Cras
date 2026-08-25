package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var accountService: AccountService
    private val session = OperatorSession(
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "operator@cras.app",
        accessToken = "test-session-jwt",
        refreshToken = null
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
        accountService = SupabaseAccountService(
            config = config,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchAccountStatus returns active state when account is not pending deletion`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"deletionState":"active","deletionDeadline":null,"recoveryAvailable":false}""")
        )

        val status = accountService.fetchAccountStatus(session)

        assertEquals(AccountDeletionState.ACTIVE, status.deletionState)
        assertNull(status.deletionDeadline)
        assertFalse(status.recoveryAvailable)

        val request = mockWebServer.takeRequest()
        assertEquals("/functions/v1/account-lifecycle", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer test-session-jwt", request.getHeader("Authorization"))
        assertEquals("test-anon-key", request.getHeader("apikey"))
        assertTrue(request.body.readUtf8().contains(""""action":"status""""))
    }

    @Test
    fun `fetchAccountStatus returns pending_deletion with deadline and recovery availability`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"deletionState":"pending_deletion","deletionDeadline":"2026-08-31T12:00:00Z","recoveryAvailable":true}""")
        )

        val status = accountService.fetchAccountStatus(session)

        assertEquals(AccountDeletionState.PENDING_DELETION, status.deletionState)
        assertEquals("2026-08-31T12:00:00Z", status.deletionDeadline)
        assertTrue(status.recoveryAvailable)
    }

    @Test
    fun `fetchAccountStatus throws AccountLifecycleException on error response`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"This session is no longer active","code":"session_inactive"}""")
        )

        val exception = assertFailsWith<AccountLifecycleException> {
            accountService.fetchAccountStatus(session)
        }

        assertEquals(401, exception.statusCode)
        assertEquals("session_inactive", exception.code)
        assertTrue(exception.message!!.contains("This session is no longer active"))
    }

    @Test
    fun `requestAccountDeletion posts request-deletion and returns confirmation`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"confirmed":true,"deletionState":"pending_deletion","deletionDeadline":"2026-08-31T12:00:00Z","sessionsRevoked":true}""")
        )

        val confirmation = accountService.requestAccountDeletion(session)

        assertTrue(confirmation.confirmed)
        assertEquals(AccountDeletionState.PENDING_DELETION, confirmation.deletionState)
        assertEquals("2026-08-31T12:00:00Z", confirmation.deletionDeadline)
        assertTrue(confirmation.sessionsRevoked)

        val request = mockWebServer.takeRequest()
        assertTrue(request.body.readUtf8().contains(""""action":"request-deletion""""))
    }

    @Test
    fun `recoverAccount posts recover-account action`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"recovered":true}""")
        )

        accountService.recoverAccount(session)

        val request = mockWebServer.takeRequest()
        assertTrue(request.body.readUtf8().contains(""""action":"recover-account""""))
    }

    @Test
    fun `recoverAccount throws AccountLifecycleException when recovery window is closed`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Recovery is not available for this account.","code":"recovery_window_closed"}""")
        )

        val exception = assertFailsWith<AccountLifecycleException> {
            accountService.recoverAccount(session)
        }

        assertEquals(403, exception.statusCode)
        assertEquals("recovery_window_closed", exception.code)
    }

    @Test
    fun `exportOperatorData calls RPC and returns canonical JSON snapshot`() = runTest {
        val canonicalJson = """{"exportedAt":"2026-08-25T10:00:00Z","tasks":[],"labels":[],"taskLabels":[],"comments":[],"settings":null}"""
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(canonicalJson)
        )

        val exportResult = accountService.exportOperatorData(session)

        assertEquals(canonicalJson, exportResult)

        val request = mockWebServer.takeRequest()
        assertEquals("/rest/v1/rpc/export_operator_data", request.path)
        assertEquals("POST", request.method)
        assertEquals("Bearer test-session-jwt", request.getHeader("Authorization"))
    }
}
