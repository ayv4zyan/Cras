package com.cras.app.quickaccess

import com.cras.app.models.Plan
import com.cras.app.models.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Tests for [buildTodayGlanceRows] at its public interface boundary.
 */
class TodayGlanceRowsTest {

    // Fixed "now" = 2026-08-24T12:00:00Z, UTC zone → today = "2026-08-24"
    private val now = Instant.parse("2026-08-24T12:00:00Z")
    private val zone = ZoneOffset.UTC

    private fun task(
        title: String,
        plan: Plan?,
        parentId: String? = null,
        completedAt: String? = null,
        priority: Int = 4,
        id: String = UUID.randomUUID().toString()
    ) = Task(
        id = id,
        title = title,
        description = null,
        priority = priority,
        plan = plan,
        labels = emptyList(),
        parentId = parentId,
        completedAt = completedAt,
        createdAt = "2026-08-01T00:00:00Z",
        updatedAt = "2026-08-01T00:00:00Z",
        version = 1
    )

    @Test
    fun `returns empty list when no tasks`() {
        assertTrue(buildTodayGlanceRows(emptyList(), now, zone).isEmpty())
    }

    @Test
    fun `excludes tasks without a plan`() {
        val t = task("Inbox task", plan = null)
        assertTrue(buildTodayGlanceRows(listOf(t), now, zone).isEmpty())
    }

    @Test
    fun `excludes completed tasks`() {
        val t = task("Done", plan = Plan.DateOnly("2026-08-24"), completedAt = "2026-08-24T10:00:00Z")
        assertTrue(buildTodayGlanceRows(listOf(t), now, zone).isEmpty())
    }

    @Test
    fun `excludes future tasks`() {
        val t = task("Tomorrow", plan = Plan.DateOnly("2026-08-25"))
        assertTrue(buildTodayGlanceRows(listOf(t), now, zone).isEmpty())
    }

    @Test
    fun `includes task with today date`() {
        val t = task("Today task", plan = Plan.DateOnly("2026-08-24"))
        val rows = buildTodayGlanceRows(listOf(t), now, zone)
        assertEquals(1, rows.size)
        assertEquals("Today task", rows[0].title)
        assertFalse(rows[0].isSubtask)
    }

    @Test
    fun `includes overdue task (date before today)`() {
        val t = task("Overdue", plan = Plan.DateOnly("2026-08-20"))
        val rows = buildTodayGlanceRows(listOf(t), now, zone)
        assertEquals(1, rows.size)
        assertEquals("Overdue", rows[0].title)
    }

    @Test
    fun `includes dated Subtask in scope`() {
        val parentId = UUID.randomUUID().toString()
        val subtask = task("Subtask", plan = Plan.DateOnly("2026-08-24"), parentId = parentId)
        val rows = buildTodayGlanceRows(listOf(subtask), now, zone)
        assertEquals(1, rows.size)
        assertTrue(rows[0].isSubtask)
        assertEquals(subtask.id, rows[0].taskId)
    }

    @Test
    fun `sorts by plan date ascending then priority ascending`() {
        val older = task("Older P2", plan = Plan.DateOnly("2026-08-22"), priority = 2)
        val todayP1 = task("Today P1", plan = Plan.DateOnly("2026-08-24"), priority = 1)
        val todayP3 = task("Today P3", plan = Plan.DateOnly("2026-08-24"), priority = 3)

        val rows = buildTodayGlanceRows(listOf(todayP3, todayP1, older), now, zone)

        assertEquals(3, rows.size)
        assertEquals("Older P2", rows[0].title)
        assertEquals("Today P1", rows[1].title)
        assertEquals("Today P3", rows[2].title)
    }

    @Test
    fun `includes top-level and Subtask independently without deduplication`() {
        val parentId = UUID.randomUUID().toString()
        val parent = task("Parent", plan = Plan.DateOnly("2026-08-24"), id = parentId)
        val subtask = task("Child", plan = Plan.DateOnly("2026-08-24"), parentId = parentId)
        val rows = buildTodayGlanceRows(listOf(parent, subtask), now, zone)
        assertEquals(2, rows.size)
    }

    @Test
    fun `includes Instant plan whose local time falls on today`() {
        // 2026-08-24T10:00:00Z in UTC → today
        val t = task("Instant today", plan = Plan.Instant("2026-08-24T10:00:00Z"))
        val rows = buildTodayGlanceRows(listOf(t), now, zone)
        assertEquals(1, rows.size)
    }

    @Test
    fun `excludes Instant plan that falls tomorrow in the zone`() {
        // 2026-08-25T01:00:00Z in UTC → tomorrow
        val t = task("Instant tomorrow", plan = Plan.Instant("2026-08-25T01:00:00Z"))
        val rows = buildTodayGlanceRows(listOf(t), now, zone)
        assertTrue(rows.isEmpty())
    }
}
