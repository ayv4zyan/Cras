package com.cras.app.domain

import com.cras.app.models.Task

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

/**
 * Filters a collection of tasks down to those belonging in the Completed view,
 * ordered newest-first (latest completedAt descending).
 */
fun filterCompletedTasks(tasks: List<Task>): List<Task> {
    return tasks
        .filter { it.isCompleted }
        .sortedWith { a, b ->
            val timeA = a.completedAt ?: ""
            val timeB = b.completedAt ?: ""
            timeB.compareTo(timeA)
        }
}
