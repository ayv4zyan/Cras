package com.cras.app.domain

import com.cras.app.models.Task

/**
 * Checks whether a task belongs to the Inbox view:
 * "The view of open top-level Tasks that have no date. Subtasks are not in Inbox." (CONTEXT.md)
 */
fun isInboxTask(task: Task): Boolean {
    return task.completedAt == null && task.plan == null && task.parentId == null
}

/**
 * Filters a collection of tasks down to those belonging in the Inbox view.
 */
fun filterInboxTasks(tasks: List<Task>): List<Task> {
    return tasks.filter(::isInboxTask)
}
