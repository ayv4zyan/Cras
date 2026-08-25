package com.cras.app.quickaccess

import com.cras.app.domain.getPlanLocalDate
import com.cras.app.domain.getDeviceLocalDate
import com.cras.app.models.Task
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * A single row displayed in the Today glance widget. Both top-level Tasks and
 * their dated Subtasks whose plan day is today or earlier are flattened into
 * this representation. Field edits are not supported from the widget.
 */
@Serializable
data class TodayGlanceRow(
    val taskId: String,
    val title: String,
    /** True when this row represents a Subtask (parentId != null). */
    val isSubtask: Boolean
)

/**
 * Produces the flat list of [TodayGlanceRow]s shown in the Today glance widget:
 *
 * - Open top-level Tasks whose plan day is today or earlier.
 * - Open Subtasks (parentId != null) whose plan day is today or earlier.
 *
 * Both are sorted by plan day ascending, then by priority ascending. Completed
 * Tasks and Subtasks are excluded. Tasks without a plan are excluded.
 *
 * No de-duplication of parent + child is performed; the widget shows whatever
 * is independently in Today scope.
 */
fun buildTodayGlanceRows(
    tasks: List<Task>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): List<TodayGlanceRow> {
    val todayStr = getDeviceLocalDate(now, zoneId)
    return tasks
        .filter { task ->
            task.completedAt == null &&
                task.plan != null &&
                (getPlanLocalDate(task.plan, zoneId) ?: "") <= todayStr
        }
        .sortedWith(
            compareBy<Task> { getPlanLocalDate(it.plan, zoneId) ?: "" }
                .thenBy { it.priority }
        )
        .map { task ->
            TodayGlanceRow(
                taskId = task.id,
                title = task.title,
                isSubtask = task.parentId != null
            )
        }
}
