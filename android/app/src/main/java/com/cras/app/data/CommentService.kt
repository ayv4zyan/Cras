package com.cras.app.data

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.models.Comment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class CreateCommentParams(
    val id: String? = null,
    val taskId: String,
    val content: String
)

interface CommentService {
    suspend fun fetchComments(session: OperatorSession, taskId: String? = null): List<Comment>
    suspend fun createComment(session: OperatorSession, params: CreateCommentParams): Comment
}

class SupabaseCommentService(
    private val config: PublicSupabaseConfig,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : CommentService {

    private fun executeRequest(request: Request, operationName: String): String {
        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                throw IOException("Failed to $operationName: ${response.code} $responseBody")
            }
            responseBody
        }
    }

    override suspend fun fetchComments(session: OperatorSession, taskId: String?): List<Comment> {
        val urlBuilder = "${config.url}/rest/v1/comments".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")

        if (taskId != null) {
            urlBuilder.addQueryParameter("task_id", "eq.$taskId")
        }

        val endpoint = urlBuilder.build().toString()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("apikey", config.publishableKey)
            .addHeader("Authorization", "Bearer ${session.accessToken}")
            .addHeader("Accept-Profile", "api")
            .get()
            .build()

        val responseBody = executeRequest(request, "fetch comments")
        return json.decodeFromString<List<Comment>>(responseBody)
    }

    override suspend fun createComment(session: OperatorSession, params: CreateCommentParams): Comment {
        val trimmedContent = params.content.trim()
        require(trimmedContent.isNotEmpty()) { "Comment content cannot be empty" }
        val trimmedTaskId = params.taskId.trim()
        require(trimmedTaskId.isNotEmpty()) { "Comment taskId cannot be empty" }

        val endpoint = "${config.url}/rest/v1/rpc/create_comment"
        val bodyObject = buildJsonObject {
            put("task_id", trimmedTaskId)
            put("content", trimmedContent)
            if (params.id != null) {
                put("id", params.id)
            }
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

        val responseBody = executeRequest(request, "create comment")
        return json.decodeFromString<Comment>(responseBody)
    }
}
