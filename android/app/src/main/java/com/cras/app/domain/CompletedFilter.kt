package com.cras.app.domain

import com.cras.app.models.Task
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Extension property evaluating whether a task belongs to the Completed view:
 * "The view of Tasks that have a completed-at, newest first." (CONTEXT.md)
 */
val Task.isCompleted: Boolean
    get() = completedAt != null

/**
 * Checks whether a task belongs to the Completed view.
 */
fun isCompletedTask(task: Task): Boolean = task.isCompleted

private fun parseInstant(timestamp: String?): Instant? {
    if (timestamp.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(timestamp).toInstant()
    } catch (_: Exception) {
        try {
            Instant.parse(timestamp)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Filters a collection of tasks down to those belonging in the Completed view,
 * ordered newest-first (latest completedAt descending).
 */
fun filterCompletedTasks(tasks: List<Task>): List<Task> {
    return tasks
        .filter { it.isCompleted }
        .sortedWith { a, b ->
            val instantA = parseInstant(a.completedAt)
            val instantB = parseInstant(b.completedAt)
            when {
                instantA != null && instantB != null -> instantB.compareTo(instantA)
                instantA != null -> -1
                instantB != null -> 1
                else -> (b.completedAt ?: "").compareTo(a.completedAt ?: "")
            }
        }
}
