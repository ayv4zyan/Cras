package com.cras.app.contracts

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

    @Test
    fun `golden fixtures exist and at least four are present`() {
        assertTrue("Examples dir should exist at ${examplesDir.absolutePath}", examplesDir.exists())
        val files = examplesDir.listFiles { _, name -> name.endsWith(".json") }
        assertNotNull(files)
        assertTrue("Expected at least 4 golden fixtures", files!!.size >= 4)
    }

    @Test
    fun `deserializes all golden fixtures into Kotlin Task models`() {
        val files = examplesDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        for (file in files) {
            val content = file.readText()
            val task = json.decodeFromString<Task>(content)

            assertNotNull("Task ID should not be null", task.id)
            assertTrue("Task title should not be empty", task.title.isNotEmpty())
            assertTrue("Task priority should be between 1 and 4", task.priority in 1..4)
            assertTrue("Task version should be at least 1", task.version >= 1)
        }
    }

    @Test
    fun `verifies specific golden fixture variants`() {
        // Inbox task (plan is null)
        val inboxFile = File(examplesDir, "inbox-task.json")
        if (inboxFile.exists()) {
            val task = json.decodeFromString<Task>(inboxFile.readText())
            assertEquals(null, task.plan)
        }

        // Date-only task
        val dateOnlyFile = File(examplesDir, "date-only-task.json")
        if (dateOnlyFile.exists()) {
            val task = json.decodeFromString<Task>(dateOnlyFile.readText())
            assertTrue("Expected DateOnly plan", task.plan is Plan.DateOnly)
            assertEquals("2026-08-20", (task.plan as Plan.DateOnly).date)
        }

        // Floating task
        val floatingFile = File(examplesDir, "floating-task.json")
        if (floatingFile.exists()) {
            val task = json.decodeFromString<Task>(floatingFile.readText())
            assertTrue("Expected Floating plan", task.plan is Plan.Floating)
            val floating = task.plan as Plan.Floating
            assertEquals("2026-08-19", floating.date)
            assertEquals("09:30:00", floating.time)
        }

        // Instant task
        val instantFile = File(examplesDir, "instant-task.json")
        if (instantFile.exists()) {
            val task = json.decodeFromString<Task>(instantFile.readText())
            assertTrue("Expected Instant plan", task.plan is Plan.Instant)
            assertEquals("2026-08-19T14:00:00Z", (task.plan as Plan.Instant).at)
        }
    }
}
