package com.cras.app.quickaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the encode/decode round-trip of [TodayGlanceRow] via the
 * [encodeTodayRows] / [decodeTodayRows] functions that back the Glance
 * Preferences state.
 */
class TodayGlanceWidgetTest {

    @Test
    fun `encode and decode round-trips an empty list`() {
        val json = encodeTodayRows(emptyList())
        val result = decodeTodayRows(json)
        assertEquals(emptyList<TodayGlanceRow>(), result)
    }

    @Test
    fun `encode and decode round-trips a list of rows`() {
        val rows = listOf(
            TodayGlanceRow(
                taskId = "550e8400-e29b-41d4-a716-446655440001",
                title = "Pick up groceries",
                isSubtask = false
            ),
            TodayGlanceRow(
                taskId = "550e8400-e29b-41d4-a716-446655440002",
                title = "Buy milk",
                isSubtask = true
            )
        )
        val json = encodeTodayRows(rows)
        val result = decodeTodayRows(json)
        assertEquals(rows, result)
    }

    @Test
    fun `decode returns empty list for malformed json`() {
        val result = decodeTodayRows("not-valid-json-{{}")
        assertEquals(emptyList<TodayGlanceRow>(), result)
    }

    @Test
    fun `decode returns empty list for empty string`() {
        val result = decodeTodayRows("")
        assertEquals(emptyList<TodayGlanceRow>(), result)
    }

    @Test
    fun `isSubtask is preserved correctly for false`() {
        val rows = listOf(
            TodayGlanceRow(
                taskId = "550e8400-e29b-41d4-a716-446655440001",
                title = "Parent task",
                isSubtask = false
            )
        )
        val result = decodeTodayRows(encodeTodayRows(rows))
        assertEquals(false, result[0].isSubtask)
    }

    @Test
    fun `isSubtask is preserved correctly for true`() {
        val rows = listOf(
            TodayGlanceRow(
                taskId = "550e8400-e29b-41d4-a716-446655440001",
                title = "Child task",
                isSubtask = true
            )
        )
        val result = decodeTodayRows(encodeTodayRows(rows))
        assertEquals(true, result[0].isSubtask)
    }
}
