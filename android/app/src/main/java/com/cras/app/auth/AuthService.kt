package com.cras.app.auth

import com.cras.app.config.PublicSupabaseConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class OperatorSession(
    val operatorId: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String? = null
)

interface SessionStore {
    fun loadSession(): OperatorSession?
    fun saveSession(session: OperatorSession)
    fun clearSession()
}

class SharedPreferencesSessionStore(
    private val preferences: android.content.SharedPreferences,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SessionStore {

    companion object {
        private const val KEY_SESSION = "cras_operator_session"
    }

    override fun loadSession(): OperatorSession? {
        val serialized = preferences.getString(KEY_SESSION, null) ?: return null
        return try {
            json.decodeFromString<OperatorSession>(serialized)
        } catch (_: Exception) {
            null
        }
    }

    override fun saveSession(session: OperatorSession) {
        val serialized = json.encodeToString(OperatorSession.serializer(), session)
        preferences.edit().putString(KEY_SESSION, serialized).apply()
    }

    override fun clearSession() {
        preferences.edit().remove(KEY_SESSION).apply()
    }
}

class InMemorySessionStore : SessionStore {
    private var session: OperatorSession? = null

    override fun loadSession(): OperatorSession? = session

    override fun saveSession(session: OperatorSession) {
        this.session = session
    }

    override fun clearSession() {
        this.session = null
    }
}

interface AuthService {
    val currentSession: StateFlow<OperatorSession?>
    suspend fun signInWithGoogleIdToken(idToken: String, nonce: String? = null): OperatorSession
    suspend fun restoreSession(): OperatorSession?
    suspend fun restoreSession(session: OperatorSession): OperatorSession
    suspend fun signOut()
}

class SupabaseAuthService(
    private val config: PublicSupabaseConfig,
    private val sessionStore: SessionStore = InMemorySessionStore(),
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : AuthService {

    private val _currentSession = MutableStateFlow<OperatorSession?>(null)
    override val currentSession: StateFlow<OperatorSession?> = _currentSession.asStateFlow()

    init {
        val initial = sessionStore.loadSession()
        _currentSession.value = initial
    }

    override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): OperatorSession {
        val endpoint = "${config.url}/auth/v1/token?grant_type=id_token"

        val requestBodyJson = buildJsonObject {
            put("provider", "google")
            put("id_token", idToken)
            if (nonce != null) {
                put("nonce", nonce)
            }
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IOException("Auth failed with code ${response.code}: $responseBody")
        }

        val jsonElement = json.parseToJsonElement(responseBody).jsonObject
        val accessToken = jsonElement["access_token"]?.jsonPrimitive?.content
            ?: throw IOException("Missing access_token in auth response")
        val refreshToken = jsonElement["refresh_token"]?.jsonPrimitive?.content

        val userObj = jsonElement["user"]?.jsonObject
        val operatorId = userObj?.get("id")?.jsonPrimitive?.content
            ?: throw IOException("Missing operator id in auth response")
        val email = userObj["email"]?.jsonPrimitive?.content

        val session = OperatorSession(
            operatorId = operatorId,
            email = email,
            accessToken = accessToken,
            refreshToken = refreshToken
        )

        sessionStore.saveSession(session)
        _currentSession.value = session
        return session
    }

    override suspend fun restoreSession(): OperatorSession? {
        val saved = sessionStore.loadSession()
        _currentSession.value = saved
        return saved
    }

    override suspend fun restoreSession(session: OperatorSession): OperatorSession {
        sessionStore.saveSession(session)
        _currentSession.value = session
        return session
    }

    override suspend fun signOut() {
        val current = _currentSession.value
        sessionStore.clearSession()
        _currentSession.value = null

        if (current != null) {
            try {
                val endpoint = "${config.url}/auth/v1/logout"
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("apikey", config.publishableKey)
                    .addHeader("Authorization", "Bearer ${current.accessToken}")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (_: Exception) {
                // Ignore network failure on local sign-out
            }
        }
    }
}

/**
 * Verifies that the active authenticated session and the underlying [AuthService] session
 * have not diverged or been invalidated while an external reauthentication flow was in progress.
 */
fun isReauthenticationSessionUnchanged(
    capturedSession: OperatorSession,
    activeSession: OperatorSession?,
    authServiceSession: OperatorSession?,
): Boolean {
    return activeSession != null &&
        activeSession == capturedSession &&
        authServiceSession == capturedSession
}

/**
 * Verifies that the new [OperatorSession] obtained after reauthentication matches
 * the active session either by operatorId or email.
 */
fun isMatchingReauthenticatedSession(
    activeSession: OperatorSession,
    newSession: OperatorSession,
): Boolean {
    return newSession.operatorId == activeSession.operatorId ||
        (activeSession.email != null && newSession.email == activeSession.email)
}

/**
 * Performs token exchange for reauthentication against [authService], verifying that
 * the newly authenticated session matches [activeSession]. If mismatched, restores [activeSession].
 */
suspend fun performReauthenticationExchange(
    authService: AuthService,
    activeSession: OperatorSession,
    idToken: String,
    nonce: String?,
    callback: (Boolean, String?) -> Unit,
) {
    try {
        val newSession = authService.signInWithGoogleIdToken(idToken, nonce)
        if (isMatchingReauthenticatedSession(activeSession, newSession)) {
            callback(true, null)
        } else {
            if (authService.currentSession.value == newSession) {
                authService.restoreSession(activeSession)
            }
            callback(false, "Signed in with a different Google account. Please use the matching account.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (authService.currentSession.value != activeSession &&
            authService.currentSession.value != null
        ) {
            runCatching { authService.restoreSession(activeSession) }
        }
        callback(false, e.message ?: "Reauthentication failed")
    }
}

