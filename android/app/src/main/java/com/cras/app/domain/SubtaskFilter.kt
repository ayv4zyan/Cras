package com.cras.app.domain

import com.cras.app.models.Task

/**
 * Extension property evaluating whether a task is a subtask:
 * "A Task that has a parent Task. A Subtask cannot itself have children." (CONTEXT.md)
 */
val Task.isSubtask: Boolean
    get() = parentId != null

/**
 * Checks whether a task is a subtask.
 */
fun isSubtaskTask(task: Task): Boolean = task.isSubtask

/**
 * Filters a collection of tasks down to those that are subtasks of the given parent task.
 */
fun filterSubtasks(tasks: List<Task>, parentId: String): List<Task> {
    return tasks.filter { it.parentId == parentId }
}
