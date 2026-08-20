package com.cras.app.domain

import com.cras.app.models.Plan
import com.cras.app.models.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedFilterTest {

    private fun createTask(
        id: String = "550e8400-e29b-41d4-a716-446655440001",
        title: String = "Sample Task",
        description: String? = null,
        priority: Int = 4,
        plan: Plan? = null,
        parentId: String? = null,
        completedAt: String? = null
    ): Task {
        return Task(
            id = id,
            title = title,
            description = description,
            priority = priority,
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
    fun `isCompletedTask returns true only when completedAt is not null`() {
        val openTask = createTask(completedAt = null)
        val completedTask = createTask(completedAt = "2026-08-19T10:00:00Z")

        assertFalse(isCompletedTask(openTask))
        assertTrue(isCompletedTask(completedTask))
    }

    @Test
    fun `filterCompletedTasks filters out open tasks and sorts newest-first`() {
        val olderCompleted = createTask(
            id = "550e8400-e29b-41d4-a716-446655440001",
            title = "Old task",
            completedAt = "2026-08-19T08:00:00Z"
        )
        val newerCompleted = createTask(
            id = "550e8400-e29b-41d4-a716-446655440002",
            title = "New task",
            completedAt = "2026-08-19T12:00:00Z"
        )
        val openInbox = createTask(
            id = "550e8400-e29b-41d4-a716-446655440003",
            title = "Inbox open task",
            completedAt = null
        )
        val openDated = createTask(
            id = "550e8400-e29b-41d4-a716-446655440004",
            title = "Dated open task",
            plan = Plan.DateOnly("2026-08-20"),
            completedAt = null
        )

        val result = filterCompletedTasks(listOf(openInbox, olderCompleted, openDated, newerCompleted))

        assertEquals(2, result.size)
        assertEquals(newerCompleted.id, result[0].id)
        assertEquals(olderCompleted.id, result[1].id)
    }

    @Test
    fun `filterCompletedTasks handles timezone offsets and fractional seconds correctly`() {
        // 13:00+02:00 corresponds to 11:00 UTC (older than 12:00 UTC)
        val taskWithOffset = createTask(
            id = "550e8400-e29b-41d4-a716-446655440010",
            title = "Offset task",
            completedAt = "2026-08-19T13:00:00+02:00"
        )
        // 12:00:00.900Z is newer than 12:00:00Z
        val taskWithFracSeconds = createTask(
            id = "550e8400-e29b-41d4-a716-446655440011",
            title = "Fractional seconds task",
            completedAt = "2026-08-19T12:00:00.900Z"
        )
        val taskUtc = createTask(
            id = "550e8400-e29b-41d4-a716-446655440012",
            title = "UTC task",
            completedAt = "2026-08-19T12:00:00Z"
        )

        val result = filterCompletedTasks(listOf(taskWithOffset, taskUtc, taskWithFracSeconds))

        assertEquals(3, result.size)
        assertEquals(taskWithFracSeconds.id, result[0].id)
        assertEquals(taskUtc.id, result[1].id)
        assertEquals(taskWithOffset.id, result[2].id)
    }
}
