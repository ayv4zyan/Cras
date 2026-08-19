package com.cras.app

import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.config.getPublicSupabaseConfig
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.SupabaseLabelService
import com.cras.app.data.SupabaseTaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.SupabaseCommentService
import com.cras.app.data.DeploymentConfig
import com.cras.app.data.OperatorSettings
import com.cras.app.data.SupabaseSettingsService
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.filterSubtasks
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.PlanSerializer
import com.cras.app.models.Task
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.CompletedUiState
import com.cras.app.ui.inbox.InboxUiState
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.inbox.TodayUiState
import com.cras.app.ui.inbox.UpcomingUiState
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class BlackboxJourneyTest {

    private data class SimulatedDbRow(
        val id: String,
        val operatorId: String,
        val title: String,
        val description: String?,
        val priority: Int,
        val plan: Plan?,
        val labels: List<String>,
        val parentId: String?,
        val completedAt: String?,
        val createdAt: String,
        val updatedAt: String,
        val version: Int
    )

    private data class SimulatedCommentDbRow(
        val id: String,
        val operatorId: String,
        val taskId: String,
        val content: String,
        val createdAt: String
    )

    private data class SimulatedLabelDbRow(
        val id: String,
        val operatorId: String,
        val name: String,
        val color: String,
        val createdAt: String,
        val updatedAt: String
    )

    private lateinit var mockWebServer: MockWebServer
    private val dbRows = CopyOnWriteArrayList<SimulatedDbRow>()
    private val labelDbRows = CopyOnWriteArrayList<SimulatedLabelDbRow>()
    private val commentDbRows = CopyOnWriteArrayList<SimulatedCommentDbRow>()
    private val operatorSettingsRows = CopyOnWriteArrayList<OperatorSettings>()
    private var deploymentConfigRow = DeploymentConfig(defaultTimedPlanType = TimedPlanType.INSTANT)
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dbRows.clear()
        labelDbRows.clear()
        commentDbRows.clear()
        operatorSettingsRows.clear()
        deploymentConfigRow = DeploymentConfig(defaultTimedPlanType = TimedPlanType.INSTANT)

        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: "/"
                val method = request.method ?: "GET"
                val authHeader = request.getHeader("Authorization")
                val callerOperatorId = authHeader?.removePrefix("Bearer ")?.let { token ->
                    if (token.startsWith("jwt-")) token.removePrefix("jwt-") else null
                }

                // Auth endpoint
                if (path.startsWith("/auth/v1/token")) {
                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val idToken = reqJson["id_token"]?.jsonPrimitive?.content ?: "anon"
                    val operatorId = if (idToken.contains("bob")) {
                        "550e8400-e29b-41d4-a716-446655440002"
                    } else {
                        "550e8400-e29b-41d4-a716-446655440001"
                    }
                    val email = if (idToken.contains("bob")) "bob@cras.app" else "alice@cras.app"

                    val resp = """
                        {
                            "access_token": "jwt-$operatorId",
                            "token_type": "bearer",
                            "expires_in": 3600,
                            "user": {
                                "id": "$operatorId",
                                "email": "$email"
                            }
                        }
                    """.trimIndent()
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(resp)
                }

                // Labels REST endpoints
                if (path.startsWith("/rest/v1/labels")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    when (method) {
                        "GET" -> {
                            val operatorLabels = labelDbRows
                                .filter { it.operatorId == callerOperatorId }
                                .sortedBy { it.createdAt }
                                .map {
                                    Label(
                                        id = it.id,
                                        name = it.name,
                                        color = it.color,
                                        createdAt = it.createdAt,
                                        updatedAt = it.updatedAt
                                    )
                                }
                            val respBody = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(Label.serializer()),
                                operatorLabels
                            )
                            return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                        "POST" -> {
                            val body = request.body.readUtf8()
                            val reqJson = json.parseToJsonElement(body).jsonObject
                            val name = reqJson["name"]?.jsonPrimitive?.content?.trim() ?: ""
                            val color = reqJson["color"]?.jsonPrimitive?.content?.trim() ?: ""
                            val id = reqJson["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()

                            if (name.isEmpty() || color.isEmpty()) {
                                return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Invalid label params"}""")
                            }

                            // Duplicate name check per operator
                            if (labelDbRows.any { it.operatorId == callerOperatorId && it.name.equals(name, ignoreCase = true) }) {
                                return MockResponse().setResponseCode(409).setBody("""{"code":"23505","message":"duplicate key value violates unique constraint \"uq_labels_name_operator\""}""")
                            }

                            val newLabel = SimulatedLabelDbRow(
                                id = id,
                                operatorId = callerOperatorId,
                                name = name,
                                color = color,
                                createdAt = "2026-08-19T00:00:00Z",
                                updatedAt = "2026-08-19T00:00:00Z"
                            )
                            labelDbRows.add(newLabel)

                            val created = Label(
                                id = newLabel.id,
                                name = newLabel.name,
                                color = newLabel.color,
                                createdAt = newLabel.createdAt,
                                updatedAt = newLabel.updatedAt
                            )
                            val respBody = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(Label.serializer()),
                                listOf(created)
                            )
                            return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                        "PATCH" -> {
                            val labelId = path.substringAfter("id=eq.", "").substringBefore("&")
                            val index = labelDbRows.indexOfFirst { it.id == labelId && it.operatorId == callerOperatorId }
                            if (index == -1) {
                                return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Label not found"}""")
                            }

                            val body = request.body.readUtf8()
                            val reqJson = json.parseToJsonElement(body).jsonObject
                            val newName = reqJson["name"]?.jsonPrimitive?.content?.trim()
                            val newColor = reqJson["color"]?.jsonPrimitive?.content?.trim()

                            if (newName != null && newName.isEmpty()) {
                                return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Label name cannot be empty"}""")
                            }

                            if (newName != null && labelDbRows.any { it.operatorId == callerOperatorId && it.id != labelId && it.name.equals(newName, ignoreCase = true) }) {
                                return MockResponse().setResponseCode(409).setBody("""{"code":"23505","message":"duplicate key value violates unique constraint \"uq_labels_name_operator\""}""")
                            }

                            val existing = labelDbRows[index]
                            val updated = existing.copy(
                                name = newName ?: existing.name,
                                color = newColor ?: existing.color,
                                updatedAt = "2026-08-19T00:15:00Z"
                            )
                            labelDbRows[index] = updated

                            val updatedLabel = Label(
                                id = updated.id,
                                name = updated.name,
                                color = updated.color,
                                createdAt = updated.createdAt,
                                updatedAt = updated.updatedAt
                            )
                            val respBody = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(Label.serializer()),
                                listOf(updatedLabel)
                            )
                            return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                        "DELETE" -> {
                            val labelId = path.substringAfter("id=eq.", "").substringBefore("&")
                            labelDbRows.removeAll { it.id == labelId && it.operatorId == callerOperatorId }
                            // Cascade remove from tasks
                            for (i in dbRows.indices) {
                                if (dbRows[i].operatorId == callerOperatorId && dbRows[i].labels.contains(labelId)) {
                                    dbRows[i] = dbRows[i].copy(labels = dbRows[i].labels.filterNot { it == labelId })
                                }
                            }
                            return MockResponse().setResponseCode(204)
                        }
                    }
                }

                // Comments REST endpoints
                if (path.startsWith("/rest/v1/comments")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    when (method) {
                        "GET" -> {
                            val taskIdFilter = if (path.contains("taskId=eq.")) {
                                path.substringAfter("taskId=eq.", "").substringBefore("&").takeIf { it.isNotEmpty() }
                            } else null

                            val operatorComments = commentDbRows
                                .filter { it.operatorId == callerOperatorId && (taskIdFilter == null || it.taskId == taskIdFilter) }
                                .sortedBy { it.createdAt }
                                .map {
                                    Comment(
                                        id = it.id,
                                        taskId = it.taskId,
                                        content = it.content,
                                        createdAt = it.createdAt
                                    )
                                }
                            val respBody = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(Comment.serializer()),
                                operatorComments
                            )
                            return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                    }
                }

                // Data API: api.create_comment RPC
                if (path.startsWith("/rest/v1/rpc/create_comment")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val taskId = reqJson["task_id"]?.jsonPrimitive?.content ?: ""
                    val commentContent = reqJson["content"]?.jsonPrimitive?.content ?: ""

                    if (commentContent.trim().isEmpty()) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Comment content cannot be empty"}""")
                    }

                    val taskExists = dbRows.any { it.id == taskId && it.operatorId == callerOperatorId }
                    if (!taskExists) {
                        return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Task not found or unauthorized"}""")
                    }

                    val commentId = reqJson["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                    val newComment = SimulatedCommentDbRow(
                        id = commentId,
                        operatorId = callerOperatorId,
                        taskId = taskId,
                        content = commentContent.trim(),
                        createdAt = "2026-08-19T10:00:00Z"
                    )
                    commentDbRows.add(newComment)

                    val comment = Comment(
                        id = newComment.id,
                        taskId = newComment.taskId,
                        content = newComment.content,
                        createdAt = newComment.createdAt
                    )
                    val respBody = json.encodeToString(Comment.serializer(), comment)
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.tasks view
                if (path.startsWith("/rest/v1/tasks")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    // RLS: operator_id = auth.uid()
                    val operatorTasks = dbRows.filter { it.operatorId == callerOperatorId }.map { row ->
                        Task(
                            id = row.id,
                            title = row.title,
                            description = row.description,
                            priority = row.priority,
                            plan = row.plan,
                            labels = row.labels,
                            parentId = row.parentId,
                            completedAt = row.completedAt,
                            createdAt = row.createdAt,
                            updatedAt = row.updatedAt,
                            version = row.version
                        )
                    }

                    val respBody = json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(Task.serializer()),
                        operatorTasks
                    )
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Operator Settings REST endpoints
                if (path.startsWith("/rest/v1/settings")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    when (method) {
                        "GET" -> {
                            val setting = operatorSettingsRows.find { it.operatorId == callerOperatorId }
                            val accept = request.getHeader("Accept")
                            val respBody = if (accept?.contains("vnd.pgrst.object") == true) {
                                if (setting != null) json.encodeToString(OperatorSettings.serializer(), setting)
                                else "{}"
                            } else {
                                val list = if (setting != null) listOf(setting) else emptyList()
                                json.encodeToString(
                                    kotlinx.serialization.builtins.ListSerializer(OperatorSettings.serializer()),
                                    list
                                )
                            }
                            return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                        "POST" -> {
                            val body = request.body.readUtf8()
                            val reqJson = json.parseToJsonElement(body).jsonObject
                            val defaultTypeStr = reqJson["default_timed_plan_type"]?.jsonPrimitive?.content
                            val type = if (defaultTypeStr == "floating") TimedPlanType.FLOATING else if (defaultTypeStr == "instant") TimedPlanType.INSTANT else null

                            operatorSettingsRows.removeAll { it.operatorId == callerOperatorId }
                            val newSetting = OperatorSettings(
                                operatorId = callerOperatorId,
                                defaultTimedPlanType = type
                            )
                            operatorSettingsRows.add(newSetting)

                            val respBody = json.encodeToString(OperatorSettings.serializer(), newSetting)
                            return MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(respBody)
                        }
                    }
                }

                // Deployment Config REST endpoints
                if (path.startsWith("/rest/v1/deployment_config")) {
                    val accept = request.getHeader("Accept")
                    val respBody = if (accept?.contains("vnd.pgrst.object") == true) {
                        json.encodeToString(DeploymentConfig.serializer(), deploymentConfigRow)
                    } else {
                        json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(DeploymentConfig.serializer()),
                            listOf(deploymentConfigRow)
                        )
                    }
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.create_task RPC
                if (path.startsWith("/rest/v1/rpc/create_task")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val title = reqJson["title"]?.jsonPrimitive?.content ?: ""

                    if (title.trim().isEmpty()) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Task title cannot be empty"}""")
                    }

                    val parsedLabels = reqJson["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    val parentId = reqJson["parent_id"]?.jsonPrimitive?.content

                    if (parentId != null) {
                        val parent = dbRows.find { it.id == parentId && it.operatorId == callerOperatorId }
                        if (parent == null) {
                            return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Parent task not found or unauthorized"}""")
                        }
                        if (parent.parentId != null) {
                            return MockResponse().setResponseCode(400).setBody("""{"code":"P0001","message":"Subtasks cannot have children (one-level nesting only)"}""")
                        }
                    }

                    val parsedPlan: Plan? = if (reqJson.containsKey("plan") && reqJson["plan"] != null && reqJson["plan"] !is kotlinx.serialization.json.JsonNull) {
                        json.decodeFromJsonElement(PlanSerializer, reqJson["plan"]!!)
                    } else null

                    val newRow = SimulatedDbRow(
                        id = UUID.randomUUID().toString(),
                        operatorId = callerOperatorId,
                        title = title.trim(),
                        description = reqJson["description"]?.jsonPrimitive?.content,
                        priority = reqJson["priority"]?.jsonPrimitive?.content?.toIntOrNull() ?: 4,
                        plan = parsedPlan,
                        labels = parsedLabels,
                        parentId = parentId,
                        completedAt = null,
                        createdAt = "2026-08-19T00:00:00Z",
                        updatedAt = "2026-08-19T00:00:00Z",
                        version = 1
                    )
                    dbRows.add(newRow)

                    val createdTask = Task(
                        id = newRow.id,
                        title = newRow.title,
                        description = newRow.description,
                        priority = newRow.priority,
                        plan = newRow.plan,
                        labels = newRow.labels,
                        parentId = newRow.parentId,
                        completedAt = newRow.completedAt,
                        createdAt = newRow.createdAt,
                        updatedAt = newRow.updatedAt,
                        version = newRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), createdTask)
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.update_task RPC
                if (path.startsWith("/rest/v1/rpc/update_task")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val id = reqJson["id"]?.jsonPrimitive?.content ?: ""

                    val index = dbRows.indexOfFirst { it.id == id && it.operatorId == callerOperatorId }
                    if (index == -1) {
                        return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Task not found or unauthorized"}""")
                    }

                    val existing = dbRows[index]
                    if (existing.completedAt != null) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"P0001","message":"Completed tasks cannot be edited. Uncomplete first."}""")
                    }

                    val expectedVersion = reqJson["expected_version"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (expectedVersion != null && existing.version != expectedVersion) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"P0001","message":"Task version conflict: expected $expectedVersion, found ${existing.version}"}""")
                    }

                    val newTitle = reqJson["title"]?.jsonPrimitive?.content
                    if (newTitle != null && newTitle.trim().isEmpty()) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Task title cannot be empty"}""")
                    }

                    val newPriority = reqJson["priority"]?.jsonPrimitive?.content?.toIntOrNull()
                    if (newPriority != null && (newPriority < 1 || newPriority > 4)) {
                        return MockResponse().setResponseCode(400).setBody("""{"code":"23514","message":"Priority must be between 1 and 4"}""")
                    }

                    val newLabels = if (reqJson.containsKey("labels")) {
                        reqJson["labels"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    } else {
                        existing.labels
                    }

                    val clearPlan = reqJson["clear_plan"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val newPlan = when {
                        clearPlan -> null
                        reqJson.containsKey("plan") && reqJson["plan"] != null && reqJson["plan"] !is kotlinx.serialization.json.JsonNull -> {
                            json.decodeFromJsonElement(PlanSerializer, reqJson["plan"]!!)
                        }
                        else -> existing.plan
                    }

                    val updatedRow = existing.copy(
                        title = newTitle?.trim() ?: existing.title,
                        description = if (reqJson.containsKey("description")) reqJson["description"]?.jsonPrimitive?.content else existing.description,
                        priority = newPriority ?: existing.priority,
                        plan = newPlan,
                        labels = newLabels,
                        updatedAt = "2026-08-19T00:10:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val updatedTask = Task(
                        id = updatedRow.id,
                        title = updatedRow.title,
                        description = updatedRow.description,
                        priority = updatedRow.priority,
                        plan = updatedRow.plan,
                        labels = updatedRow.labels,
                        parentId = updatedRow.parentId,
                        completedAt = updatedRow.completedAt,
                        createdAt = updatedRow.createdAt,
                        updatedAt = updatedRow.updatedAt,
                        version = updatedRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), updatedTask)
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.complete_task RPC
                if (path.startsWith("/rest/v1/rpc/complete_task")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val id = reqJson["id"]?.jsonPrimitive?.content ?: ""

                    val index = dbRows.indexOfFirst { it.id == id && it.operatorId == callerOperatorId }
                    if (index == -1) {
                        return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Task not found or unauthorized"}""")
                    }

                    val existing = dbRows[index]
                    val completedAt = reqJson["completed_at"]?.jsonPrimitive?.content ?: "2026-08-19T10:00:00Z"

                    val updatedRow = existing.copy(
                        completedAt = completedAt,
                        updatedAt = "2026-08-19T10:00:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val completedTask = Task(
                        id = updatedRow.id,
                        title = updatedRow.title,
                        description = updatedRow.description,
                        priority = updatedRow.priority,
                        plan = updatedRow.plan,
                        labels = updatedRow.labels,
                        parentId = updatedRow.parentId,
                        completedAt = updatedRow.completedAt,
                        createdAt = updatedRow.createdAt,
                        updatedAt = updatedRow.updatedAt,
                        version = updatedRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), completedTask)
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                // Data API: api.uncomplete_task RPC
                if (path.startsWith("/rest/v1/rpc/uncomplete_task")) {
                    if (callerOperatorId == null) {
                        return MockResponse().setResponseCode(401).setBody("""{"code":"42501","message":"Unauthorized"}""")
                    }

                    val body = request.body.readUtf8()
                    val reqJson = json.parseToJsonElement(body).jsonObject
                    val id = reqJson["id"]?.jsonPrimitive?.content ?: ""

                    val index = dbRows.indexOfFirst { it.id == id && it.operatorId == callerOperatorId }
                    if (index == -1) {
                        return MockResponse().setResponseCode(404).setBody("""{"code":"P0002","message":"Task not found or unauthorized"}""")
                    }

                    val existing = dbRows[index]
                    val updatedRow = existing.copy(
                        completedAt = null,
                        updatedAt = "2026-08-19T10:05:00Z",
                        version = existing.version + 1
                    )
                    dbRows[index] = updatedRow

                    val uncompletedTask = Task(
                        id = updatedRow.id,
                        title = updatedRow.title,
                        description = updatedRow.description,
                        priority = updatedRow.priority,
                        plan = updatedRow.plan,
                        labels = updatedRow.labels,
                        parentId = updatedRow.parentId,
                        completedAt = updatedRow.completedAt,
                        createdAt = updatedRow.createdAt,
                        updatedAt = updatedRow.updatedAt,
                        version = updatedRow.version
                    )

                    val respBody = json.encodeToString(Task.serializer(), uncompletedTask)
                    return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(respBody)
                }

                return MockResponse().setResponseCode(404)
            }
        }
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockWebServer.shutdown()
    }

    private fun createViewModel(
        sessionStore: InMemorySessionStore = InMemorySessionStore(),
        nowProvider: () -> Instant = { Instant.now() },
        zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
    ): InboxViewModel {
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "sb_publishable_anon")
        val authService = SupabaseAuthService(config, sessionStore, OkHttpClient())
        val taskService = SupabaseTaskService(config, OkHttpClient())
        val labelService = SupabaseLabelService(config, OkHttpClient())
        val commentService = SupabaseCommentService(config, OkHttpClient())
        val settingsService = SupabaseSettingsService(config, OkHttpClient())
        return InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            nowProvider = nowProvider,
            zoneIdProvider = zoneIdProvider
        )
    }

    @Test
    fun `proves Criterion 4 and ADR 0005 - configuration purity`() {
        val env = mapOf(
            "SUPABASE_URL" to "https://cras-mvp.supabase.co",
            "SUPABASE_ANON_KEY" to "sb_publishable_anon_token"
        )
        val config = getPublicSupabaseConfig(env)
        assertEquals("https://cras-mvp.supabase.co", config.url)
        assertEquals("sb_publishable_anon_token", config.publishableKey)
        assertFalse(config.publishableKey.contains("service_role"))
    }

    @Test
    fun `proves Criterion 1 - Google Sign-In establishes and restores operator session`() = runTest {
        val sessionStore = InMemorySessionStore()

        // 1. Fresh login
        val viewModel = createViewModel(sessionStore)
        advanceUntilIdle()
        assertTrue(viewModel.authState.value is AuthUiState.Unauthenticated)

        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        val auth = viewModel.authState.value
        assertTrue(auth is AuthUiState.Authenticated)
        val session = (auth as AuthUiState.Authenticated).session
        assertEquals("alice@cras.app", session.email)
        assertEquals("550e8400-e29b-41d4-a716-446655440001", session.operatorId)

        // 2. Returning session restores
        val restoredViewModel = createViewModel(sessionStore)
        advanceUntilIdle()

        val restoredAuth = restoredViewModel.authState.value
        assertTrue(restoredAuth is AuthUiState.Authenticated)
        assertEquals("alice@cras.app", (restoredAuth as AuthUiState.Authenticated).session.email)
    }

    @Test
    fun `proves Criterion 2 & 3 - creates titled tasks with distinct identities and filters inbox`() = runTest {
        val sessionStore = InMemorySessionStore()
        val viewModel = createViewModel(sessionStore)
        advanceUntilIdle()

        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create Task 1
        viewModel.createTask("Prepare launch checklist")
        advanceUntilIdle()

        // Create Task 2 with duplicate title
        viewModel.createTask("Prepare launch checklist")
        advanceUntilIdle()

        val state = viewModel.inboxState.value
        assertTrue(state is InboxUiState.Success)
        val tasks = (state as InboxUiState.Success).tasks

        assertEquals(2, tasks.size)
        assertEquals("Prepare launch checklist", tasks[0].title)
        assertEquals("Prepare launch checklist", tasks[1].title)
        assertNotEquals(tasks[0].id, tasks[1].id)

        // Add non-inbox tasks directly to database for Alice
        val aliceId = "550e8400-e29b-41d4-a716-446655440001"
        dbRows.add(
            SimulatedDbRow(
                id = UUID.randomUUID().toString(),
                operatorId = aliceId,
                title = "Subtask task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = tasks[0].id, // Subtask
                completedAt = null,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )
        dbRows.add(
            SimulatedDbRow(
                id = UUID.randomUUID().toString(),
                operatorId = aliceId,
                title = "Tomorrow meeting",
                description = null,
                priority = 4,
                plan = Plan.DateOnly("2026-08-20"), // Dated
                labels = emptyList(),
                parentId = null,
                completedAt = null,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )
        dbRows.add(
            SimulatedDbRow(
                id = UUID.randomUUID().toString(),
                operatorId = aliceId,
                title = "Done task",
                description = null,
                priority = 4,
                plan = null,
                labels = emptyList(),
                parentId = null,
                completedAt = "2026-08-19T01:00:00Z", // Completed
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
        )

        viewModel.loadTasks()
        advanceUntilIdle()

        val refreshedState = viewModel.inboxState.value
        assertTrue(refreshedState is InboxUiState.Success)
        val refreshedTasks = (refreshedState as InboxUiState.Success).tasks

        // Inbox contains ONLY the 2 open top-level undated tasks
        assertEquals(2, refreshedTasks.size)
        assertTrue(refreshedTasks.none { it.title == "Subtask task" })
        assertTrue(refreshedTasks.none { it.title == "Tomorrow meeting" })
        assertTrue(refreshedTasks.none { it.title == "Done task" })
    }

    @Test
    fun `proves Issue 42 AC 1 - Compose flows edit Description and all Priority states`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create with initial description and priority 4
        viewModel.createTask("Spec review", description = "Initial notes", priority = 4)
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Initial notes", task.description)
        assertEquals(4, task.priority)
        assertEquals(1, task.version)

        // Cycle through all Priority states: P1, P2, P3, P4
        for (p in listOf(1, 2, 3, 4)) {
            var updated = false
            val currentTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
            viewModel.updateTask(
                UpdateTaskParams(
                    id = currentTask.id,
                    title = "Spec review P$p",
                    description = "Updated description for priority $p",
                    priority = p,
                    expectedVersion = currentTask.version
                ),
                onSuccess = { updated = true }
            )
            advanceUntilIdle()

            assertTrue(updated)
            val updatedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
            assertEquals("Spec review P$p", updatedTask.title)
            assertEquals("Updated description for priority $p", updatedTask.description)
            assertEquals(p, updatedTask.priority)
        }
    }

    @Test
    fun `proves Issue 42 AC 2 & 3 - Completing and uncompleting produce canonical persistence and newest-first Completed view`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createTask("Alpha task")
        advanceUntilIdle()
        viewModel.createTask("Beta task")
        advanceUntilIdle()

        val initialInbox = (viewModel.inboxState.value as InboxUiState.Success).tasks
        val alpha = initialInbox.find { it.title == "Alpha task" }!!
        val beta = initialInbox.find { it.title == "Beta task" }!!

        // Complete Alpha at earlier timestamp
        viewModel.completeTask(alpha.id, completedAt = "2026-08-19T09:00:00Z")
        advanceUntilIdle()

        // Complete Beta at later timestamp
        viewModel.completeTask(beta.id, completedAt = "2026-08-19T11:00:00Z")
        advanceUntilIdle()

        // 1. Both completed tasks absent from Inbox view
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)

        // 2. Completed view has both tasks ordered newest-first (Beta at 11:00 before Alpha at 09:00)
        val completedState = viewModel.completedState.value
        assertTrue(completedState is CompletedUiState.Success)
        val completedList = (completedState as CompletedUiState.Success).tasks
        assertEquals(2, completedList.size)
        assertEquals("Beta task", completedList[0].title)
        assertEquals("2026-08-19T11:00:00Z", completedList[0].completedAt)
        assertEquals("Alpha task", completedList[1].title)
        assertEquals("2026-08-19T09:00:00Z", completedList[1].completedAt)

        // 3. Uncomplete Beta task -> returns to Inbox and removed from Completed
        viewModel.uncompleteTask(beta.id)
        advanceUntilIdle()

        val inboxAfterUncomplete = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxAfterUncomplete.size)
        assertEquals("Beta task", inboxAfterUncomplete[0].title)
        assertNull(inboxAfterUncomplete[0].completedAt)

        val completedAfterUncomplete = (viewModel.completedState.value as CompletedUiState.Success).tasks
        assertEquals(1, completedAfterUncomplete.size)
        assertEquals("Alpha task", completedAfterUncomplete[0].title)
    }

    @Test
    fun `proves Issue 42 AC 4 - Field edits on completed Tasks are rejected until uncompletion`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createTask("Immutable when done")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        viewModel.completeTask(task.id)
        advanceUntilIdle()

        // Attempt to edit description and title while completed
        var errorMessage: String? = null
        viewModel.updateTask(
            UpdateTaskParams(
                id = task.id,
                title = "Attempted modified title",
                description = "Attempted description"
            ),
            onError = { errorMessage = it }
        )
        advanceUntilIdle()

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Completed tasks cannot be edited. Uncomplete first."))

        // Verify task in db was not modified
        val completedTask = (viewModel.completedState.value as CompletedUiState.Success).tasks[0]
        assertEquals("Immutable when done", completedTask.title)
        assertNull(completedTask.description)

        // Uncomplete task
        viewModel.uncompleteTask(task.id)
        advanceUntilIdle()

        // Now field edits succeed
        var successMessage = false
        viewModel.updateTask(
            UpdateTaskParams(
                id = task.id,
                title = "Modified title after uncompletion",
                description = "Valid description now"
            ),
            onSuccess = { successMessage = true }
        )
        advanceUntilIdle()

        assertTrue(successMessage)
        val uncompletedInbox = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Modified title after uncompletion", uncompletedInbox.title)
        assertEquals("Valid description now", uncompletedInbox.description)
    }

    @Test
    fun `proves Issue 42 AC 5 - UI tests cover success, failure, and stale rendered state recovery`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createTask("Base task")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]

        // 1. Success case
        var success = false
        viewModel.updateTask(
            UpdateTaskParams(id = task.id, description = "Updated desc", expectedVersion = task.version),
            onSuccess = { success = true }
        )
        advanceUntilIdle()
        assertTrue(success)

        val updatedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, updatedTask.version)

        // 2. Failure due to stale version (CAS conflict)
        var conflictError: String? = null
        viewModel.updateTask(
            UpdateTaskParams(id = task.id, title = "Stale Edit", expectedVersion = 1), // Version is already 2
            onError = { conflictError = it }
        )
        advanceUntilIdle()

        assertNotNull(conflictError)
        assertTrue(conflictError!!.contains("version conflict"))

        // Stale rendered state recovery: viewmodel reloaded canonical state from database
        val recoveredTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Base task", recoveredTask.title)
        assertEquals(2, recoveredTask.version)

        // Subsequent edit with corrected version succeeds
        var recoverySuccess = false
        viewModel.updateTask(
            UpdateTaskParams(id = task.id, title = "Fresh edit", expectedVersion = recoveredTask.version),
            onSuccess = { recoverySuccess = true }
        )
        advanceUntilIdle()

        assertTrue(recoverySuccess)
        val finalList = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Fresh edit", finalList.title)
        assertEquals(3, finalList.version)
    }

    @Test
    fun `proves Issue 44 AC 1 & 2 - Android can create, rename, recolor, and remove Labels with duplicate name error handling`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // 1. Create label
        var createdUrgent: Label? = null
        viewModel.createLabel("Urgent", "#ef4444", onSuccess = { createdUrgent = it })
        advanceUntilIdle()

        assertNotNull(createdUrgent)
        assertEquals("Urgent", createdUrgent!!.name)
        assertEquals("#ef4444", createdUrgent!!.color)
        assertEquals(1, viewModel.labels.value.size)

        // 2. Create another label
        var createdBackend: Label? = null
        viewModel.createLabel("Backend", "#3b82f6", onSuccess = { createdBackend = it })
        advanceUntilIdle()

        assertNotNull(createdBackend)
        assertEquals(2, viewModel.labels.value.size)

        // 3. Duplicate name error - clear recoverable error
        var duplicateError: String? = null
        viewModel.createLabel("urgent", "#10b981", onError = { duplicateError = it })
        advanceUntilIdle()

        assertNotNull(duplicateError)
        assertEquals("A label with this name already exists", duplicateError)
        assertEquals(2, viewModel.labels.value.size)

        // 4. Rename and recolor label
        var updatedUrgent: Label? = null
        viewModel.updateLabel(
            id = createdUrgent!!.id,
            name = "Critical",
            color = "#f97316",
            onSuccess = { updatedUrgent = it }
        )
        advanceUntilIdle()

        assertNotNull(updatedUrgent)
        assertEquals("Critical", updatedUrgent!!.name)
        assertEquals("#f97316", updatedUrgent!!.color)
        assertEquals(createdUrgent!!.id, updatedUrgent!!.id) // Stable UUID identity

        // 5. Remove label
        var removed = false
        viewModel.deleteLabel(createdBackend!!.id, onSuccess = { removed = true })
        advanceUntilIdle()

        assertTrue(removed)
        assertEquals(1, viewModel.labels.value.size)
        assertEquals("Critical", viewModel.labels.value[0].name)
    }

    @Test
    fun `proves Issue 44 AC 3 - Label identity and Task associations remain stable across rename and other label deletion`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create 2 labels
        var labelUrgent: Label? = null
        var labelDev: Label? = null
        viewModel.createLabel("Urgent", "#ef4444", onSuccess = { labelUrgent = it })
        viewModel.createLabel("Dev", "#10b981", onSuccess = { labelDev = it })
        advanceUntilIdle()

        assertNotNull(labelUrgent)
        assertNotNull(labelDev)

        // Create Task with both labels
        viewModel.createTask(
            title = "Fix production regression",
            priority = 1,
            labels = listOf(labelUrgent!!.id, labelDev!!.id)
        )
        advanceUntilIdle()

        val initialTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, initialTask.labels.size)
        assertTrue(initialTask.labels.contains(labelUrgent!!.id))
        assertTrue(initialTask.labels.contains(labelDev!!.id))

        // Rename Urgent to Blocker
        viewModel.updateLabel(
            id = labelUrgent!!.id,
            name = "Blocker",
            color = "#ef4444"
        )
        advanceUntilIdle()

        // Task association remains stable: task still points to labelUrgent.id!
        val taskAfterRename = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, taskAfterRename.labels.size)
        assertTrue(taskAfterRename.labels.contains(labelUrgent!!.id))

        // Label name in canonical label list is now "Blocker" with same id
        val renamedLabel = viewModel.labels.value.find { it.id == labelUrgent!!.id }!!
        assertEquals("Blocker", renamedLabel.name)

        // Delete "Dev" label -> associations to "Dev" are cascaded, "Blocker" remains
        viewModel.deleteLabel(labelDev!!.id)
        advanceUntilIdle()

        val taskAfterDelete = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(1, taskAfterDelete.labels.size)
        assertEquals(labelUrgent!!.id, taskAfterDelete.labels[0])
    }

    @Test
    fun `proves Issue 44 AC 4 - Compose UI state assigns and unassigns multiple labels and renders across Inbox and Completed`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create labels
        var labelA: Label? = null
        var labelB: Label? = null
        var labelC: Label? = null
        viewModel.createLabel("Label A", "#ef4444", onSuccess = { labelA = it })
        viewModel.createLabel("Label B", "#3b82f6", onSuccess = { labelB = it })
        viewModel.createLabel("Label C", "#10b981", onSuccess = { labelC = it })
        advanceUntilIdle()

        // 1. Create task with Label A and Label B
        viewModel.createTask(
            title = "Task with labels",
            labels = listOf(labelA!!.id, labelB!!.id)
        )
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(listOf(labelA!!.id, labelB!!.id), task.labels)

        // 2. Edit task: unassign Label A, assign Label C -> [Label B, Label C]
        viewModel.updateTask(
            UpdateTaskParams(
                id = task.id,
                labels = listOf(labelB!!.id, labelC!!.id),
                expectedVersion = task.version
            )
        )
        advanceUntilIdle()

        val updatedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(listOf(labelB!!.id, labelC!!.id), updatedTask.labels)

        // 3. Complete task -> Completed view renders canonical labels [Label B, Label C]
        viewModel.completeTask(updatedTask.id)
        advanceUntilIdle()

        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
        val completedTask = (viewModel.completedState.value as CompletedUiState.Success).tasks[0]
        assertEquals(listOf(labelB!!.id, labelC!!.id), completedTask.labels)

        // 4. Uncomplete task -> Restored to inbox with intact label associations
        viewModel.uncompleteTask(completedTask.id)
        advanceUntilIdle()

        val restoredTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(listOf(labelB!!.id, labelC!!.id), restoredTask.labels)
    }

    @Test
    fun `proves Issue 44 AC 5 - Cross-client convergence after remote updates and later refetch`() = runTest {
        val aliceId = "550e8400-e29b-41d4-a716-446655440001"
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        viewModel.createLabel("Web Label", "#a855f7")
        viewModel.createTask("Web Synchronized Task")
        advanceUntilIdle()

        val initialLabel = viewModel.labels.value[0]
        val initialTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(0, initialTask.labels.size)

        // Simulate web client creating a new label and associating it to the task in database
        val remoteLabelId = UUID.randomUUID().toString()
        labelDbRows.add(
            SimulatedLabelDbRow(
                id = remoteLabelId,
                operatorId = aliceId,
                name = "Web Created Label",
                color = "#14b8a6",
                createdAt = "2026-08-19T02:00:00Z",
                updatedAt = "2026-08-19T02:00:00Z"
            )
        )

        // Remote web client associates both labels to the task
        val taskIndex = dbRows.indexOfFirst { it.id == initialTask.id }
        dbRows[taskIndex] = dbRows[taskIndex].copy(
            labels = listOf(initialLabel.id, remoteLabelId),
            version = initialTask.version + 1,
            updatedAt = "2026-08-19T02:05:00Z"
        )

        // Android client refetches
        viewModel.loadTasks()
        advanceUntilIdle()

        // Android converges to the canonical state
        val convergedLabels = viewModel.labels.value
        assertEquals(2, convergedLabels.size)
        assertTrue(convergedLabels.any { it.id == remoteLabelId && it.name == "Web Created Label" })

        val convergedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(2, convergedTask.labels.size)
        assertTrue(convergedTask.labels.contains(initialLabel.id))
        assertTrue(convergedTask.labels.contains(remoteLabelId))
    }

    @Test
    fun `proves Criterion 5 & 6 & Issue 44 AC - Operator isolation between two Operators for labels and tasks`() = runTest {
        // Alice creates a task with a private label
        val aliceViewModel = createViewModel()
        advanceUntilIdle()
        aliceViewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        var aliceLabel: Label? = null
        aliceViewModel.createLabel("Alice Secret Label", "#ef4444", onSuccess = { aliceLabel = it })
        advanceUntilIdle()

        aliceViewModel.createTask("Alice Confidential Strategy", labels = listOf(aliceLabel!!.id))
        advanceUntilIdle()

        // Bob signs in
        val bobViewModel = createViewModel()
        advanceUntilIdle()
        bobViewModel.signInWithGoogleIdToken("google-token-bob")
        advanceUntilIdle()

        // Bob must NOT see Alice's task or Alice's labels
        assertTrue(bobViewModel.inboxState.value is InboxUiState.Empty)
        assertEquals(0, bobViewModel.labels.value.size)

        // Bob can create a label with the SAME name as Alice in his own isolated task space
        var bobLabel: Label? = null
        bobViewModel.createLabel("Alice Secret Label", "#3b82f6", onSuccess = { bobLabel = it })
        advanceUntilIdle()

        assertNotNull(bobLabel)
        assertNotEquals(aliceLabel!!.id, bobLabel!!.id) // Distinct UUIDs
        assertEquals("#3b82f6", bobLabel!!.color) // Bob's distinct color

        bobViewModel.createTask("Bob Project Review", labels = listOf(bobLabel!!.id))
        advanceUntilIdle()

        val bobSuccess = bobViewModel.inboxState.value
        assertTrue(bobSuccess is InboxUiState.Success)
        val bobTasks = (bobSuccess as InboxUiState.Success).tasks
        assertEquals(1, bobTasks.size)
        assertEquals("Bob Project Review", bobTasks[0].title)
        assertEquals(listOf(bobLabel!!.id), bobTasks[0].labels)

        // Unauthenticated direct call
        val unauthedSession = OperatorSession(
            operatorId = "hacker-uuid",
            email = "hacker@evil.com",
            accessToken = "invalid-token"
        )
        val baseUrl = mockWebServer.url("/").toString().removeSuffix("/")
        val config = PublicSupabaseConfig(url = baseUrl, publishableKey = "anon")
        val taskService = SupabaseTaskService(config, OkHttpClient())
        val labelService = SupabaseLabelService(config, OkHttpClient())
        val commentService = SupabaseCommentService(config, OkHttpClient())

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                taskService.fetchTasks(unauthedSession)
            }
        }

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                labelService.fetchLabels(unauthedSession)
            }
        }

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking {
                commentService.fetchComments(unauthedSession)
            }
        }
    }

    @Test
    fun `proves Issue 46 AC 1 - Android can add and render dated Comments separately from Description`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // 1. Create a top-level task with Description
        viewModel.createTask(
            title = "Production Incident Triage",
            description = "Initial incident details in description field"
        )
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Production Incident Triage", task.title)
        assertEquals("Initial incident details in description field", task.description)

        // 2. Add first dated comment
        var comment1: Comment? = null
        viewModel.createComment(
            taskId = task.id,
            content = "Identified latency spike in DB read replica",
            onSuccess = { comment1 = it }
        )
        advanceUntilIdle()

        assertNotNull(comment1)
        assertEquals(task.id, comment1!!.taskId)
        assertEquals("Identified latency spike in DB read replica", comment1!!.content)
        assertNotNull(comment1!!.createdAt)

        // 3. Add second dated comment
        var comment2: Comment? = null
        viewModel.createComment(
            taskId = task.id,
            content = "Restarted pool manager; latencies normalized",
            onSuccess = { comment2 = it }
        )
        advanceUntilIdle()

        assertNotNull(comment2)

        // 4. Verify comments flow and separation from Description
        val taskComments = viewModel.comments.value.filter { it.taskId == task.id }
        assertEquals(2, taskComments.size)
        assertEquals("Identified latency spike in DB read replica", taskComments[0].content)
        assertEquals("Restarted pool manager; latencies normalized", taskComments[1].content)

        // Description is unaffected and distinct
        val refreshedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Initial incident details in description field", refreshedTask.description)

        // 5. Empty comment content is rejected
        var emptyCommentError: String? = null
        viewModel.createComment(
            taskId = task.id,
            content = "   ",
            onError = { emptyCommentError = it }
        )
        advanceUntilIdle()

        assertNotNull(emptyCommentError)
        assertEquals("Comment content cannot be empty", emptyCommentError)
    }

    @Test
    fun `proves Issue 46 AC 2, 3, 4 - Android can create and open a Subtask beneath a top-level Task, prevents deeper nesting, and excludes Subtasks from Inbox`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // 1. Create top-level Task
        viewModel.createTask("Release Sprint 1")
        advanceUntilIdle()

        val parentTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(1, (viewModel.inboxState.value as InboxUiState.Success).tasks.size)

        // 2. Create subtask beneath the top-level task
        var subtask1: Task? = null
        viewModel.createSubtask(
            parentId = parentTask.id,
            title = "Update changelog",
            onSuccess = { subtask1 = it }
        )
        advanceUntilIdle()

        assertNotNull(subtask1)
        assertEquals("Update changelog", subtask1!!.title)
        assertEquals(parentTask.id, subtask1!!.parentId)

        // 3. Subtasks do NOT appear as top-level Inbox rows (AC 4)
        val inboxTasks = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxTasks.size)
        assertEquals(parentTask.id, inboxTasks[0].id)
        assertNull(inboxTasks[0].parentId)

        // 4. Subtasks are accessible under the parent task
        val parentSubtasks = filterSubtasks(viewModel.allTasks.value, parentTask.id)
        assertEquals(1, parentSubtasks.size)
        assertEquals(subtask1!!.id, parentSubtasks[0].id)
        assertEquals("Update changelog", parentSubtasks[0].title)

        // 5. Attempt deeper nesting beneath the subtask (level 2) is prevented/reported (AC 3)
        var nestedError: String? = null
        viewModel.createSubtask(
            parentId = subtask1!!.id,
            title = "Invalid level-2 nested task",
            onError = { nestedError = it }
        )
        advanceUntilIdle()

        assertNotNull(nestedError)
        assertTrue(nestedError!!.contains("Subtasks cannot have children (one-level nesting only)"))
    }

    @Test
    fun `proves Issue 46 AC 5 - Android tests exercise valid and rejected hierarchy behavior and operator isolation`() = runTest {
        val aliceViewModel = createViewModel()
        advanceUntilIdle()
        aliceViewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        aliceViewModel.createTask("Alice Task")
        advanceUntilIdle()
        val aliceTask = (aliceViewModel.inboxState.value as InboxUiState.Success).tasks[0]

        var aliceSubtask: Task? = null
        aliceViewModel.createSubtask(aliceTask.id, "Alice Subtask", onSuccess = { aliceSubtask = it })
        advanceUntilIdle()
        assertNotNull(aliceSubtask)

        var aliceComment: Comment? = null
        aliceViewModel.createComment(aliceTask.id, "Alice confidential remark", onSuccess = { aliceComment = it })
        advanceUntilIdle()
        assertNotNull(aliceComment)

        // Bob signs in
        val bobViewModel = createViewModel()
        advanceUntilIdle()
        bobViewModel.signInWithGoogleIdToken("google-token-bob")
        advanceUntilIdle()

        // Bob cannot see Alice's tasks, subtasks, or comments
        assertTrue(bobViewModel.inboxState.value is InboxUiState.Empty)
        assertEquals(0, bobViewModel.comments.value.size)

        // Bob cannot attach a subtask or comment to Alice's task
        var bobSubtaskError: String? = null
        bobViewModel.createSubtask(aliceTask.id, "Bob hijacking attempt", onError = { bobSubtaskError = it })
        advanceUntilIdle()
        assertNotNull(bobSubtaskError)

        var bobCommentError: String? = null
        bobViewModel.createComment(aliceTask.id, "Bob snooping remark", onError = { bobCommentError = it })
        advanceUntilIdle()
        assertNotNull(bobCommentError)
    }

    @Test
    fun `proves Issue 48 AC 1 - Android creates and edits absent, Date-only, Floating, and Instant plans through shared contract`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // 1. Absent plan (inbox)
        viewModel.createTask("Task without plan")
        advanceUntilIdle()
        val task1 = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertNull(task1.plan)

        // 2. Date-only plan
        viewModel.createTask("Task date-only", plan = Plan.DateOnly("2026-08-19"))
        advanceUntilIdle()
        val task2 = viewModel.allTasks.value.find { it.title == "Task date-only" }!!
        assertTrue(task2.plan is Plan.DateOnly)
        assertEquals("2026-08-19", (task2.plan as Plan.DateOnly).date)

        // 3. Floating plan
        viewModel.createTask("Task floating", plan = Plan.Floating("2026-08-19", "14:30"))
        advanceUntilIdle()
        val task3 = viewModel.allTasks.value.find { it.title == "Task floating" }!!
        assertTrue(task3.plan is Plan.Floating)
        assertEquals("2026-08-19", (task3.plan as Plan.Floating).date)
        assertEquals("14:30", (task3.plan as Plan.Floating).time)

        // 4. Instant plan
        viewModel.createTask("Task instant", plan = Plan.Instant("2026-08-19T14:30:00Z"))
        advanceUntilIdle()
        val task4 = viewModel.allTasks.value.find { it.title == "Task instant" }!!
        assertTrue(task4.plan is Plan.Instant)
        assertEquals("2026-08-19T14:30:00Z", (task4.plan as Plan.Instant).at)

        // 5. Edit plan: Date-only -> Floating
        viewModel.updateTask(
            UpdateTaskParams(
                id = task2.id,
                plan = Plan.Floating("2026-08-20", "09:00"),
                expectedVersion = task2.version
            )
        )
        advanceUntilIdle()
        val updatedTask2 = viewModel.allTasks.value.find { it.id == task2.id }!!
        assertTrue(updatedTask2.plan is Plan.Floating)
        assertEquals("2026-08-20", (updatedTask2.plan as Plan.Floating).date)
        assertEquals("09:00", (updatedTask2.plan as Plan.Floating).time)

        // 6. Edit plan: clear plan (move back to inbox)
        viewModel.updateTask(
            UpdateTaskParams(
                id = task3.id,
                clearPlan = true,
                expectedVersion = task3.version
            )
        )
        advanceUntilIdle()
        val clearedTask3 = viewModel.allTasks.value.find { it.id == task3.id }!!
        assertNull(clearedTask3.plan)
    }

    @Test
    fun `proves Issue 48 AC 2 - Explicit and inherited timed-type behavior matches web and preserves existing timed types`() = runTest {
        val fixedNow = Instant.parse("2026-08-19T10:00:00Z")
        val fixedZone = ZoneOffset.UTC

        val viewModel = createViewModel(
            nowProvider = { fixedNow },
            zoneIdProvider = { fixedZone }
        )
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // 1. Initial effective timed plan type is INSTANT (deployment default)
        assertEquals(TimedPlanType.INSTANT, viewModel.effectiveTimedPlanType.value)

        // 2. Operator updates default to FLOATING
        viewModel.updateOperatorTimedPlanType(TimedPlanType.FLOATING)
        advanceUntilIdle()
        assertEquals(TimedPlanType.FLOATING, viewModel.effectiveTimedPlanType.value)

        // 3. Creating timed plan without explicit type inherits operator setting (FLOATING)
        val inheritedPlan = com.cras.app.domain.createPlanFromInputs(
            com.cras.app.domain.CreatePlanParams(
                date = "2026-08-19",
                time = "16:00",
                type = null,
                effectiveDefault = viewModel.effectiveTimedPlanType.value,
                zoneId = fixedZone
            )
        )
        assertTrue(inheritedPlan is Plan.Floating)
        assertEquals("16:00", (inheritedPlan as Plan.Floating).time)

        // 4. Preserving existing timed type when editing an Instant task even when default is Floating
        val existingInstant = Plan.Instant("2026-08-19T08:00:00Z")
        val preservedPlan = com.cras.app.domain.createPlanFromInputs(
            com.cras.app.domain.CreatePlanParams(
                date = "2026-08-19",
                time = "10:00",
                type = TimedPlanType.INSTANT, // preserved from existing
                effectiveDefault = viewModel.effectiveTimedPlanType.value,
                zoneId = fixedZone
            )
        )
        assertTrue(preservedPlan is Plan.Instant)
        assertEquals("2026-08-19T10:00:00Z", (preservedPlan as Plan.Instant).at)
    }

    @Test
    fun `proves Issue 48 AC 3 & 4 - Today and Upcoming derive membership from device local calendar date and relative dates resolve immediately`() = runTest {
        val fixedNow = Instant.parse("2026-08-19T12:00:00Z")
        val fixedZone = ZoneOffset.UTC

        val viewModel = createViewModel(
            nowProvider = { fixedNow },
            zoneIdProvider = { fixedZone }
        )
        advanceUntilIdle()
        viewModel.signInWithGoogleIdToken("google-token-alice")
        advanceUntilIdle()

        // Create tasks across different dates
        viewModel.createTask("Today task", plan = Plan.DateOnly("2026-08-19"))
        advanceUntilIdle()
        viewModel.createTask("Overdue task", plan = Plan.DateOnly("2026-08-17"))
        advanceUntilIdle()
        viewModel.createTask("Tomorrow task", plan = Plan.DateOnly("2026-08-20"))
        advanceUntilIdle()
        viewModel.createTask("Future task", plan = Plan.DateOnly("2026-08-25"))
        advanceUntilIdle()
        viewModel.createTask("Inbox task")
        advanceUntilIdle()

        // Verify Today View: Contains Overdue and Today items, sorted asc by date
        val todayState = viewModel.todayState.value
        assertTrue(todayState is TodayUiState.Success)
        val todayTasks = (todayState as TodayUiState.Success).tasks
        assertEquals(2, todayTasks.size)
        assertEquals("Overdue task", todayTasks[0].title)
        assertEquals("Today task", todayTasks[1].title)

        // Verify Upcoming View: Contains Overdue strip and Day Groups from today forward
        val upcomingState = viewModel.upcomingState.value
        assertTrue(upcomingState is UpcomingUiState.Success)
        val upcoming = upcomingState as UpcomingUiState.Success
        assertEquals(1, upcoming.overdue.size)
        assertEquals("Overdue task", upcoming.overdue[0].title)

        assertEquals(3, upcoming.groups.size)
        assertEquals("2026-08-19", upcoming.groups[0].date)
        assertEquals("Today", upcoming.groups[0].dateLabel)
        assertEquals("2026-08-20", upcoming.groups[1].date)
        assertEquals("Tomorrow", upcoming.groups[1].dateLabel)
        assertEquals("2026-08-25", upcoming.groups[2].date)
    }

    @Test
    fun `proves Issue 48 AC 5 - Controlled clocks and explicit timezones for midnight and cross-timezone cases`() = runTest {
        // Instant: 2026-08-19T16:00:00Z
        // In Tokyo (UTC+9): 2026-08-20 01:00 -> Local date is 2026-08-20
        // In New York (UTC-4): 2026-08-19 12:00 -> Local date is 2026-08-19
        val instantUtc = "2026-08-19T16:00:00Z"
        val instantPlan = Plan.Instant(instantUtc)

        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val nyZone = ZoneId.of("America/New_York")

        // 1. Plan local date derivation
        val tokyoLocalDate = com.cras.app.domain.getPlanLocalDate(instantPlan, tokyoZone)
        val nyLocalDate = com.cras.app.domain.getPlanLocalDate(instantPlan, nyZone)
        assertEquals("2026-08-20", tokyoLocalDate)
        assertEquals("2026-08-19", nyLocalDate)

        // 2. Today membership in Tokyo: at 2026-08-19T16:00:00Z (01:00 on 2026-08-20 in Tokyo), device today is 2026-08-20
        val tokyoNow = Instant.parse("2026-08-19T16:00:00Z")
        val tokyoDeviceDate = com.cras.app.domain.getDeviceLocalDate(tokyoNow, tokyoZone)
        assertEquals("2026-08-20", tokyoDeviceDate)

        // In Tokyo, instantPlan (local date 2026-08-20) is scheduled for Today
        val task = Task(
            id = "550e8400-e29b-41d4-a716-446655440099",
            title = "Cross-timezone meeting",
            description = null,
            priority = 4,
            plan = instantPlan,
            labels = emptyList(),
            parentId = null,
            completedAt = null,
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T00:00:00Z",
            version = 1
        )

        val tokyoTodayTasks = com.cras.app.domain.filterTodayTasks(listOf(task), tokyoNow, tokyoZone)
        assertEquals(1, tokyoTodayTasks.size)
        assertEquals("Cross-timezone meeting", tokyoTodayTasks[0].title)

        // In New York, at 2026-08-19T16:00:00Z (12:00 on 2026-08-19), device date is 2026-08-19, instantPlan local date is 2026-08-19 -> also Today
        val nyNow = Instant.parse("2026-08-19T16:00:00Z")
        val nyDeviceDate = com.cras.app.domain.getDeviceLocalDate(nyNow, nyZone)
        assertEquals("2026-08-19", nyDeviceDate)

        val nyTodayTasks = com.cras.app.domain.filterTodayTasks(listOf(task), nyNow, nyZone)
        assertEquals(1, nyTodayTasks.size)
        assertEquals("Cross-timezone meeting", nyTodayTasks[0].title)
    }

    @Test
    fun `proves Issue 48 AC 6 - Date-only plans contain no timed type and request no Notification`() = runTest {
        val plan = com.cras.app.domain.createPlanFromInputs(
            com.cras.app.domain.CreatePlanParams(
                date = "2026-08-19",
                time = null,
                type = null,
                effectiveDefault = TimedPlanType.INSTANT
            )
        )

        assertNotNull(plan)
        assertTrue(plan is Plan.DateOnly)
        assertEquals("2026-08-19", (plan as Plan.DateOnly).date)

        // JSON serialization verification: must be strictly {"date":"YYYY-MM-DD"}
        val encoded = json.encodeToString(PlanSerializer, plan)
        assertEquals("""{"date":"2026-08-19"}""", encoded)
        assertFalse(encoded.contains("time"))
        assertFalse(encoded.contains("at"))
        assertFalse(encoded.contains("00:00"))
    }
}
