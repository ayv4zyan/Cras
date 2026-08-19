package com.cras.app.domain

import com.cras.app.models.Plan
import com.cras.app.models.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class TemporalServiceTest {

    private val tokyoZone = ZoneId.of("Asia/Tokyo") // UTC+9
    private val nyZone = ZoneId.of("America/New_York") // UTC-4 in summer
    private val utcZone = ZoneOffset.UTC

    private fun createTask(
        title: String,
        plan: Plan? = null,
        priority: Int = 4,
        completedAt: String? = null
    ): Task = Task(
        id = UUID.randomUUID().toString(),
        title = title,
        description = null,
        priority = priority,
        plan = plan,
        labels = emptyList(),
        parentId = null,
        completedAt = completedAt,
        createdAt = "2026-08-19T00:00:00Z",
        updatedAt = "2026-08-19T00:00:00Z",
        version = 1
    )

    @Test
    fun `getDeviceLocalDate returns local calendar date YYYY-MM-DD for given instant and timezone`() {
        // 2026-08-19T20:00:00Z is 2026-08-19 in UTC, 2026-08-19 in New York (16:00), but 2026-08-20 in Tokyo (05:00 next day)
        val instant = Instant.parse("2026-08-19T20:00:00Z")

        assertEquals("2026-08-19", getDeviceLocalDate(instant, utcZone))
        assertEquals("2026-08-19", getDeviceLocalDate(instant, nyZone))
        assertEquals("2026-08-20", getDeviceLocalDate(instant, tokyoZone))
    }

    @Test
    fun `getPlanLocalDate extracts local calendar date for all plan variants`() {
        // Date-only
        val dateOnly = Plan.DateOnly("2026-08-19")
        assertEquals("2026-08-19", getPlanLocalDate(dateOnly, utcZone))
        assertEquals("2026-08-19", getPlanLocalDate(dateOnly, tokyoZone))

        // Floating: identical date across all timezones
        val floating = Plan.Floating("2026-08-19", "09:30")
        assertEquals("2026-08-19", getPlanLocalDate(floating, utcZone))
        assertEquals("2026-08-19", getPlanLocalDate(floating, tokyoZone))

        // Instant: derived from viewing device local timezone
        val instantPlan = Plan.Instant("2026-08-19T22:00:00Z")
        assertEquals("2026-08-19", getPlanLocalDate(instantPlan, utcZone))
        assertEquals("2026-08-19", getPlanLocalDate(instantPlan, nyZone))
        assertEquals("2026-08-20", getPlanLocalDate(instantPlan, tokyoZone))

        // Null plan
        assertNull(getPlanLocalDate(null, utcZone))
    }

    @Test
    fun `createPlanFromInputs creates contract-compliant Date-only, Floating, and Instant plans`() {
        // 1. Omitted date -> null
        assertNull(createPlanFromInputs(CreatePlanParams(date = null)))
        assertNull(createPlanFromInputs(CreatePlanParams(date = "   ")))

        // 2. Date provided, time omitted -> DateOnly (no timed type, no fake midnight)
        val dateOnly = createPlanFromInputs(CreatePlanParams(date = "2026-08-19"))
        assertTrue("Expected DateOnly plan", dateOnly is Plan.DateOnly)
        assertEquals("2026-08-19", (dateOnly as Plan.DateOnly).date)

        // 3. Date & time provided with explicit Floating
        val floatingPlan = createPlanFromInputs(
            CreatePlanParams(
                date = "2026-08-19",
                time = "14:30",
                type = TimedPlanType.FLOATING
            )
        )
        assertTrue("Expected Floating plan", floatingPlan is Plan.Floating)
        val floating = floatingPlan as Plan.Floating
        assertEquals("2026-08-19", floating.date)
        assertEquals("14:30", floating.time)

        // 4. Date & time provided with explicit Instant in specific timezone
        val instantPlan = createPlanFromInputs(
            CreatePlanParams(
                date = "2026-08-19",
                time = "14:30",
                type = TimedPlanType.INSTANT,
                zoneId = nyZone // UTC-4 in August -> 14:30 EDT = 18:30 UTC
            )
        )
        assertTrue("Expected Instant plan", instantPlan is Plan.Instant)
        val instant = instantPlan as Plan.Instant
        assertEquals("2026-08-19T18:30:00Z", instant.at)

        // 5. Inherits effective default when type is null
        val defaultFloatingPlan = createPlanFromInputs(
            CreatePlanParams(
                date = "2026-08-19",
                time = "09:00",
                type = null,
                effectiveDefault = TimedPlanType.FLOATING
            )
        )
        assertTrue(defaultFloatingPlan is Plan.Floating)

        val defaultInstantPlan = createPlanFromInputs(
            CreatePlanParams(
                date = "2026-08-19",
                time = "09:00",
                type = null,
                effectiveDefault = TimedPlanType.INSTANT,
                zoneId = utcZone
            )
        )
        assertTrue(defaultInstantPlan is Plan.Instant)
        assertEquals("2026-08-19T09:00:00Z", (defaultInstantPlan as Plan.Instant).at)
    }

    @Test
    fun `formatFriendlyDateLabel formats Today, Tomorrow, Yesterday and dates correctly`() {
        assertEquals("Today", formatFriendlyDateLabel("2026-08-19", "2026-08-19", "2026-08-20", "2026-08-18"))
        assertEquals("Tomorrow", formatFriendlyDateLabel("2026-08-20", "2026-08-19", "2026-08-20", "2026-08-18"))
        assertEquals("Yesterday", formatFriendlyDateLabel("2026-08-18", "2026-08-19", "2026-08-20", "2026-08-18"))
        assertEquals("Fri, Aug 21", formatFriendlyDateLabel("2026-08-21", "2026-08-19", "2026-08-20", "2026-08-18"))
    }

    @Test
    fun `formatPlanDisplay formats plan display info with date, time, type and overdue status`() {
        val now = Instant.parse("2026-08-19T12:00:00Z")

        // Overdue date-only task
        val overduePlan = Plan.DateOnly("2026-08-18")
        val overdueDisplay = formatPlanDisplay(overduePlan, now, utcZone)
        assertNotNull(overdueDisplay)
        assertEquals("Yesterday", overdueDisplay!!.dateLabel)
        assertNull(overdueDisplay.timeLabel)
        assertNull(overdueDisplay.typeLabel)
        assertTrue(overdueDisplay.isOverdue)

        // Today floating task
        val todayFloating = Plan.Floating("2026-08-19", "15:00")
        val todayDisplay = formatPlanDisplay(todayFloating, now, utcZone)
        assertNotNull(todayDisplay)
        assertEquals("Today", todayDisplay!!.dateLabel)
        assertEquals("15:00", todayDisplay.timeLabel)
        assertEquals("Floating", todayDisplay.typeLabel)
        assertFalse(todayDisplay.isOverdue)

        // Tomorrow instant task in NY timezone
        // 2026-08-20T19:00:00Z is 15:00 EDT in New York
        val instantPlan = Plan.Instant("2026-08-20T19:00:00Z")
        val instantDisplay = formatPlanDisplay(instantPlan, now, nyZone)
        assertNotNull(instantDisplay)
        assertEquals("Tomorrow", instantDisplay!!.dateLabel)
        assertEquals("15:00", instantDisplay.timeLabel)
        assertEquals("Instant", instantDisplay.typeLabel)
        assertFalse(instantDisplay.isOverdue)
    }

    @Test
    fun `filterTodayTasks includes open tasks scheduled today or earlier and excludes completed or undated tasks`() {
        val now = Instant.parse("2026-08-19T12:00:00Z") // 2026-08-19 in UTC

        val overdueTask = createTask("Overdue P2", Plan.DateOnly("2026-08-18"), priority = 2)
        val todayP1Task = createTask("Today P1", Plan.Floating("2026-08-19", "10:00"), priority = 1)
        val todayP3Task = createTask("Today P3", Plan.Instant("2026-08-19T14:00:00Z"), priority = 3)
        val tomorrowTask = createTask("Tomorrow", Plan.DateOnly("2026-08-20"), priority = 1)
        val completedTodayTask = createTask("Completed Today", Plan.DateOnly("2026-08-19"), completedAt = "2026-08-19T10:00:00Z")
        val inboxTask = createTask("Inbox task", plan = null)

        val allTasks = listOf(tomorrowTask, inboxTask, todayP3Task, overdueTask, completedTodayTask, todayP1Task)

        val todayFiltered = filterTodayTasks(allTasks, now, utcZone)

        assertEquals(3, todayFiltered.size)
        // Ordered by date ascending, then priority
        assertEquals("Overdue P2", todayFiltered[0].title)
        assertEquals("Today P1", todayFiltered[1].title)
        assertEquals("Today P3", todayFiltered[2].title)
    }

    @Test
    fun `filterUpcomingTasks groups open future dated tasks and separates overdue strip`() {
        val now = Instant.parse("2026-08-19T12:00:00Z") // 2026-08-19

        val overdue1 = createTask("Overdue older", Plan.DateOnly("2026-08-17"), priority = 3)
        val overdue2 = createTask("Overdue yesterday", Plan.DateOnly("2026-08-18"), priority = 1)
        val todayTask = createTask("Today task", Plan.Floating("2026-08-19", "10:00"), priority = 2)
        val tomorrowP2 = createTask("Tomorrow P2", Plan.DateOnly("2026-08-20"), priority = 2)
        val tomorrowP1 = createTask("Tomorrow P1", Plan.DateOnly("2026-08-20"), priority = 1)
        val nextWeek = createTask("Next week", Plan.DateOnly("2026-08-26"), priority = 4)
        val completedTask = createTask("Completed future", Plan.DateOnly("2026-08-21"), completedAt = "2026-08-19T00:00:00Z")
        val undatedTask = createTask("Undated inbox", plan = null)

        val allTasks = listOf(nextWeek, undatedTask, tomorrowP2, overdue2, completedTask, todayTask, overdue1, tomorrowP1)

        val result = filterUpcomingTasks(allTasks, now, utcZone)

        // Overdue strip contains tasks before today sorted date asc
        assertEquals(2, result.overdue.size)
        assertEquals("Overdue older", result.overdue[0].title)
        assertEquals("Overdue yesterday", result.overdue[1].title)

        // Day groups: 2026-08-19, 2026-08-20, 2026-08-26
        assertEquals(3, result.groups.size)

        val todayGroup = result.groups[0]
        assertEquals("2026-08-19", todayGroup.date)
        assertEquals("Today", todayGroup.dateLabel)
        assertEquals(1, todayGroup.tasks.size)
        assertEquals("Today task", todayGroup.tasks[0].title)

        val tomorrowGroup = result.groups[1]
        assertEquals("2026-08-20", tomorrowGroup.date)
        assertEquals("Tomorrow", tomorrowGroup.dateLabel)
        assertEquals(2, tomorrowGroup.tasks.size)
        assertEquals("Tomorrow P1", tomorrowGroup.tasks[0].title)
        assertEquals("Tomorrow P2", tomorrowGroup.tasks[1].title)

        val nextWeekGroup = result.groups[2]
        assertEquals("2026-08-26", nextWeekGroup.date)
        assertEquals(1, nextWeekGroup.tasks.size)
        assertEquals("Next week", nextWeekGroup.tasks[0].title)
    }
}
