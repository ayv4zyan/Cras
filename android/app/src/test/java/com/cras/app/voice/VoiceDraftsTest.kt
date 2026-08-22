package com.cras.app.voice

import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

private val TEST_ZONE = java.time.ZoneId.of("UTC")

private fun fixedIds(vararg ids: String): () -> String {
    val queue = ids.toMutableList()
    return { if (queue.isEmpty()) UUID.randomUUID().toString() else queue.removeAt(0) }
}

class VoiceDraftsTest {

    // ---- createDraftTaskFromExtracted ----

    @Test
    fun `creates a Date-only Draft when only plan_date is present`() {
        val payload = ExtractedDraftPayload(
            title = "Buy groceries",
            description = "Apples, bananas, milk",
            priority = 2,
            plan_date = "2026-08-25",
            plan_time = null,
            plan_type = null,
        )

        val draft = createDraftTaskFromExtracted(payload, TimedPlanType.INSTANT, zoneId = TEST_ZONE)

        assertEquals("Buy groceries", draft.title)
        assertEquals("Apples, bananas, milk", draft.description)
        assertEquals(2, draft.priority)
        assertEquals(Plan.DateOnly(date = "2026-08-25"), draft.plan)
        assertNull(draft.validationError)
    }

    @Test
    fun `applies effective default Instant when plan has date and time but unstated type`() {
        val payload = ExtractedDraftPayload(
            title = "Team standup",
            plan_date = "2026-08-25",
            plan_time = "09:30:00",
            plan_type = null,
        )

        val draft = createDraftTaskFromExtracted(payload, TimedPlanType.INSTANT, zoneId = TEST_ZONE)

        assertEquals(Plan.Instant(at = "2026-08-25T09:30:00Z"), draft.plan)
        assertNull(draft.validationError)
    }

    @Test
    fun `applies effective default Floating when plan has date and time and default is floating`() {
        val payload = ExtractedDraftPayload(
            title = "Team standup",
            plan_date = "2026-08-25",
            plan_time = "09:30:00",
            plan_type = null,
        )

        val draft = createDraftTaskFromExtracted(payload, TimedPlanType.FLOATING, zoneId = TEST_ZONE)

        assertEquals(Plan.Floating(date = "2026-08-25", time = "09:30"), draft.plan)
        assertNull(draft.validationError)
    }

    @Test
    fun `honors explicit spoken Instant type overriding Floating default`() {
        val payload = ExtractedDraftPayload(
            title = "Flight departure",
            plan_date = "2026-08-25",
            plan_time = "14:00:00",
            plan_type = "instant",
        )

        val draft = createDraftTaskFromExtracted(payload, TimedPlanType.FLOATING, zoneId = TEST_ZONE)

        assertEquals(Plan.Instant(at = "2026-08-25T14:00:00Z"), draft.plan)
    }

    @Test
    fun `flags validation error when speech explicitly requests Instant or Floating without a clock time`() {
        val payload = ExtractedDraftPayload(
            title = "Invalid explicit floating task",
            plan_date = "2026-08-25",
            plan_time = null,
            plan_type = "floating",
        )

        val draft = createDraftTaskFromExtracted(payload, TimedPlanType.INSTANT, zoneId = TEST_ZONE)

        assertEquals(
            "An explicit Instant or Floating plan requires a clock time. Please provide a time or change to Date-only.",
            draft.validationError,
        )
        assertEquals(Plan.DateOnly(date = "2026-08-25"), draft.plan)
    }

    @Test
    fun `flags validation error when explicit type arrives without a date`() {
        val payload = ExtractedDraftPayload(
            title = "No date at all",
            plan_date = null,
            plan_time = null,
            plan_type = "instant",
        )

        val draft = createDraftTaskFromExtracted(payload, TimedPlanType.INSTANT, zoneId = TEST_ZONE)

        assertEquals(
            "An explicit Instant or Floating plan requires a date and clock time.",
            draft.validationError,
        )
        assertNull(draft.plan)
    }

    @Test
    fun `falls back to Untitled task title, trims description and clamps priority`() {
        val payload = ExtractedDraftPayload(
            title = "",
            description = "   ",
            priority = 9,
            plan_date = null,
            plan_time = null,
            plan_type = null,
        )

        val draft = createDraftTaskFromExtracted(payload, TimedPlanType.INSTANT, zoneId = TEST_ZONE)

        assertEquals("Untitled task", draft.title)
        assertNull(draft.description)
        assertEquals(4, draft.priority)
        assertNull(draft.originalTaskId)
        assertNull(draft.parentId)
        assertEquals(emptyList<String>(), draft.labels)
    }

    // ---- switchDraftTimedPlanType ----

    @Test
    fun `switching Instant to Floating and back preserves displayed calendar date and clock time`() {
        val initialPayload = ExtractedDraftPayload(
            title = "Preserve displayed time test",
            plan_date = "2026-08-25",
            plan_time = "19:00:00",
            plan_type = "instant",
        )

        val draftInstant = createDraftTaskFromExtracted(
            initialPayload,
            TimedPlanType.INSTANT,
            zoneId = TEST_ZONE,
        )
        assertEquals(Plan.Instant::class, draftInstant.plan!!::class)
        val displayedDateBefore = formatPlanDate(draftInstant.plan, TEST_ZONE)
        val displayedTimeBefore = formatPlanTime(draftInstant.plan, TEST_ZONE)

        val draftFloating = switchDraftTimedPlanType(
            draftInstant,
            TimedPlanType.FLOATING,
            TimedPlanType.INSTANT,
            TEST_ZONE,
        )
        assertEquals(Plan.Floating(date = "2026-08-25", time = "19:00"), draftFloating.plan)
        assertEquals(displayedDateBefore, formatPlanDate(draftFloating.plan, TEST_ZONE))
        assertEquals(displayedTimeBefore, formatPlanTime(draftFloating.plan, TEST_ZONE))

        val draftInstantAgain = switchDraftTimedPlanType(
            draftFloating,
            TimedPlanType.INSTANT,
            TimedPlanType.INSTANT,
            TEST_ZONE,
        )
        assertEquals(Plan.Instant(at = "2026-08-25T19:00:00Z"), draftInstantAgain.plan)
        assertEquals(displayedDateBefore, formatPlanDate(draftInstantAgain.plan, TEST_ZONE))
        assertEquals(displayedTimeBefore, formatPlanTime(draftInstantAgain.plan, TEST_ZONE))
        assertNull(draftInstantAgain.validationError)
    }

    @Test
    fun `switching keeps the displayed wall-clock face across timezone interpretation`() {
        // A 23:45 floating plan viewed in Tokyo switches to Instant and keeps 23:45 local face.
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        val draft = DraftTask(
            id = "draft-1",
            title = "Late call",
            description = null,
            priority = 4,
            plan = Plan.Floating(date = "2026-08-25", time = "23:45"),
        )

        val switched = switchDraftTimedPlanType(draft, TimedPlanType.INSTANT, TimedPlanType.INSTANT, tokyo)

        val instantPlan = switched.plan as Plan.Instant
        assertEquals("2026-08-25T14:45:00Z", instantPlan.at)
        assertEquals("2026-08-25", formatPlanDate(switched.plan, tokyo))
        assertEquals("23:45", formatPlanTime(switched.plan, tokyo))
    }

    @Test
    fun `switching a draft without a plan returns it unchanged`() {
        val draft = DraftTask(
            id = "draft-2",
            title = "No plan",
            description = null,
            priority = 4,
            plan = null,
        )

        val result = switchDraftTimedPlanType(draft, TimedPlanType.FLOATING, TimedPlanType.INSTANT, TEST_ZONE)

        assertEquals(draft, result)
    }

    @Test
    fun `switching an untimed dated draft flags a validation error`() {
        val draft = DraftTask(
            id = "draft-3",
            title = "Date only",
            description = null,
            priority = 4,
            plan = Plan.DateOnly(date = "2026-08-25"),
        )

        val result = switchDraftTimedPlanType(draft, TimedPlanType.FLOATING, TimedPlanType.INSTANT, TEST_ZONE)

        assertEquals(
            "Cannot switch plan type on an untimed task without a clock time.",
            result.validationError,
        )
        assertEquals(Plan.DateOnly(date = "2026-08-25"), result.plan)
    }
}
