package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.Label
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

data class CreateLabelParams(
    val id: String? = null,
    val name: String,
    val color: String
)

data class UpdateLabelParams(
    val id: String,
    val name: String? = null,
    val color: String? = null
)

interface LabelService {
    suspend fun fetchLabels(session: OperatorSession): List<Label>
    suspend fun createLabel(session: OperatorSession, params: CreateLabelParams): Label
    suspend fun updateLabel(session: OperatorSession, params: UpdateLabelParams): Label
    suspend fun deleteLabel(session: OperatorSession, labelId: String)
}

class SupabaseLabelService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : LabelService {

    private fun executeRequest(request: Request, operationName: String): String {
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            if (response.code == 409 || responseBody.contains("23505") || responseBody.contains("duplicate key")) {
                throw IOException("A label with this name already exists")
            }
            throw IOException("Failed to $operationName: ${response.code} $responseBody")
        }
        return responseBody
    }

    override suspend fun fetchLabels(session: OperatorSession): List<Label> {
        val endpoint = "${config.url}/rest/v1/labels?select=*&order=created_at.asc"

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .get()
            .build()

        val responseBody = executeRequest(request, "fetch labels")
        return json.decodeFromString<List<Label>>(responseBody)
    }

    override suspend fun createLabel(session: OperatorSession, params: CreateLabelParams): Label {
        val trimmedName = params.name.trim()
        require(trimmedName.isNotEmpty()) { "Label name cannot be empty" }
        val trimmedColor = params.color.trim()
        require(trimmedColor.isNotEmpty()) { "Label color cannot be empty" }

        val endpoint = "${config.url}/rest/v1/labels"
        val bodyObject = buildJsonObject {
            if (params.id != null) {
                put("id", params.id)
            }
            put("name", trimmedName)
            put("color", trimmedColor)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Prefer", "return=representation")
            .addHeader("Content-Type", "application/json")
            .post(bodyObject.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = executeRequest(request, "create label")
        return if (responseBody.trim().startsWith("[")) {
            val list = json.decodeFromString<List<Label>>(responseBody)
            list.first()
        } else {
            json.decodeFromString<Label>(responseBody)
        }
    }

    override suspend fun updateLabel(session: OperatorSession, params: UpdateLabelParams): Label {
        require(params.id.isNotBlank()) { "Label id cannot be empty" }
        if (params.name != null) {
            require(params.name.trim().isNotEmpty()) { "Label name cannot be empty" }
        }
        if (params.color != null) {
            require(params.color.trim().isNotEmpty()) { "Label color cannot be empty" }
        }

        val endpoint = "${config.url}/rest/v1/labels?id=eq.${params.id}"
        val bodyObject = buildJsonObject {
            if (params.name != null) {
                put("name", params.name.trim())
            }
            if (params.color != null) {
                put("color", params.color.trim())
            }
            put("updated_at", Instant.now().toString())
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Prefer", "return=representation")
            .addHeader("Content-Type", "application/json")
            .patch(bodyObject.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = executeRequest(request, "update label")
        return if (responseBody.trim().startsWith("[")) {
            val list = json.decodeFromString<List<Label>>(responseBody)
            list.first()
        } else {
            json.decodeFromString<Label>(responseBody)
        }
    }

    override suspend fun deleteLabel(session: OperatorSession, labelId: String) {
        require(labelId.isNotBlank()) { "Label id cannot be empty" }
        val endpoint = "${config.url}/rest/v1/labels?id=eq.$labelId"

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .delete()
            .build()

        executeRequest(request, "delete label")
    }
}
