package com.cras.app.auth

import com.cras.app.config.PublicSupabaseConfig
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthServiceTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var sessionStore: InMemorySessionStore
    private lateinit var authService: AuthService

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(
            url = baseUrl,
            publishableKey = "test-anon-key"
        )
        sessionStore = InMemorySessionStore()
        authService = SupabaseAuthService(
            config = config,
            sessionStore = sessionStore,
            httpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `signInWithGoogleIdToken establishes new operator session and stores it`() = runTest {
        val authResponseBody = """
            {
                "access_token": "operator-jwt-token-123",
                "token_type": "bearer",
                "expires_in": 3600,
                "refresh_token": "refresh-token-123",
                "user": {
                    "id": "550e8400-e29b-41d4-a716-446655440001",
                    "aud": "authenticated",
                    "role": "authenticated",
                    "email": "operator@cras.app"
                }
            }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(authResponseBody)
        )

        val session = authService.signInWithGoogleIdToken(
            idToken = "google-id-token-abc",
            nonce = "raw-nonce-123"
        )

        assertNotNull(session)
        assertEquals("550e8400-e29b-41d4-a716-446655440001", session.operatorId)
        assertEquals("operator@cras.app", session.email)
        assertEquals("operator-jwt-token-123", session.accessToken)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("/auth/v1/token?grant_type=id_token", recordedRequest.path)
        assertEquals("POST", recordedRequest.method)
        assertEquals("test-anon-key", recordedRequest.getHeader("apikey"))

        // Assert session persisted in store
        val stored = sessionStore.loadSession()
        assertEquals(session, stored)
    }

    @Test
    fun `restoreSession loads previously saved operator session without network if valid`() = runTest {
        val savedSession = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "returning@cras.app",
            accessToken = "saved-token-456",
            refreshToken = "saved-refresh-456"
        )
        sessionStore.saveSession(savedSession)

        val restored = authService.restoreSession()
        assertEquals(savedSession, restored)
    }

    @Test
    fun `restoreSession returns null if no session stored`() = runTest {
        val restored = authService.restoreSession()
        assertNull(restored)
    }

    @Test
    fun `signOut clears stored session and returns unauthenticated state`() = runTest {
        val savedSession = OperatorSession(
            operatorId = "550e8400-e29b-41d4-a716-446655440002",
            email = "alice@cras.app",
            accessToken = "saved-token",
            refreshToken = null
        )
        sessionStore.saveSession(savedSession)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}")
        )

        authService.signOut()

        assertNull(sessionStore.loadSession())
        assertNull(authService.currentSession.value)
    }
}
