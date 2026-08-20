package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.isValidUuid
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * Parameters for updating an existing task via the `update_task` RPC.
 *
 * Null optional fields (such as [title], [description], [priority], [plan], [parentId],
 * [expectedVersion], and [labels]) indicate "no change" and are omitted from the JSON request payload.
 * To explicitly clear the plan, set [clearPlan] to true. To clear a description, set [clearDescription] to true.
 */
data class UpdateTaskParams(
    val id: String,
    val title: String? = null,
    val description: String? = null,
    val clearDescription: Boolean = false,
    val priority: Int? = null,
    val plan: Plan? = null,
    val clearPlan: Boolean = false,
    val parentId: String? = null,
    val expectedVersion: Int? = null,
    val labels: List<String>? = null
)

interface TaskService {
    suspend fun fetchTasks(session: OperatorSession): List<Task>
    suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task
    suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task
    suspend fun completeTask(session: OperatorSession, taskId: String, completedAt: String? = null): Task
    suspend fun uncompleteTask(session: OperatorSession, taskId: String): Task
}

class SupabaseTaskService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : TaskService {

    private fun executeRequest(request: Request, operationName: String): String {
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                throw IOException("Failed to $operationName: ${response.code} $responseBody")
            }
            responseBody
        }
    }

    private suspend fun executeRpc(
        session: OperatorSession,
        rpcName: String,
        bodyObject: JsonObject
    ): Task {
        val endpoint = "${config.url}/rest/v1/rpc/$rpcName"
        val mediaType = "application/json".toMediaType()
        val requestBody = bodyObject.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Accept-Profile", "api")
            .addHeader("Content-Profile", "api")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val responseBody = executeRequest(request, rpcName)
        return json.decodeFromString<Task>(responseBody)
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
        val normalizedLabels = params.labels.map { labelId ->
            require(isValidUuid(labelId)) { "Task label must be a valid UUID: $labelId" }
            labelId.lowercase()
        }
        require(normalizedLabels.distinct().size == normalizedLabels.size) { "Task labels must be unique" }

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
                normalizedLabels.forEach { add(JsonPrimitive(it)) }
            })
        }

        return executeRpc(session, "create_task", bodyObject)
    }

    override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task {
        require(params.id.isNotBlank()) { "Task id cannot be empty" }
        if (params.title != null) {
            require(params.title.trim().isNotEmpty()) { "Task title cannot be empty" }
        }
        if (params.priority != null) {
            require(params.priority in 1..4) { "Priority must be between 1 and 4" }
        }
        val normalizedLabels = params.labels?.map { labelId ->
            require(isValidUuid(labelId)) { "Task label must be a valid UUID: $labelId" }
            labelId.lowercase()
        }
        if (normalizedLabels != null) {
            require(normalizedLabels.distinct().size == normalizedLabels.size) { "Task labels must be unique" }
        }

        val bodyObject = buildJsonObject {
            put("id", params.id)
            if (params.title != null) {
                put("title", params.title.trim())
            }
            if (params.description != null) {
                put("description", params.description)
            }
            if (params.clearDescription) {
                put("clear_description", true)
            }
            if (params.priority != null) {
                put("priority", params.priority)
            }
            if (params.plan != null) {
                put("plan", json.encodeToJsonElement(params.plan))
            }
            if (params.clearPlan) {
                put("clear_plan", true)
            }
            if (params.parentId != null) {
                put("parent_id", params.parentId)
            }
            if (params.expectedVersion != null) {
                put("expected_version", params.expectedVersion)
            }
            if (normalizedLabels != null) {
                put("labels", buildJsonArray {
                    normalizedLabels.forEach { add(JsonPrimitive(it)) }
                })
            }
        }

        return executeRpc(session, "update_task", bodyObject)
    }

    override suspend fun completeTask(
        session: OperatorSession,
        taskId: String,
        completedAt: String?
    ): Task {
        require(taskId.isNotBlank()) { "Task id cannot be empty" }

        val bodyObject = buildJsonObject {
            put("id", taskId)
            if (completedAt != null) {
                put("completed_at", completedAt)
            }
        }

        return executeRpc(session, "complete_task", bodyObject)
    }

    override suspend fun uncompleteTask(session: OperatorSession, taskId: String): Task {
        require(taskId.isNotBlank()) { "Task id cannot be empty" }

        val bodyObject = buildJsonObject {
            put("id", taskId)
        }

        return executeRpc(session, "uncomplete_task", bodyObject)
    }
}
