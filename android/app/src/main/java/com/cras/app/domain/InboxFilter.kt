package com.cras.app.domain

import com.cras.app.models.Task

/**
 * Extension property evaluating whether a task belongs to the Inbox view:
 * "The view of open top-level Tasks that have no date. Subtasks are not in Inbox." (CONTEXT.md)
 */
val Task.isInbox: Boolean
    get() = completedAt == null && plan == null && parentId == null

/**
 * Checks whether a task belongs to the Inbox view.
 */
fun isInboxTask(task: Task): Boolean = task.isInbox

/**
 * Filters a collection of tasks down to those belonging in the Inbox view.
 */
fun filterInboxTasks(tasks: List<Task>): List<Task> {
    return tasks.filter { it.isInbox }
}
