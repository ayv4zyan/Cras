package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.domain.TimedPlanType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class OperatorSettings(
    @SerialName("operator_id") val operatorId: String? = null,
    @SerialName("default_timed_plan_type") val defaultTimedPlanTypeString: String? = null,
    @SerialName("missed_delivery_enabled") val missedDeliveryEnabled: Boolean? = null
) {
    val defaultTimedPlanType: TimedPlanType?
        get() = TimedPlanType.fromValue(defaultTimedPlanTypeString)

    constructor(
        operatorId: String? = null,
        defaultTimedPlanType: TimedPlanType? = null,
        missedDeliveryEnabled: Boolean? = null
    ) : this(
        operatorId = operatorId,
        defaultTimedPlanTypeString = defaultTimedPlanType?.value,
        missedDeliveryEnabled = missedDeliveryEnabled
    )
}

@Serializable
data class DeploymentConfig(
    val id: Int? = null,
    @SerialName("default_timed_plan_type") val defaultTimedPlanTypeString: String? = null,
    @SerialName("voice_enabled") val voiceEnabled: Boolean? = null
) {
    val defaultTimedPlanType: TimedPlanType?
        get() = TimedPlanType.fromValue(defaultTimedPlanTypeString)

    constructor(
        id: Int? = null,
        defaultTimedPlanType: TimedPlanType? = null,
        voiceEnabled: Boolean? = null
    ) : this(
        id = id,
        defaultTimedPlanTypeString = defaultTimedPlanType?.value,
        voiceEnabled = voiceEnabled
    )
}

/**
 * Resolves the effective default timed plan type:
 * 1. An explicit Operator override (Instant/Floating) wins.
 * 2. If Operator override is null or missing, inherit Deployment configuration.
 * 3. Fallback to TimedPlanType.INSTANT.
 */
fun resolveEffectiveTimedPlanType(
    settings: OperatorSettings?,
    deploymentConfig: DeploymentConfig?
): TimedPlanType {
    if (settings?.defaultTimedPlanType != null) {
        return settings.defaultTimedPlanType!!
    }

    if (deploymentConfig?.defaultTimedPlanType != null) {
        return deploymentConfig.defaultTimedPlanType!!
    }

    return TimedPlanType.INSTANT
}

interface SettingsService {
    suspend fun fetchOperatorSettings(session: OperatorSession): OperatorSettings?
    suspend fun fetchDeploymentConfig(session: OperatorSession): DeploymentConfig?
    suspend fun fetchEffectiveTimedPlanType(session: OperatorSession): TimedPlanType
    suspend fun updateOperatorTimedPlanType(session: OperatorSession, type: TimedPlanType?)
}

class SupabaseSettingsService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SettingsService {

    @Volatile
    private var cachedEffectiveType: TimedPlanType = TimedPlanType.INSTANT

    private fun executeRequest(request: Request, operationName: String): String {
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IOException("Failed to $operationName: ${response.code} $responseBody")
        }
        return responseBody
    }

    override suspend fun fetchOperatorSettings(session: OperatorSession): OperatorSettings? {
        val endpoint = "${config.url}/rest/v1/settings?select=operator_id,default_timed_plan_type,missed_delivery_enabled"

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Accept", "application/vnd.pgrst.object+json")
            .get()
            .build()

        val responseBody = executeRequest(request, "fetch operator settings")
        return if (responseBody.trim().isEmpty() || responseBody.trim() == "null" || responseBody.trim() == "{}") {
            null
        } else {
            try {
                json.decodeFromString<OperatorSettings>(responseBody)
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchDeploymentConfig(session: OperatorSession): DeploymentConfig? {
        val endpoint = "${config.url}/rest/v1/deployment_config?select=id,default_timed_plan_type,voice_enabled"

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Accept", "application/vnd.pgrst.object+json")
            .get()
            .build()

        val responseBody = executeRequest(request, "fetch deployment config")
        return if (responseBody.trim().isEmpty() || responseBody.trim() == "null" || responseBody.trim() == "{}") {
            null
        } else {
            try {
                json.decodeFromString<DeploymentConfig>(responseBody)
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun fetchEffectiveTimedPlanType(session: OperatorSession): TimedPlanType {
        return try {
            val settings = fetchOperatorSettings(session)
            val deploymentConfig = fetchDeploymentConfig(session)
            val effective = resolveEffectiveTimedPlanType(settings, deploymentConfig)
            cachedEffectiveType = effective
            effective
        } catch (_: Exception) {
            cachedEffectiveType
        }
    }

    override suspend fun updateOperatorTimedPlanType(session: OperatorSession, type: TimedPlanType?) {
        val endpoint = "${config.url}/rest/v1/settings"

        val bodyObject = buildJsonObject {
            if (type != null) {
                put("default_timed_plan_type", type.value)
            } else {
                put("default_timed_plan_type", JsonNull)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates")
            .post(bodyObject.toString().toRequestBody("application/json".toMediaType()))
            .build()

        executeRequest(request, "update operator settings")
        if (type != null) {
            cachedEffectiveType = type
        } else {
            fetchEffectiveTimedPlanType(session)
        }
    }
}
