package com.cras.app

import com.cras.app.auth.InMemorySessionStore
import com.cras.app.auth.OperatorSession
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.PublicSupabaseConfig
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.SupabaseTaskService
import com.cras.app.domain.filterInboxTasks
import com.cras.app.domain.filterTodayTasks
import com.cras.app.domain.filterUpcomingTasks
import com.cras.app.models.Plan
import com.cras.app.models.Task
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.inbox.InboxUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ReleaseMatrixJourneyTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var config: PublicSupabaseConfig
    private val httpClient = OkHttpClient()
    private val testDispatcher = StandardTestDispatcher()

    private val operatorId = "11111111-1111-1111-1111-111111111111"
    private val session = OperatorSession(
        operatorId = operatorId,
        email = "matrix.operator@example.com",
        accessToken = "matrix-token"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockServer = MockWebServer()
        mockServer.start()
        config = PublicSupabaseConfig(
            url = mockServer.url("/").toString().removeSuffix("/"),
            publishableKey = "matrix-anon-key"
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockServer.shutdown()
    }

    @Test
    fun `executes operator journey across API level matrix`() = runTest(testDispatcher) {
        val taskService = SupabaseTaskService(config, httpClient)
        val apiLevel = Build.VERSION.SDK_INT
        val createdTaskId = UUID.randomUUID().toString()

        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""

                if (path.contains("create_task")) {
                    return MockResponse().setResponseCode(200).setBody("""
                        {
                            "id": "$createdTaskId",
                            "title": "Matrix Task API $apiLevel",
                            "description": "Tested on Android API $apiLevel",
                            "priority": 2,
                            "plan": null,
                            "labels": [],
                            "parentId": null,
                            "completedAt": null,
                            "createdAt": "2026-08-26T12:00:00Z",
                            "updatedAt": "2026-08-26T12:00:00Z",
                            "version": 1
                        }
                    """.trimIndent())
                }

                if (path.contains("complete_task")) {
                    return MockResponse().setResponseCode(200).setBody("""
                        {
                            "id": "$createdTaskId",
                            "title": "Matrix Task API $apiLevel",
                            "description": "Tested on Android API $apiLevel",
                            "priority": 2,
                            "plan": null,
                            "labels": [],
                            "parentId": null,
                            "completedAt": "2026-08-26T12:05:00Z",
                            "createdAt": "2026-08-26T12:00:00Z",
                            "updatedAt": "2026-08-26T12:05:00Z",
                            "version": 2
                        }
                    """.trimIndent())
                }

                return MockResponse().setResponseCode(200).setBody("[]")
            }
        }

        // 1. Create task
        val task = taskService.createTask(
            session,
            CreateTaskParams(
                id = createdTaskId,
                title = "Matrix Task API $apiLevel",
                description = "Tested on Android API $apiLevel",
                priority = 2
            )
        )

        assertEquals(createdTaskId, task.id)
        assertEquals("Matrix Task API $apiLevel", task.title)
        assertEquals(2, task.priority)

        // 2. Complete task
        val completed = taskService.completeTask(session, createdTaskId, 1)
        assertNotNull(completed.completedAt)
        assertEquals(2, completed.version)

        // 3. Verify temporal domain filtering on Android matrix
        val todayDate = LocalDate.now(ZoneId.of("UTC")).toString()
        val datedTask = Task(
            id = UUID.randomUUID().toString(),
            title = "Dated Plan Task",
            description = null,
            priority = 4,
            plan = Plan.DateOnly(date = todayDate),
            labels = emptyList(),
            parentId = null,
            completedAt = null,
            createdAt = "2026-08-26T12:00:00Z",
            updatedAt = "2026-08-26T12:00:00Z",
            version = 1
        )

        val tasksList = listOf(task, datedTask)
        val inboxTasks = filterInboxTasks(tasksList)
        val todayTasks = filterTodayTasks(tasksList, java.time.Instant.now(), ZoneId.of("UTC"))

        assertTrue("Inbox should contain untimed task", inboxTasks.any { it.id == task.id })
        assertTrue("Today should contain dated task", todayTasks.any { it.id == datedTask.id })
    }
}
