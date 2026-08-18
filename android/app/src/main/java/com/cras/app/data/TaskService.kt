package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class CreateTaskParams(
    val title: String,
    val description: String? = null,
    val priority: Int = 4,
    val plan: Plan? = null,
    val parentId: String? = null,
    val labels: List<String> = emptyList()
)

interface TaskService {
    suspend fun fetchTasks(session: OperatorSession): List<Task>
    suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task
}

class SupabaseTaskService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TaskService {

    private fun executeRequest(request: Request, operationName: String): String {
        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw IOException("Failed to $operationName: ${response.code} $responseBody")
        }
        return responseBody
    }

    override suspend fun fetchTasks(session: OperatorSession): List<Task> {
        val endpoint = "${config.url}/rest/v1/tasks?select=*"

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Accept-Profile", "api")
            .get()
            .build()

        val responseBody = executeRequest(request, "fetch tasks")
        return json.decodeFromString<List<Task>>(responseBody)
    }

    override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
        val trimmedTitle = params.title.trim()
        require(trimmedTitle.isNotEmpty()) { "Task title cannot be empty" }

        val endpoint = "${config.url}/rest/v1/rpc/create_task"

        val bodyObject = buildJsonObject {
            put("title", trimmedTitle)
            if (params.description != null) {
                put("description", params.description)
            }
            put("priority", params.priority)
            if (params.plan != null) {
                put("plan", json.encodeToJsonElement(params.plan))
            }
            if (params.parentId != null) {
                put("parent_id", params.parentId)
            }
            put("labels", buildJsonArray {
                params.labels.forEach { add(JsonPrimitive(it)) }
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Accept-Profile", "api")
            .addHeader("Content-Profile", "api")
            .addHeader("Content-Type", "application/json")
            .post(bodyObject.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = executeRequest(request, "create task")
        return json.decodeFromString<Task>(responseBody)
    }
}
