package com.cras.app.domain

import com.cras.app.models.Plan
import com.cras.app.models.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TimedPlanType(val value: String) {
    INSTANT("instant"),
    FLOATING("floating");

    companion object {
        fun fromValue(value: String?): TimedPlanType? = when (value?.lowercase()) {
            "instant" -> INSTANT
            "floating" -> FLOATING
            else -> null
        }
    }
}

data class CreatePlanParams(
    val date: String? = null,
    val time: String? = null,
    val type: TimedPlanType? = null,
    val effectiveDefault: TimedPlanType = TimedPlanType.INSTANT,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val now: Instant = Instant.now()
)

data class PlanDisplayInfo(
    val dateLabel: String,
    val timeLabel: String?,
    val typeLabel: String?,
    val isOverdue: Boolean
)

data class UpcomingDayGroup(
    val date: String,
    val dateLabel: String,
    val tasks: List<Task>
)

data class UpcomingResult(
    val overdue: List<Task>,
    val groups: List<UpcomingDayGroup>
)

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME_FORMATTER_SHORT = DateTimeFormatter.ofPattern("HH:mm")
private val FRIENDLY_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

/**
 * Returns the device-local calendar date formatted as YYYY-MM-DD.
 */
fun getDeviceLocalDate(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val zonedDateTime = now.atZone(zoneId)
    return zonedDateTime.toLocalDate().format(DATE_FORMATTER)
}

/**
 * Formats a calendar date string (YYYY-MM-DD) into a friendly label ("Today", "Tomorrow", "Yesterday", "Wed, Aug 19").
 */
fun formatFriendlyDateLabel(
    dateStr: String,
    todayStr: String,
    tomorrowStr: String,
    yesterdayStr: String? = null
): String {
    if (dateStr == todayStr) {
        return "Today"
    }
    if (dateStr == tomorrowStr) {
        return "Tomorrow"
    }
    if (yesterdayStr != null && dateStr == yesterdayStr) {
        return "Yesterday"
    }

    return try {
        val parsedDate = LocalDate.parse(dateStr, DATE_FORMATTER)
        parsedDate.format(FRIENDLY_DATE_FORMATTER)
    } catch (_: Exception) {
        dateStr
    }
}

/**
 * Extracts the device-local calendar date (YYYY-MM-DD) representing a task plan.
 * For Date-only and Floating, this is the stored calendar date.
 * For Instant, this is the device-local date of the UTC moment in the acting zoneId.
 */
fun getPlanLocalDate(
    plan: Plan?,
    zoneId: ZoneId = ZoneId.systemDefault()
): String? {
    if (plan == null) return null

    return when (plan) {
        is Plan.DateOnly -> plan.date
        is Plan.Floating -> plan.date
        is Plan.Instant -> {
            try {
                val instant = Instant.parse(plan.at)
                val zonedDateTime = instant.atZone(zoneId)
                zonedDateTime.toLocalDate().format(DATE_FORMATTER)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Creates a contract-compliant Plan object from user inputs.
 * - If date is omitted or blank, returns null.
 * - If time is omitted or blank, returns Date-only plan Plan.DateOnly(date) without fake midnight or type.
 * - If time is provided, resolves explicit type or falls back to effective default.
 * - For Instant, converts the viewing device's local date + local time into RFC 3339 UTC ISO string.
 * - For Floating, stores the exact date and time without timezone adjustment.
 */
fun createPlanFromInputs(params: CreatePlanParams): Plan? {
    val rawDate = params.date?.trim()
    if (rawDate.isNullOrEmpty()) {
        return null
    }

    val rawTime = params.time?.trim()
    if (rawTime.isNullOrEmpty()) {
        return Plan.DateOnly(date = rawDate)
    }

    // Format time as HH:mm
    val timeParts = rawTime.split(":")
    if (timeParts.size < 2) {
        return Plan.DateOnly(date = rawDate)
    }
    val hours = timeParts[0].padStart(2, '0')
    val minutes = timeParts[1].padStart(2, '0')
    val normalizedTime = "$hours:$minutes"

    val resolvedType = params.type ?: params.effectiveDefault

    if (resolvedType == TimedPlanType.FLOATING) {
        return Plan.Floating(
            date = rawDate,
            time = normalizedTime
        )
    }

    // Instant: resolve local date & time in zoneId to UTC ISO string
    return try {
        val parsedDate = LocalDate.parse(rawDate, DATE_FORMATTER)
        val parsedTime = LocalTime.of(hours.toInt(), minutes.toInt())
        val localDateTime = LocalDateTime.of(parsedDate, parsedTime)
        val zonedDateTime = localDateTime.atZone(params.zoneId)
        val utcInstant = zonedDateTime.toInstant()
        Plan.Instant(at = utcInstant.toString())
    } catch (_: Exception) {
        Plan.DateOnly(date = rawDate)
    }
}

/**
 * Checks whether an open task is overdue relative to device's today.
 */
fun isTaskOverdue(
    task: Task,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): Boolean {
    if (task.completedAt != null || task.plan == null) {
        return false
    }
    val planDay = getPlanLocalDate(task.plan, zoneId) ?: return false
    val todayStr = getDeviceLocalDate(now, zoneId)
    return planDay < todayStr
}

/**
 * Formats a Plan for display in the UI using relative date semantics.
 */
fun formatPlanDisplay(
    plan: Plan?,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): PlanDisplayInfo? {
    if (plan == null) return null

    val todayLocalDate = now.atZone(zoneId).toLocalDate()
    val todayStr = todayLocalDate.format(DATE_FORMATTER)
    val tomorrowStr = todayLocalDate.plusDays(1).format(DATE_FORMATTER)
    val yesterdayStr = todayLocalDate.minusDays(1).format(DATE_FORMATTER)

    val planDateStr = getPlanLocalDate(plan, zoneId) ?: return null
    val dateLabel = formatFriendlyDateLabel(planDateStr, todayStr, tomorrowStr, yesterdayStr)

    var timeLabel: String? = null
    var typeLabel: String? = null

    when (plan) {
        is Plan.DateOnly -> {
            timeLabel = null
            typeLabel = null
        }
        is Plan.Floating -> {
            typeLabel = "Floating"
            val parts = plan.time.split(":")
            timeLabel = if (parts.size >= 2) "${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}" else plan.time
        }
        is Plan.Instant -> {
            typeLabel = "Instant"
            try {
                val instant = Instant.parse(plan.at)
                val zonedDateTime = instant.atZone(zoneId)
                timeLabel = zonedDateTime.toLocalTime().format(TIME_FORMATTER_SHORT)
            } catch (_: Exception) {
                timeLabel = null
            }
        }
    }

    val isOverdue = planDateStr < todayStr

    return PlanDisplayInfo(
        dateLabel = dateLabel,
        timeLabel = timeLabel,
        typeLabel = typeLabel,
        isOverdue = isOverdue
    )
}

/**
 * Filters tasks belonging to the Today view:
 * "The view of open Tasks whose plan day is today or earlier, using the viewing device's local calendar date.
 * Completed Tasks are not shown."
 */
fun filterTodayTasks(
    tasks: List<Task>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): List<Task> {
    val todayStr = getDeviceLocalDate(now, zoneId)

    return tasks
        .filter { task ->
            if (task.completedAt != null || task.plan == null) {
                false
            } else {
                val planDay = getPlanLocalDate(task.plan, zoneId)
                planDay != null && planDay <= todayStr
            }
        }
        .sortedWith(
            compareBy<Task> { getPlanLocalDate(it.plan, zoneId) ?: "" }
                .thenBy { it.priority }
        )
}

/**
 * Filters tasks belonging to the Upcoming view:
 * "The view of open dated Tasks grouped by day, from today into the future, with overdue in a strip at the top.
 * There is no 7-day window."
 */
fun filterUpcomingTasks(
    tasks: List<Task>,
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): UpcomingResult {
    val todayLocalDate = now.atZone(zoneId).toLocalDate()
    val todayStr = todayLocalDate.format(DATE_FORMATTER)
    val tomorrowStr = todayLocalDate.plusDays(1).format(DATE_FORMATTER)

    val openDatedTasks = tasks.filter { it.completedAt == null && it.plan != null }

    val overdue = mutableListOf<Task>()
    val futureTasksByDate = mutableMapOf<String, MutableList<Task>>()

    for (task in openDatedTasks) {
        val planDay = getPlanLocalDate(task.plan, zoneId) ?: continue
        if (planDay < todayStr) {
            overdue.add(task)
        } else {
            val list = futureTasksByDate.getOrPut(planDay) { mutableListOf() }
            list.add(task)
        }
    }

    // Sort overdue tasks by plan date ascending
    overdue.sortWith(
        compareBy<Task> { getPlanLocalDate(it.plan, zoneId) ?: "" }
            .thenBy { it.priority }
    )

    // Sort date groups ascending
    val sortedDates = futureTasksByDate.keys.sorted()
    val groups = sortedDates.map { date ->
        val dateLabel = formatFriendlyDateLabel(date, todayStr, tomorrowStr)
        val groupTasks = futureTasksByDate.getValue(date).sortedBy { it.priority }
        UpcomingDayGroup(
            date = date,
            dateLabel = dateLabel,
            tasks = groupTasks
        )
    }

    return UpcomingResult(
        overdue = overdue,
        groups = groups
    )
}
