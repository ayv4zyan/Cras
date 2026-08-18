package com.cras.app.domain

import com.cras.app.models.Plan
import com.cras.app.models.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxFilterTest {

    private fun createTask(
        id: String = "550e8400-e29b-41d4-a716-446655440001",
        title: String = "Sample Task",
        plan: Plan? = null,
        parentId: String? = null,
        completedAt: String? = null
    ): Task {
        return Task(
            id = id,
            title = title,
            description = null,
            priority = 4,
            plan = plan,
            labels = emptyList(),
            parentId = parentId,
            completedAt = completedAt,
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T00:00:00Z",
            version = 1
        )
    }

    @Test
    fun `isInboxTask returns true for open undated top-level task`() {
        val task = createTask()
        assertTrue(isInboxTask(task))
    }

    @Test
    fun `isInboxTask returns false for completed task`() {
        val task = createTask(completedAt = "2026-08-19T01:00:00Z")
        assertFalse(isInboxTask(task))
    }

    @Test
    fun `isInboxTask returns false for task with date-only plan`() {
        val task = createTask(plan = Plan.DateOnly("2026-08-19"))
        assertFalse(isInboxTask(task))
    }

    @Test
    fun `isInboxTask returns false for task with floating plan`() {
        val task = createTask(plan = Plan.Floating("2026-08-19", "09:00"))
        assertFalse(isInboxTask(task))
    }

    @Test
    fun `isInboxTask returns false for task with instant plan`() {
        val task = createTask(plan = Plan.Instant("2026-08-19T09:00:00Z"))
        assertFalse(isInboxTask(task))
    }

    @Test
    fun `isInboxTask returns false for subtask with parentId`() {
        val task = createTask(parentId = "550e8400-e29b-41d4-a716-446655440099")
        assertFalse(isInboxTask(task))
    }

    @Test
    fun `filterInboxTasks filters out completed, dated, and subtasks`() {
        val inbox1 = createTask(id = "550e8400-e29b-41d4-a716-446655440001", title = "Inbox 1")
        val inbox2 = createTask(id = "550e8400-e29b-41d4-a716-446655440002", title = "Inbox 2")
        val completed = createTask(id = "550e8400-e29b-41d4-a716-446655440003", completedAt = "2026-08-19T01:00:00Z")
        val dated = createTask(id = "550e8400-e29b-41d4-a716-446655440004", plan = Plan.DateOnly("2026-08-20"))
        val subtask = createTask(id = "550e8400-e29b-41d4-a716-446655440005", parentId = inbox1.id)

        val result = filterInboxTasks(listOf(inbox1, completed, dated, subtask, inbox2))
        assertEquals(listOf(inbox1, inbox2), result)
    }
}
