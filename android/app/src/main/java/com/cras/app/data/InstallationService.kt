package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class InstallationRecord(
    @SerialName("id") val id: String,
    @SerialName("platform") val platform: String? = null,
    @SerialName("local_enabled") val localEnabled: Boolean? = null,
    @SerialName("permission_state") val permissionState: String? = null,
    @SerialName("endpoint") val endpoint: String? = null,
    @SerialName("installation_timezone") val installationTimezone: String? = null,
    @SerialName("timezone_observed_at") val timezoneObservedAt: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null
)

data class RegisterInstallationParams(
    val id: String,
    val localEnabled: Boolean,
    val permissionState: String,
    val endpoint: String?,
    val installationTimezone: String
)

interface InstallationService {
    suspend fun registerOrUpdate(
        session: OperatorSession,
        params: RegisterInstallationParams
    ): InstallationRecord?

    suspend fun deactivate(session: OperatorSession, installationId: String): Boolean
}

class SupabaseInstallationService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : InstallationService {

    override suspend fun registerOrUpdate(
        session: OperatorSession,
        params: RegisterInstallationParams
    ): InstallationRecord? = withContext(Dispatchers.IO) {
        val endpoint = "${config.url}/rest/v1/rpc/register_or_update_installation"

        val bodyObject = buildJsonObject {
            put("p_id", params.id)
            put("p_platform", "android")
            put("p_local_enabled", params.localEnabled)
            put("p_permission_state", params.permissionState)
            if (params.endpoint != null) {
                put("p_endpoint", params.endpoint)
            } else {
                put("p_endpoint", kotlinx.serialization.json.JsonNull)
            }
            put("p_p256dh", kotlinx.serialization.json.JsonNull)
            put("p_auth", kotlinx.serialization.json.JsonNull)
            put("p_installation_timezone", params.installationTimezone)
            // A lost registration token must clear the persisted endpoint
            // server-side instead of leaving a stale target in place.
            put("p_clear_subscription", params.endpoint == null)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Content-Type", "application/json")
            .post(bodyObject.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("Failed to register installation: ${resp.code} $responseBody")
            }
            if (responseBody.isBlank() || responseBody.trim() == "null") {
                null
            } else {
                try {
                    json.decodeFromString<InstallationRecord>(responseBody)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    override suspend fun deactivate(
        session: OperatorSession,
        installationId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = "${config.url}/rest/v1/rpc/deactivate_installation"

        val bodyObject = buildJsonObject {
            put("p_id", installationId)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Content-Type", "application/json")
            .post(bodyObject.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("Failed to deactivate installation: ${resp.code} $responseBody")
            }
            responseBody.trim() == "true"
        }
    }
}
