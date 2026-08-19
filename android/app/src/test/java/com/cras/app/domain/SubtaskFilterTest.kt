package com.cras.app.domain

import com.cras.app.models.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtaskFilterTest {

    private fun createTask(
        id: String,
        title: String = "Task",
        parentId: String? = null,
        completedAt: String? = null
    ): Task {
        return Task(
            id = id,
            title = title,
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = parentId,
            completedAt = completedAt,
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T00:00:00Z",
            version = 1
        )
    }

    @Test
    fun `isSubtask returns true when parentId is present and false when parentId is null`() {
        val topLevel = createTask(id = "550e8400-e29b-41d4-a716-446655440001")
        val subtask = createTask(id = "550e8400-e29b-41d4-a716-446655440002", parentId = topLevel.id)

        assertFalse(topLevel.isSubtask)
        assertTrue(subtask.isSubtask)
        assertFalse(isSubtaskTask(topLevel))
        assertTrue(isSubtaskTask(subtask))
    }

    @Test
    fun `filterSubtasks returns only subtasks belonging to specified parentId`() {
        val parent1 = createTask(id = "550e8400-e29b-41d4-a716-446655440001", title = "Parent 1")
        val parent2 = createTask(id = "550e8400-e29b-41d4-a716-446655440002", title = "Parent 2")
        val subtask1A = createTask(id = "550e8400-e29b-41d4-a716-446655440003", title = "Subtask 1A", parentId = parent1.id)
        val subtask1B = createTask(id = "550e8400-e29b-41d4-a716-446655440004", title = "Subtask 1B", parentId = parent1.id)
        val subtask2A = createTask(id = "550e8400-e29b-41d4-a716-446655440005", title = "Subtask 2A", parentId = parent2.id)

        val allTasks = listOf(parent1, subtask1A, parent2, subtask1B, subtask2A)

        val parent1Subtasks = filterSubtasks(allTasks, parent1.id)
        assertEquals(listOf(subtask1A, subtask1B), parent1Subtasks)

        val parent2Subtasks = filterSubtasks(allTasks, parent2.id)
        assertEquals(listOf(subtask2A), parent2Subtasks)

        val nonExistentSubtasks = filterSubtasks(allTasks, "550e8400-e29b-41d4-a716-446655440999")
        assertTrue(nonExistentSubtasks.isEmpty())
    }
}
