package com.cras.app.voice

import com.cras.app.domain.CreatePlanParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.createPlanFromInputs
import com.cras.app.domain.getPlanLocalDate
import com.cras.app.models.Plan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A proposed Task, or a proposed change to a Task, shown after Voice capture.
 * Not saved until the Operator accepts.
 */
data class DraftTask(
    val id: String,
    val title: String,
    val description: String?,
    val priority: Int,
    val plan: Plan?,
    val labels: List<String> = emptyList(),
    val parentId: String? = null,
    val originalTaskId: String? = null,
    val validationError: String? = null,
)

/** Draft payload extracted by the Voice boundary (wire contract of voice-capture). */
data class ExtractedDraftPayload(
    val title: String? = null,
    val description: String? = null,
    val priority: Int? = null,
    val plan_date: String? = null,
    val plan_time: String? = null,
    val plan_type: String? = null,
    val target_draft_index: Int? = null,
)

/** Edit payload extracted by the Voice boundary (wire contract of voice-capture). */
data class ExtractedEditPayload(
    val title: String? = null,
    val description: String? = null,
    val priority: Int? = null,
    val plan_date: String? = null,
    val plan_time: String? = null,
    val plan_type: String? = null,
    val clear_plan: Boolean? = null,
)

enum class VoiceCaptureMode { CREATE, EDIT;

    companion object {
        fun fromValue(value: String?): VoiceCaptureMode =
            if (value == "edit") EDIT else CREATE
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

fun defaultDraftId(): String = UUID.randomUUID().toString()

/**
 * Builds a DraftTask from an extracted draft payload, mirroring web
 * createDraftTaskFromExtracted including validation messages and the timed-type
 * switch between explicit speech and effective Deployment default.
 */
fun createDraftTaskFromExtracted(
    payload: ExtractedDraftPayload,
    effectiveDefault: TimedPlanType,
    originalTaskId: String? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
    newId: () -> String = ::defaultDraftId,
): DraftTask {
    val rawTitle = payload.title
    val title = (if (rawTitle.isNullOrEmpty()) "Untitled task" else rawTitle).trim()
        .ifEmpty { "Untitled task" }
    val description = payload.description?.trim()?.ifEmpty { null }
    val priority = payload.priority?.takeIf { it in 1..4 } ?: 4

    var plan: Plan? = null
    var validationError: String? = null

    val date = payload.plan_date?.trim()?.ifEmpty { null }
    val time = payload.plan_time?.trim()?.ifEmpty { null }
    val explicitType = TimedPlanType.fromValue(payload.plan_type)

    if (date != null) {
        if (time != null) {
            // Timed plan: explicit type overrides effective default
            val chosenType = explicitType ?: effectiveDefault
            plan = createPlanFromInputs(
                CreatePlanParams(
                    date = date,
                    time = time,
                    type = chosenType,
                    effectiveDefault = effectiveDefault,
                    zoneId = zoneId,
                )
            )
        } else {
            // Untimed date (Date-only)
            if (explicitType != null) {
                // An explicit Instant/Floating instruction without a clock time is invalid
                validationError =
                    "An explicit Instant or Floating plan requires a clock time. Please provide a time or change to Date-only."
            }
            plan = Plan.DateOnly(date = date)
        }
    } else if (explicitType != null) {
        validationError =
            "An explicit Instant or Floating plan requires a date and clock time."
    }

    return DraftTask(
        id = newId(),
        title = title,
        description = description,
        priority = priority,
        plan = plan,
        labels = emptyList(),
        parentId = null,
        originalTaskId = originalTaskId,
        validationError = validationError,
    )
}

/**
 * Formats a plan's calendar date as YYYY-MM-DD (device-local for Instant).
 */
fun formatPlanDate(plan: Plan?, zoneId: ZoneId = ZoneId.systemDefault()): String? =
    getPlanLocalDate(plan, zoneId)

/**
 * Formats a plan's local time as HH:mm (device-local for Instant), or null when untimed.
 */
fun formatPlanTime(plan: Plan?, zoneId: ZoneId = ZoneId.systemDefault()): String? {
    return when (plan) {
        is Plan.Floating -> plan.time.take(5)
        is Plan.Instant -> try {
            Instant.parse(plan.at).atZone(zoneId).toLocalDateTime().format(TIME_FORMATTER)
        } catch (_: Exception) {
            null
        }
        else -> null
    }
}

fun isTimedPlan(plan: Plan?): Boolean = plan is Plan.Floating || plan is Plan.Instant

fun timedPlanTypeOf(plan: Plan?): TimedPlanType? = when (plan) {
    is Plan.Instant -> TimedPlanType.INSTANT
    is Plan.Floating -> TimedPlanType.FLOATING
    else -> null
}

/**
 * Switches a DraftTask's plan between Instant and Floating.
 * Preserves the displayed calendar date and clock time and reinterprets their meaning.
 */
fun switchDraftTimedPlanType(
    draft: DraftTask,
    newType: TimedPlanType,
    effectiveDefault: TimedPlanType = TimedPlanType.INSTANT,
    zoneId: ZoneId = ZoneId.systemDefault(),
): DraftTask {
    val currentPlan = draft.plan ?: return draft

    val currentDate = formatPlanDate(currentPlan, zoneId)
    val currentTime = formatPlanTime(currentPlan, zoneId)

    if (currentDate == null || currentTime == null) {
        return draft.copy(
            validationError =
            "Cannot switch plan type on an untimed task without a clock time."
        )
    }

    val updatedPlan = createPlanFromInputs(
        CreatePlanParams(
            date = currentDate,
            time = currentTime,
            type = newType,
            effectiveDefault = effectiveDefault,
            zoneId = zoneId,
        )
    )

    return draft.copy(plan = updatedPlan, validationError = null)
}
