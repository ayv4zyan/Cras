package com.cras.app.contracts

import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskContractTest {

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    private val examplesDir: File = listOf(
        "../../../contracts/examples",
        "../../contracts/examples",
        "../contracts/examples",
        "contracts/examples"
    ).map { File(it).canonicalFile }
        .firstOrNull { it.exists() }
        ?: File("../../../contracts/examples").canonicalFile

    private val invalidExamplesDir: File = File(examplesDir, "invalid")

    private fun isCommentFixture(file: File): Boolean =
        file.name == "comment.json" || file.name.startsWith("invalid-comment-")

    private fun isLabelFixture(file: File): Boolean =
        file.name == "label.json" || file.name.startsWith("invalid-label-")

    private fun decodeFixture(file: File): Any {
        val content = file.readText()
        return when {
            isCommentFixture(file) -> json.decodeFromString<Comment>(content)
            isLabelFixture(file) -> json.decodeFromString<Label>(content)
            else -> json.decodeFromString<Task>(content)
        }
    }

    @Test
    fun `golden fixtures exist and at least seven valid fixtures are present`() {
        assertTrue("Examples dir should exist at ${examplesDir.absolutePath}", examplesDir.exists())
        val files = examplesDir.listFiles { _, name -> name.endsWith(".json") }
        assertNotNull(files)
        assertTrue("Expected at least 7 valid golden fixtures", files!!.size >= 7)
    }

    @Test
    fun `invalid fixtures exist and at least ten are present`() {
        assertTrue("Invalid examples dir should exist at ${invalidExamplesDir.absolutePath}", invalidExamplesDir.exists())
        val files = invalidExamplesDir.listFiles { _, name -> name.endsWith(".json") }
        assertNotNull(files)
        assertTrue("Expected at least 10 invalid boundary fixtures", files!!.size >= 10)
    }

    @Test
    fun `deserializes all valid golden fixtures into Kotlin models`() {
        val files = examplesDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        for (file in files) {
            when (val model = decodeFixture(file)) {
                is Comment -> {
                    assertNotNull("Comment ID should not be null", model.id)
                    assertNotNull("Comment taskId should not be null", model.taskId)
                    assertTrue("Comment content should not be empty", model.content.isNotEmpty())
                }
                is Label -> {
                    assertNotNull("Label ID should not be null", model.id)
                    assertTrue("Label name should not be empty", model.name.isNotEmpty())
                    assertTrue("Label color should start with #", model.color.startsWith("#"))
                }
                is Task -> {
                    assertNotNull("Task ID should not be null", model.id)
                    assertTrue("Task title should not be empty", model.title.isNotEmpty())
                    assertTrue("Task priority should be between 1 and 4", model.priority in 1..4)
                    assertTrue("Task version should be at least 1", model.version >= 1)
                }
            }
        }
    }

    @Test
    fun `rejects all invalid golden boundary fixtures`() {
        val files = invalidExamplesDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        for (file in files) {
            val result = runCatching { decodeFixture(file) }
            assertTrue(
                "Expected invalid fixture ${file.name} to be rejected by Kotlin contract",
                result.isFailure
            )
        }
    }

    @Test
    fun `verifies specific golden fixture variants`() {
        // 1. Inbox task (plan is null)
        val inboxFile = File(examplesDir, "inbox-task.json")
        assertTrue("inbox-task.json should exist", inboxFile.exists())
        val inboxTask = json.decodeFromString<Task>(inboxFile.readText())
        assertEquals(null, inboxTask.plan)
        assertEquals(null, inboxTask.parentId)
        assertEquals(null, inboxTask.completedAt)

        // 2. Date-only task
        val dateOnlyFile = File(examplesDir, "date-only-task.json")
        assertTrue("date-only-task.json should exist", dateOnlyFile.exists())
        val dateOnlyTask = json.decodeFromString<Task>(dateOnlyFile.readText())
        assertTrue("Expected DateOnly plan", dateOnlyTask.plan is Plan.DateOnly)
        assertEquals("2026-08-20", (dateOnlyTask.plan as Plan.DateOnly).date)

        // 3. Floating task
        val floatingFile = File(examplesDir, "floating-task.json")
        assertTrue("floating-task.json should exist", floatingFile.exists())
        val floatingTask = json.decodeFromString<Task>(floatingFile.readText())
        assertTrue("Expected Floating plan", floatingTask.plan is Plan.Floating)
        val floating = floatingTask.plan as Plan.Floating
        assertEquals("2026-08-19", floating.date)
        assertEquals("09:30:00", floating.time)

        // 4. Instant task
        val instantFile = File(examplesDir, "instant-task.json")
        assertTrue("instant-task.json should exist", instantFile.exists())
        val instantTask = json.decodeFromString<Task>(instantFile.readText())
        assertTrue("Expected Instant plan", instantTask.plan is Plan.Instant)
        assertEquals("2026-08-19T14:00:00Z", (instantTask.plan as Plan.Instant).at)

        // 5. Completed task
        val completedFile = File(examplesDir, "completed-task.json")
        assertTrue("completed-task.json should exist", completedFile.exists())
        val completedTask = json.decodeFromString<Task>(completedFile.readText())
        assertEquals("2026-08-18T16:45:00Z", completedTask.completedAt)

        // 6. Subtask (with parentId)
        val subtaskFile = File(examplesDir, "subtask.json")
        assertTrue("subtask.json should exist", subtaskFile.exists())
        val subtask = json.decodeFromString<Task>(subtaskFile.readText())
        assertEquals("11111111-1111-1111-1111-111111111111", subtask.parentId)

        // 7. Labeled task (multiple labels)
        val labeledFile = File(examplesDir, "labeled-task.json")
        assertTrue("labeled-task.json should exist", labeledFile.exists())
        val labeledTask = json.decodeFromString<Task>(labeledFile.readText())
        assertEquals(2, labeledTask.labels.size)

        // 8. Comment
        val commentFile = File(examplesDir, "comment.json")
        assertTrue("comment.json should exist", commentFile.exists())
        val comment = json.decodeFromString<Comment>(commentFile.readText())
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", comment.id)
        assertEquals("11111111-1111-1111-1111-111111111111", comment.taskId)
        assertEquals("Verified the staging run logs and all services healthy.", comment.content)

        // 9. Label
        val labelFile = File(examplesDir, "label.json")
        assertTrue("label.json should exist", labelFile.exists())
        val label = json.decodeFromString<Label>(labelFile.readText())
        assertEquals("22222222-2222-2222-2222-222222222222", label.id)
        assertEquals("Urgent", label.name)
        assertEquals("#ef4444", label.color)
    }

    @Test
    fun `label serialization handles snake_case and camelCase timestamps and optional fields`() {
        val jsonSnake = """
            {
                "id": "22222222-2222-2222-2222-222222222222",
                "name": "Backend",
                "color": "#3b82f6",
                "created_at": "2026-08-19T10:00:00Z",
                "updated_at": "2026-08-19T11:00:00Z"
            }
        """.trimIndent()
        val labelSnake = json.decodeFromString<Label>(jsonSnake)
        assertEquals("Backend", labelSnake.name)
        assertEquals("#3b82f6", labelSnake.color)
        assertEquals("2026-08-19T10:00:00Z", labelSnake.createdAt)
        assertEquals("2026-08-19T11:00:00Z", labelSnake.updatedAt)

        val jsonMinimal = """
            {
                "id": "22222222-2222-2222-2222-222222222222",
                "name": "Minimal",
                "color": "#10b981"
            }
        """.trimIndent()
        val labelMinimal = json.decodeFromString<Label>(jsonMinimal)
        assertEquals("Minimal", labelMinimal.name)
        assertEquals("#10b981", labelMinimal.color)
        assertEquals(null, labelMinimal.createdAt)
        assertEquals(null, labelMinimal.updatedAt)
    }

    @Test
    fun `plan deserialization accepts date-only plan with explicit null type`() {
        val jsonString = """
            {
                "id": "550e8400-e29b-41d4-a716-446655440000",
                "title": "Date only with null type",
                "description": null,
                "priority": 4,
                "plan": {
                    "type": null,
                    "date": "2026-08-20"
                },
                "labels": [],
                "parentId": null,
                "completedAt": null,
                "createdAt": "2026-08-19T00:00:00Z",
                "updatedAt": "2026-08-19T00:00:00Z",
                "version": 1
            }
        """.trimIndent()
        val task = json.decodeFromString<Task>(jsonString)
        assertTrue("Expected DateOnly plan", task.plan is Plan.DateOnly)
        assertEquals("2026-08-20", (task.plan as Plan.DateOnly).date)
    }
}

