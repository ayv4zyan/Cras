package com.cras.app.contracts

import com.cras.app.models.Comment
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            val content = file.readText()
            if (file.name.startsWith("comment")) {
                val comment = json.decodeFromString<Comment>(content)
                assertNotNull("Comment ID should not be null", comment.id)
                assertNotNull("Comment taskId should not be null", comment.taskId)
                assertTrue("Comment content should not be empty", comment.content.isNotEmpty())
            } else {
                val task = json.decodeFromString<Task>(content)
                assertNotNull("Task ID should not be null", task.id)
                assertTrue("Task title should not be empty", task.title.isNotEmpty())
                assertTrue("Task priority should be between 1 and 4", task.priority in 1..4)
                assertTrue("Task version should be at least 1", task.version >= 1)
            }
        }
    }

    @Test
    fun `rejects all invalid golden boundary fixtures`() {
        val files = invalidExamplesDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        for (file in files) {
            val content = file.readText()
            var failedAsExpected = false
            if (file.name.contains("comment")) {
                try {
                    json.decodeFromString<Comment>(content)
                } catch (_: Throwable) {
                    failedAsExpected = true
                }
            } else {
                try {
                    json.decodeFromString<Task>(content)
                } catch (_: Throwable) {
                    failedAsExpected = true
                }
            }
            assertTrue("Expected invalid fixture ${file.name} to be rejected by Kotlin contract", failedAsExpected)
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
    }
}
