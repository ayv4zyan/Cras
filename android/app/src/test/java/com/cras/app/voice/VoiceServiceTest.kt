package com.cras.app.voice

import com.cras.app.auth.OperatorSession
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

private fun session() = OperatorSession(
    operatorId = UUID.randomUUID().toString(),
    email = "op@example.com",
    accessToken = "test-access-token",
)

private fun task(
    id: String = UUID.randomUUID().toString(),
    title: String = "Existing Task",
    description: String? = "Original description",
    priority: Int = 3,
    plan: Plan? = Plan.Floating(date = "2026-08-20", time = "10:00"),
): Task = Task(
    id = id,
    title = title,
    description = description,
    priority = priority,
    plan = plan,
    labels = emptyList(),
    parentId = null,
    completedAt = null,
    createdAt = "2026-08-20T00:00:00Z",
    updatedAt = "2026-08-20T00:00:00Z",
    version = 1,
)

class VoiceServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: SupabaseVoiceCaptureApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = SupabaseVoiceCaptureApi(
            config = PublicSupabaseConfig(server.url("/").toString(), "anon-key"),
            zoneIdProvider = { java.time.ZoneId.of("UTC") },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueSuccess(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private fun createResponseBody(): String {
        // "tomorrow"/"today" grounded server-side relative to recording start.
        return """
        {
          "transcript": "Buy milk tomorrow and call doctor at 3pm",
          "mode": "create",
          "drafts": [
            {"title": "Buy milk", "description": null, "priority": 4,
             "plan_date": "2026-08-22", "plan_time": null, "plan_type": null},
            {"title": "Call doctor", "description": null, "priority": 2,
             "plan_date": "2026-08-21", "plan_time": "15:00:00", "plan_type": null}
          ],
          "edit": null
        }
        """.trimIndent()
    }

    private fun options(
        recordingStartTime: String = "2026-08-21T10:00:00Z",
        focusedTask: Task? = null,
        existingDrafts: List<DraftTask>? = null,
    ) = VoiceCaptureRequestOptions(
        audioWav = createWavHeader(32000),
        recordingStartTime = java.time.Instant.parse(recordingStartTime),
        timezone = "UTC",
        focusedTask = focusedTask,
        existingDrafts = existingDrafts,
        effectiveDefaultTimedPlanType = TimedPlanType.INSTANT,
    )

    // ---- request contract ----

    @Test
    fun `sends multipart with bearer auth, wav file, timezone and anchors`() = runTest {
        enqueueSuccess(createResponseBody())

        api.sendVoiceCapture(session(), options())

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-access-token", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"audio\""))
        assertTrue(body.contains("filename=\"audio.wav\""))
        assertTrue(body.contains("name=\"timezone\""))
        assertTrue(body.contains("UTC"))
        // Recording start time is sent verbatim as ISO timestamp.
        assertTrue(body.contains("2026-08-21T10:00:00Z"))
    }

    @Test
    fun `relative dates stay anchored to recording start even across midnight`() = runTest {
        // Recording began just before midnight UTC; processing happens after midnight.
        enqueueSuccess(createResponseBody())

        val result = api.sendVoiceCapture(
            session(),
            options(
                recordingStartTime = "2026-08-21T23:59:30Z",
                focusedTask = null,
            ),
        )

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = recorded.body.readUtf8()
        // The pre-midnight recording start reaches the boundary unchanged.
        assertTrue(body.contains("name=\"recording_start_time\""))
        assertTrue(body.contains("2026-08-21T23:59:30Z"))

        // Server-grounded dates land in drafts untouched by any local re-grounding:
        // "tomorrow" relative to the 23:59:30Z anchor is 2026-08-22.
        assertEquals(Plan.DateOnly(date = "2026-08-22"), result.drafts[0].plan)
    }

    @Test
    fun `includes focused_task context json when editing`() = runTest {
        enqueueSuccess(
            """
            {"transcript": "Move to tomorrow at 11am", "mode": "edit", "drafts": [],
             "edit": {"title": null, "description": null, "priority": null,
                      "plan_date": "2026-08-22", "plan_time": "11:00:00",
                      "plan_type": null, "clear_plan": null}}
            """.trimIndent()
        )

        val focused = task(plan = Plan.Floating(date = "2026-08-20", time = "10:00"))
        api.sendVoiceCapture(session(), options(focusedTask = focused))

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"focused_task\""))
        assertTrue(body.contains("\"id\":\"${focused.id}\""))
        assertTrue(body.contains("\"title\":\"Existing Task\""))
        assertTrue(body.contains("\"priority\":3"))
        // Existing timed type travels to the boundary so the extractor sees it.
        assertTrue(body.contains("\"type\":\"floating\""))
    }

    @Test
    fun `includes existing_drafts summary when correcting`() = runTest {
        enqueueSuccess(createResponseBody())

        val drafts = listOf(
            DraftTask(
                id = "draft-1", title = "Buy milk", description = null, priority = 4,
                plan = Plan.DateOnly(date = "2026-08-22"),
            ),
            DraftTask(
                id = "draft-2", title = "Go to pool", description = null, priority = 4,
                plan = Plan.DateOnly(date = "2026-08-25"),
            ),
        )
        api.sendVoiceCapture(session(), options(existingDrafts = drafts))

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"existing_drafts\""))
        assertTrue(body.contains("\"target_draft_index\":0"))
        assertTrue(body.contains("\"title\":\"Go to pool\""))
        assertTrue(body.contains("\"plan_date\":\"2026-08-25\""))
        // Untimed drafts omit plan_type entirely, like JSON.stringify dropping undefined.
        assertFalse(body.contains("\"plan_type\""))
    }

    // ---- successful response mapping ----

    @Test
    fun `maps successful multi-draft create response`() = runTest {
        enqueueSuccess(createResponseBody())

        val result = api.sendVoiceCapture(session(), options())

        assertEquals("Buy milk tomorrow and call doctor at 3pm", result.transcript)
        assertEquals(VoiceCaptureMode.CREATE, result.mode)
        assertEquals(2, result.drafts.size)
        assertEquals("Buy milk", result.drafts[0].title)
        assertEquals(Plan.DateOnly(date = "2026-08-22"), result.drafts[0].plan)
        assertEquals("Call doctor", result.drafts[1].title)
        assertEquals(2, result.drafts[1].priority)
        assertEquals(Plan.Instant(at = "2026-08-21T15:00:00Z"), result.drafts[1].plan)
        assertNull(result.editProposal)
    }

    @Test
    fun `edit mode preserves existing timed type unless speech changed it`() = runTest {
        enqueueSuccess(
            """
            {"transcript": "Move to tomorrow at 11am", "mode": "edit", "drafts": [],
             "edit": {"title": null, "description": null, "priority": null,
                      "plan_date": "2026-08-22", "plan_time": "11:00:00",
                      "plan_type": null, "clear_plan": null}}
            """.trimIndent()
        )

        val focused = task(plan = Plan.Floating(date = "2026-08-20", time = "10:00"))
        val result = api.sendVoiceCapture(session(), options(focusedTask = focused))

        assertEquals(VoiceCaptureMode.EDIT, result.mode)
        assertEquals(1, result.drafts.size)
        val editDraft = result.drafts[0]
        assertNotNull(result.editProposal)
        assertEquals(focused.id, editDraft.originalTaskId)
        assertEquals("Existing Task", editDraft.title) // title unchanged
        assertEquals("Original description", editDraft.description)
        assertEquals(3, editDraft.priority)
        // Preserved floating!
        assertEquals(Plan.Floating(date = "2026-08-22", time = "11:00"), editDraft.plan)
    }

    @Test
    fun `edit mode without edit payload mirrors the focused task`() = runTest {
        enqueueSuccess(
            """{"transcript": "Hmm", "mode": "edit", "drafts": [], "edit": null}"""
        )

        val focused = task()
        val result = api.sendVoiceCapture(session(), options(focusedTask = focused))

        val editDraft = result.drafts.single()
        assertEquals(focused.id, editDraft.originalTaskId)
        assertEquals(focused.title, editDraft.title)
        assertEquals(focused.description, editDraft.description)
        assertEquals(focused.priority, editDraft.priority)
        assertEquals(focused.plan, editDraft.plan)
        assertNull(editDraft.validationError)
    }

    @Test
    fun `correction merges updates into existing drafts by index and appends new ones`() = runTest {
        enqueueSuccess(
            """
            {"transcript": "No, buy milk today, not tomorrow", "mode": "create",
             "drafts": [
               {"target_draft_index": 0, "title": "Buy milk", "description": null,
                "priority": 4, "plan_date": "2026-08-21", "plan_time": null, "plan_type": null}
             ], "edit": null}
            """.trimIndent()
        )

        val existingDrafts = listOf(
            DraftTask(
                id = "draft-1", title = "Buy milk", description = null, priority = 4,
                plan = Plan.DateOnly(date = "2026-08-22"),
            ),
            DraftTask(
                id = "draft-2", title = "Go to pool", description = null, priority = 4,
                plan = Plan.DateOnly(date = "2026-08-25"),
            ),
        )
        val result = api.sendVoiceCapture(
            session(),
            options(existingDrafts = existingDrafts),
        )

        assertEquals(VoiceCaptureMode.CREATE, result.mode)
        assertEquals(2, result.drafts.size)
        assertEquals(Plan.DateOnly(date = "2026-08-21"), result.drafts[0].plan) // updated!
        assertEquals("Go to pool", result.drafts[1].title)
        assertEquals(Plan.DateOnly(date = "2026-08-25"), result.drafts[1].plan) // untouched!
    }

    @Test
    fun `clear_plan removes the plan from an edit draft`() = runTest {
        enqueueSuccess(
            """
            {"transcript": "Remove the date", "mode": "edit", "drafts": [],
             "edit": {"title": null, "description": null, "priority": null,
                      "plan_date": null, "plan_time": null, "plan_type": null,
                      "clear_plan": true}}
            """.trimIndent()
        )

        val result = api.sendVoiceCapture(session(), options(focusedTask = task()))

        assertNull(result.drafts.single().plan)
    }

    @Test
    fun `explicit instant type without clock time in edit flags validation error`() = runTest {
        enqueueSuccess(
            """
            {"transcript": "Make it instant tomorrow", "mode": "edit", "drafts": [],
             "edit": {"title": null, "description": null, "priority": null,
                      "plan_date": "2026-08-22", "plan_time": null,
                      "plan_type": "instant", "clear_plan": null}}
            """.trimIndent()
        )

        val result = api.sendVoiceCapture(session(), options(focusedTask = task()))

        val editDraft = result.drafts.single()
        assertEquals(
            "An explicit Instant or Floating plan requires a clock time.",
            editDraft.validationError,
        )
        assertEquals(Plan.DateOnly(date = "2026-08-22"), editDraft.plan)
    }

    // ---- error mapping ----

    @Test
    fun `maps 429 allowance errors with earliest retry metadata`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"error": "Rate limit exceeded: maximum 3 requests per minute.",
                     "code": "rate_limit_minute",
                     "earliest_retry_at": "2026-08-21T10:01:00Z",
                     "retry_after_seconds": 45}
                    """.trimIndent()
                )
        )

        val error = runCatching { api.sendVoiceCapture(session(), options()) }
            .exceptionOrNull()

        assertTrue(error is VoiceError)
        error as VoiceError
        assertEquals(429, error.status)
        assertEquals("rate_limit_minute", error.code)
        assertEquals("2026-08-21T10:01:00Z", error.earliestRetryAt)
        assertEquals(45, error.retryAfterSeconds)
        assertFalse(error.isNetworkError)
    }

    @Test
    fun `maps 503 circuit breaker errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"error": "Voice capture is temporarily unavailable. Please try again later.",
                     "code": "circuit_breaker_daily"}
                    """.trimIndent()
                )
        )

        val error = runCatching { api.sendVoiceCapture(session(), options()) }
            .exceptionOrNull() as VoiceError

        assertEquals(503, error.status)
        assertEquals("circuit_breaker_daily", error.code)
    }

    @Test
    fun `maps 502 provider errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(502)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error": "Voice processing failed. Please try again.", "code": "provider_error"}""")
        )

        val error = runCatching { api.sendVoiceCapture(session(), options()) }
            .exceptionOrNull() as VoiceError

        assertEquals(502, error.status)
        assertEquals("provider_error", error.code)
        assertEquals("Voice processing failed. Please try again.", error.message)
    }

    @Test
    fun `flags network failures so the recording is preserved`() = runTest {
        server.shutdown()
        val deadApi = SupabaseVoiceCaptureApi(
            config = PublicSupabaseConfig("http://127.0.0.1:1/", "anon-key"),
        )

        val error = runCatching {
            deadApi.sendVoiceCapture(session(), options())
        }.exceptionOrNull()

        assertTrue(error is VoiceError)
        error as VoiceError
        assertEquals(0, error.status)
        assertEquals("network_error", error.code)
        assertTrue(error.isNetworkError)
    }
}
