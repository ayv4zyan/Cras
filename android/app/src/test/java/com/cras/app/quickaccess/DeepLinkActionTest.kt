package com.cras.app.quickaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [parseDeepLinkUri] — the pure-JVM seam for deep-link parsing. No
 * Android framework dependency; tests run as standard JVM unit tests.
 */
class DeepLinkActionTest {

    // Helper: build segments list the same way Uri.pathSegments would
    private fun segments(vararg parts: String) = parts.toList()

    @Test
    fun `returns null for null scheme`() {
        assertNull(parseDeepLinkUri(null, DEEP_LINK_HOST, segments("today")))
    }

    @Test
    fun `returns null for null host`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, null, segments("today")))
    }

    @Test
    fun `returns null for non-cras scheme`() {
        assertNull(parseDeepLinkUri("https", DEEP_LINK_HOST, segments("today")))
    }

    @Test
    fun `returns null for wrong host`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, "navigate", segments("today")))
    }

    @Test
    fun `returns null for empty path`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, emptyList()))
    }

    @Test
    fun `parses OpenToday`() {
        assertEquals(
            DeepLinkAction.OpenToday,
            parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("today"))
        )
    }

    @Test
    fun `parses OpenUpcoming`() {
        assertEquals(
            DeepLinkAction.OpenUpcoming,
            parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("upcoming"))
        )
    }

    @Test
    fun `parses OpenVoice`() {
        assertEquals(
            DeepLinkAction.OpenVoice,
            parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("voice"))
        )
    }

    @Test
    fun `parses OpenCreate`() {
        assertEquals(
            DeepLinkAction.OpenCreate,
            parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("create"))
        )
    }

    @Test
    fun `parses OpenTask with id`() {
        val taskId = "550e8400-e29b-41d4-a716-446655440001"
        assertEquals(
            DeepLinkAction.OpenTask(taskId),
            parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("task", taskId))
        )
    }

    @Test
    fun `returns null for OpenTask without id segment`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("task")))
    }

    @Test
    fun `returns null for OpenTask with blank id`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("task", "  ")))
    }

    @Test
    fun `parses CompleteTask with id`() {
        val taskId = "550e8400-e29b-41d4-a716-446655440001"
        assertEquals(
            DeepLinkAction.CompleteTask(taskId),
            parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST_COMPLETE, segments("task", taskId))
        )
    }

    @Test
    fun `returns null for CompleteTask without id segment`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST_COMPLETE, segments("task")))
    }

    @Test
    fun `returns null for CompleteTask with blank id`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST_COMPLETE, segments("task", "   ")))
    }

    @Test
    fun `returns null for unrecognised complete path`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST_COMPLETE, segments("label", "123")))
    }

    @Test
    fun `returns null for unrecognised path`() {
        assertNull(parseDeepLinkUri(DEEP_LINK_SCHEME, DEEP_LINK_HOST, segments("settings")))
    }
}
