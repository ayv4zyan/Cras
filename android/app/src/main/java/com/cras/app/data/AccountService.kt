package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

enum class AccountDeletionState(val value: String) {
    ACTIVE("active"),
    PENDING_DELETION("pending_deletion");

    companion object {
        fun fromValue(value: String?): AccountDeletionState = when (value) {
            "pending_deletion" -> PENDING_DELETION
            else -> ACTIVE
        }
    }
}

@Serializable
data class AccountStatus(
    val deletionState: AccountDeletionState,
    val deletionDeadline: String?,
    val recoveryAvailable: Boolean
)

@Serializable
data class DeletionConfirmation(
    val confirmed: Boolean,
    val deletionState: AccountDeletionState,
    val deletionDeadline: String?,
    val sessionsRevoked: Boolean
)

class AccountLifecycleException(
    message: String,
    val statusCode: Int = 0,
    val code: String? = null,
    val isNetworkError: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

interface AccountService {
    suspend fun fetchAccountStatus(session: OperatorSession): AccountStatus
    suspend fun requestAccountDeletion(session: OperatorSession): DeletionConfirmation
    suspend fun recoverAccount(session: OperatorSession)
    suspend fun exportOperatorData(session: OperatorSession): String
}

private fun JsonElement?.readString(): String? {
    if (this == null || this is JsonNull) return null
    if (this is JsonPrimitive) {
        val str = this.contentOrNull
        return if (str == "null" || str.isNullOrEmpty()) null else str
    }
    return null
}

private fun JsonElement?.readBoolean(defaultValue: Boolean = false): Boolean {
    if (this == null || this is JsonNull) return defaultValue
    if (this is JsonPrimitive) {
        return this.booleanOrNull ?: this.contentOrNull?.toBooleanStrictOrNull() ?: defaultValue
    }
    return defaultValue
}

class SupabaseAccountService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : AccountService {

    private fun callLifecycleEndpoint(session: OperatorSession, action: String): JsonObject {
        val endpoint = "${config.url}/functions/v1/account-lifecycle"
        val requestBodyJson = buildJsonObject {
            put("action", action)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw AccountLifecycleException(
                message = "Network error: unable to reach Cras account services.",
                statusCode = 0,
                code = "network_error",
                isNetworkError = true,
                cause = e
            )
        }

        return response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                var errorMessage = "Account service request failed."
                var errorCode: String? = null
                try {
                    val parsed = json.parseToJsonElement(responseBody).jsonObject
                    parsed["error"]?.readString()?.let { if (it.isNotBlank()) errorMessage = it }
                    parsed["code"]?.readString()?.let { errorCode = it }
                } catch (_: Exception) {
                    // Fallback to default message
                }
                throw AccountLifecycleException(
                    message = errorMessage,
                    statusCode = resp.code,
                    code = errorCode
                )
            }

            if (responseBody.trim().isEmpty() || responseBody.trim() == "null") {
                JsonObject(emptyMap())
            } else {
                try {
                    json.parseToJsonElement(responseBody).jsonObject
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }
            }
        }
    }

    override suspend fun fetchAccountStatus(session: OperatorSession): AccountStatus {
        val responseObj = callLifecycleEndpoint(session, "status")
        val stateStr = responseObj["deletionState"]?.readString()
            ?: responseObj["deletion_state"]?.readString()
        val deadlineStr = responseObj["deletionDeadline"]?.readString()
            ?: responseObj["deletion_deadline"]?.readString()
        val recoveryAvail = responseObj["recoveryAvailable"]?.readBoolean(false)
            ?: responseObj["recovery_available"]?.readBoolean(false)
            ?: false

        return AccountStatus(
            deletionState = AccountDeletionState.fromValue(stateStr),
            deletionDeadline = deadlineStr,
            recoveryAvailable = recoveryAvail
        )
    }

    override suspend fun requestAccountDeletion(session: OperatorSession): DeletionConfirmation {
        val responseObj = callLifecycleEndpoint(session, "request-deletion")
        val confirmed = responseObj["confirmed"]?.readBoolean(false) ?: false
        val stateStr = responseObj["deletionState"]?.readString()
            ?: responseObj["deletion_state"]?.readString()
        val deadlineStr = responseObj["deletionDeadline"]?.readString()
            ?: responseObj["deletion_deadline"]?.readString()
        val sessionsRevoked = responseObj["sessionsRevoked"]?.readBoolean(false)
            ?: responseObj["sessions_revoked"]?.readBoolean(false)
            ?: false

        return DeletionConfirmation(
            confirmed = confirmed,
            deletionState = AccountDeletionState.fromValue(stateStr),
            deletionDeadline = deadlineStr,
            sessionsRevoked = sessionsRevoked
        )
    }

    override suspend fun recoverAccount(session: OperatorSession) {
        callLifecycleEndpoint(session, "recover-account")
    }

    override suspend fun exportOperatorData(session: OperatorSession): String {
        val endpoint = "${config.url}/rest/v1/rpc/export_operator_data"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Content-Type", "application/json")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw AccountLifecycleException(
                message = "Network error: unable to generate account export.",
                statusCode = 0,
                code = "network_error",
                isNetworkError = true,
                cause = e
            )
        }

        return response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw AccountLifecycleException(
                    message = "Failed to generate account export: ${resp.code} $responseBody",
                    statusCode = resp.code
                )
            }
            if (responseBody.startsWith("\"") && responseBody.endsWith("\"") && responseBody.length >= 2) {
                try {
                    json.decodeFromString<String>(responseBody)
                } catch (_: Exception) {
                    responseBody
                }
            } else {
                responseBody
            }
        }
    }
}
