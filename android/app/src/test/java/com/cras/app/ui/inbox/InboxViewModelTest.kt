package com.cras.app.ui.inbox

import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CommentService
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.DeploymentConfig
import com.cras.app.data.LabelService
import com.cras.app.data.OperatorSettings
import com.cras.app.data.SettingsService
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeAuthService : AuthService {
        val sessionFlow = MutableStateFlow<OperatorSession?>(null)
        override val currentSession: StateFlow<OperatorSession?> = sessionFlow

        override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?): OperatorSession {
            val session = OperatorSession("op-1", "alice@cras.app", "token-1")
            sessionFlow.value = session
            return session
        }

        override suspend fun restoreSession(): OperatorSession? {
            return sessionFlow.value
        }

        override suspend fun signOut() {
            sessionFlow.value = null
        }
    }

    private class FakeLabelService : LabelService {
        val labelsInDb = mutableListOf<Label>()
        var shouldFail = false
        var failureMessage = "Network error"

        override suspend fun fetchLabels(session: OperatorSession): List<Label> {
            if (shouldFail) throw RuntimeException(failureMessage)
            return labelsInDb.toList()
        }

        override suspend fun createLabel(session: OperatorSession, params: CreateLabelParams): Label {
            if (shouldFail) throw RuntimeException(failureMessage)
            if (labelsInDb.any { it.name.equals(params.name.trim(), ignoreCase = true) }) {
                throw RuntimeException("A label with this name already exists")
            }
            val label = Label(
                id = params.id ?: UUID.randomUUID().toString(),
                name = params.name.trim(),
                color = params.color.trim(),
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z"
            )
            labelsInDb.add(label)
            return label
        }

        override suspend fun updateLabel(session: OperatorSession, params: UpdateLabelParams): Label {
            if (shouldFail) throw RuntimeException(failureMessage)
            val index = labelsInDb.indexOfFirst { it.id == params.id }
            if (index == -1) throw RuntimeException("Label not found")
            if (params.name != null && labelsInDb.any { it.id != params.id && it.name.equals(params.name.trim(), ignoreCase = true) }) {
                throw RuntimeException("A label with this name already exists")
            }
            val existing = labelsInDb[index]
            val updated = existing.copy(
                name = params.name?.trim() ?: existing.name,
                color = params.color?.trim() ?: existing.color,
                updatedAt = "2026-08-19T00:05:00Z"
            )
            labelsInDb[index] = updated
            return updated
        }

        override suspend fun deleteLabel(session: OperatorSession, labelId: String) {
            if (shouldFail) throw RuntimeException(failureMessage)
            labelsInDb.removeAll { it.id == labelId }
        }
    }

    private class FakeCommentService : CommentService {
        val commentsInDb = mutableListOf<Comment>()
        var shouldFail = false
        var failureMessage = "Network error"

        override suspend fun fetchComments(session: OperatorSession, taskId: String?): List<Comment> {
            if (shouldFail) throw RuntimeException(failureMessage)
            return if (taskId != null) {
                commentsInDb.filter { it.taskId == taskId }
            } else {
                commentsInDb.toList()
            }
        }

        override suspend fun createComment(session: OperatorSession, params: CreateCommentParams): Comment {
            if (shouldFail) throw RuntimeException(failureMessage)
            val comment = Comment(
                id = params.id ?: UUID.randomUUID().toString(),
                taskId = params.taskId,
                content = params.content.trim(),
                createdAt = "2026-08-19T10:00:00Z"
            )
            commentsInDb.add(comment)
            return comment
        }
    }

    private class FakeTaskService : TaskService {
        val tasksInDb = mutableListOf<Task>()
        var shouldFail = false
        var failureMessage = "Network error"

        override suspend fun fetchTasks(session: OperatorSession): List<Task> {
            if (shouldFail) throw RuntimeException(failureMessage)
            return tasksInDb.toList()
        }

        override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
            if (shouldFail) throw RuntimeException(failureMessage)
            if (params.parentId != null) {
                val parent = tasksInDb.find { it.id == params.parentId }
                if (parent != null && parent.parentId != null) {
                    throw RuntimeException("Subtasks cannot have children (one-level nesting only)")
                }
            }

            val task = Task(
                id = UUID.randomUUID().toString(),
                title = params.title.trim(),
                description = params.description,
                priority = params.priority,
                plan = params.plan,
                labels = params.labels,
                parentId = params.parentId,
                completedAt = null,
                createdAt = "2026-08-19T00:00:00Z",
                updatedAt = "2026-08-19T00:00:00Z",
                version = 1
            )
            tasksInDb.add(task)
            return task
        }

        override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task {
            if (shouldFail) throw RuntimeException(failureMessage)
            val index = tasksInDb.indexOfFirst { it.id == params.id }
            if (index == -1) throw RuntimeException("Task not found")
            val existing = tasksInDb[index]
            if (existing.completedAt != null) {
                throw RuntimeException("Completed tasks cannot be edited. Uncomplete first.")
            }
            if (params.expectedVersion != null && existing.version != params.expectedVersion) {
                throw RuntimeException("Task version conflict: expected ${params.expectedVersion}, found ${existing.version}")
            }
            val updated = existing.copy(
                title = params.title ?: existing.title,
                description = params.description ?: existing.description,
                priority = params.priority ?: existing.priority,
                plan = if (params.clearPlan) null else (params.plan ?: existing.plan),
                labels = params.labels ?: existing.labels,
                parentId = params.parentId ?: existing.parentId,
                updatedAt = "2026-08-19T00:05:00Z",
                version = existing.version + 1
            )
            tasksInDb[index] = updated
            return updated
        }

        override suspend fun completeTask(
            session: OperatorSession,
            taskId: String,
            completedAt: String?
        ): Task {
            if (shouldFail) throw RuntimeException(failureMessage)
            val index = tasksInDb.indexOfFirst { it.id == taskId }
            if (index == -1) throw RuntimeException("Task not found")
            val existing = tasksInDb[index]
            val completed = existing.copy(
                completedAt = completedAt ?: "2026-08-19T10:00:00Z",
                updatedAt = "2026-08-19T10:00:00Z",
                version = existing.version + 1
            )
            tasksInDb[index] = completed
            return completed
        }

        override suspend fun uncompleteTask(session: OperatorSession, taskId: String): Task {
            if (shouldFail) throw RuntimeException(failureMessage)
            val index = tasksInDb.indexOfFirst { it.id == taskId }
            if (index == -1) throw RuntimeException("Task not found")
            val existing = tasksInDb[index]
            val uncompleted = existing.copy(
                completedAt = null,
                updatedAt = "2026-08-19T10:05:00Z",
                version = existing.version + 1
            )
            tasksInDb[index] = uncompleted
            return uncompleted
        }
    }

    private class FakeSettingsService : SettingsService {
        var currentEffectiveType = TimedPlanType.INSTANT
        var shouldFail = false

        override suspend fun fetchOperatorSettings(session: OperatorSession): OperatorSettings? {
            return OperatorSettings(defaultTimedPlanType = currentEffectiveType)
        }

        override suspend fun fetchDeploymentConfig(session: OperatorSession): DeploymentConfig? {
            return DeploymentConfig(defaultTimedPlanType = TimedPlanType.INSTANT)
        }

        override suspend fun fetchEffectiveTimedPlanType(session: OperatorSession): TimedPlanType {
            if (shouldFail) return currentEffectiveType
            return currentEffectiveType
        }

        override suspend fun updateOperatorTimedPlanType(session: OperatorSession, type: TimedPlanType?) {
            currentEffectiveType = type ?: TimedPlanType.INSTANT
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial unauthenticated state transitions to Authenticated upon sign in`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)

        advanceUntilIdle()
        assertTrue(viewModel.authState.value is AuthUiState.Unauthenticated)

        viewModel.signInWithGoogleIdToken("google-token-xyz")
        advanceUntilIdle()

        val auth = viewModel.authState.value
        assertTrue(auth is AuthUiState.Authenticated)
        assertEquals("alice@cras.app", (auth as AuthUiState.Authenticated).session.email)
    }

    @Test
    fun `loadInbox transitions through Loading, Empty, Success, and Failure states`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        // 1. Empty state when no tasks exist
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
        assertTrue(viewModel.completedState.value is CompletedUiState.Empty)

        // 2. Success state when tasks exist
        viewModel.createTask("Buy coffee")
        advanceUntilIdle()

        val successState = viewModel.inboxState.value
        assertTrue(successState is InboxUiState.Success)
        assertEquals(1, (successState as InboxUiState.Success).tasks.size)
        assertEquals("Buy coffee", successState.tasks[0].title)

        // 3. Failure state on network error
        taskService.shouldFail = true
        viewModel.loadTasks()
        advanceUntilIdle()

        val errorState = viewModel.inboxState.value
        assertTrue(errorState is InboxUiState.Error)
        assertEquals("Network error", (errorState as InboxUiState.Error).message)
        val completedError = viewModel.completedState.value
        assertTrue(completedError is CompletedUiState.Error)
    }

    @Test
    fun `createTask with all priority states and descriptions`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Urgent task", description = "Must do today", priority = 1)
        advanceUntilIdle()
        viewModel.createTask("High task", description = "Next up", priority = 2)
        advanceUntilIdle()
        viewModel.createTask("Medium task", priority = 3)
        advanceUntilIdle()
        viewModel.createTask("Low task", priority = 4)
        advanceUntilIdle()

        val state = viewModel.inboxState.value
        assertTrue(state is InboxUiState.Success)
        val tasks = (state as InboxUiState.Success).tasks
        assertEquals(4, tasks.size)
        assertEquals(1, tasks.find { it.title == "Urgent task" }?.priority)
        assertEquals("Must do today", tasks.find { it.title == "Urgent task" }?.description)
        assertEquals(2, tasks.find { it.title == "High task" }?.priority)
        assertEquals(3, tasks.find { it.title == "Medium task" }?.priority)
        assertEquals(4, tasks.find { it.title == "Low task" }?.priority)
    }

    @Test
    fun `completeTask removes task from inbox and adds to completed ordered newest-first`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Task One")
        advanceUntilIdle()
        viewModel.createTask("Task Two")
        advanceUntilIdle()

        val inboxTasks = (viewModel.inboxState.value as InboxUiState.Success).tasks
        val taskOne = inboxTasks.find { it.title == "Task One" }!!
        val taskTwo = inboxTasks.find { it.title == "Task Two" }!!

        // Complete Task One at 10:00
        viewModel.completeTask(taskOne.id, completedAt = "2026-08-19T10:00:00Z")
        advanceUntilIdle()

        // Complete Task Two at 11:00
        viewModel.completeTask(taskTwo.id, completedAt = "2026-08-19T11:00:00Z")
        advanceUntilIdle()

        // Inbox should now be empty
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)

        // Completed should have 2 tasks, newest-first (Task Two then Task One)
        val completedState = viewModel.completedState.value
        assertTrue(completedState is CompletedUiState.Success)
        val completedTasks = (completedState as CompletedUiState.Success).tasks
        assertEquals(2, completedTasks.size)
        assertEquals("Task Two", completedTasks[0].title)
        assertEquals("Task One", completedTasks[1].title)

        // Uncomplete Task One
        viewModel.uncompleteTask(taskOne.id)
        advanceUntilIdle()

        val inboxAfterUncomplete = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxAfterUncomplete.size)
        assertEquals("Task One", inboxAfterUncomplete[0].title)

        val completedAfterUncomplete = (viewModel.completedState.value as CompletedUiState.Success).tasks
        assertEquals(1, completedAfterUncomplete.size)
        assertEquals("Task Two", completedAfterUncomplete[0].title)
    }

    @Test
    fun `updateTask edits fields and handles rejection on completed tasks or version conflict`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Initial Title")
        advanceUntilIdle()

        val createdTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]

        // 1. Successful update
        var updateSuccess = false
        var updateError: String? = null
        viewModel.updateTask(
            UpdateTaskParams(
                id = createdTask.id,
                title = "Updated Title",
                description = "Added description",
                priority = 1,
                expectedVersion = createdTask.version
            ),
            onSuccess = { updateSuccess = true },
            onError = { updateError = it }
        )
        advanceUntilIdle()

        assertTrue(updateSuccess)
        assertNull(updateError)

        val updatedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Updated Title", updatedTask.title)
        assertEquals("Added description", updatedTask.description)
        assertEquals(1, updatedTask.priority)
        assertEquals(2, updatedTask.version)

        // 2. Complete task
        viewModel.completeTask(updatedTask.id)
        advanceUntilIdle()

        // 3. Attempt update on completed task - must be rejected
        var completedUpdateError: String? = null
        viewModel.updateTask(
            UpdateTaskParams(
                id = updatedTask.id,
                title = "Illegal Title Change"
            ),
            onError = { completedUpdateError = it }
        )
        advanceUntilIdle()

        assertNotNull(completedUpdateError)
        assertTrue(completedUpdateError!!.contains("Completed tasks cannot be edited"))

        // 4. Stale version conflict recovery
        viewModel.uncompleteTask(updatedTask.id)
        advanceUntilIdle()

        val uncompletedTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        var conflictError: String? = null
        viewModel.updateTask(
            UpdateTaskParams(
                id = uncompletedTask.id,
                title = "Conflict Attempt",
                expectedVersion = 1
            ),
            onError = { conflictError = it }
        )
        advanceUntilIdle()

        assertNotNull(conflictError)
        assertTrue(conflictError!!.contains("version conflict"))
    }

    @Test
    fun `label lifecycle - create, rename, recolor, delete, and duplicate-name rejection`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        // 1. Create label
        var createdLabel: Label? = null
        viewModel.createLabel("Urgent", "#ef4444", onSuccess = { createdLabel = it })
        advanceUntilIdle()

        assertNotNull(createdLabel)
        assertEquals("Urgent", createdLabel!!.name)
        assertEquals("#ef4444", createdLabel!!.color)
        assertEquals(1, viewModel.labels.value.size)

        // 2. Duplicate label rejection
        var duplicateError: String? = null
        viewModel.createLabel("urgent", "#3b82f6", onError = { duplicateError = it })
        advanceUntilIdle()

        assertNotNull(duplicateError)
        assertTrue(duplicateError!!.contains("already exists"))
        assertEquals(1, viewModel.labels.value.size)

        // 3. Rename and recolor label
        var updatedLabel: Label? = null
        viewModel.updateLabel(
            id = createdLabel!!.id,
            name = "Critical",
            color = "#f97316",
            onSuccess = { updatedLabel = it }
        )
        advanceUntilIdle()

        assertNotNull(updatedLabel)
        assertEquals("Critical", updatedLabel!!.name)
        assertEquals("#f97316", updatedLabel!!.color)
        assertEquals(createdLabel!!.id, updatedLabel!!.id)
        assertEquals("Critical", viewModel.labels.value[0].name)

        // 4. Create task with label
        viewModel.createTask("Deploy MVP", labels = listOf(createdLabel!!.id))
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(1, task.labels.size)
        assertEquals(createdLabel!!.id, task.labels[0])

        // 5. Delete label
        var deleted = false
        viewModel.deleteLabel(createdLabel!!.id, onSuccess = { deleted = true })
        advanceUntilIdle()

        assertTrue(deleted)
        assertEquals(0, viewModel.labels.value.size)
    }

    @Test
    fun `comment lifecycle - create comment updates comments flow and handles error`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Main feature")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(0, viewModel.comments.value.size)

        // 1. Add comment
        var createdComment: Comment? = null
        viewModel.createComment(task.id, "First feedback remark", onSuccess = { createdComment = it })
        advanceUntilIdle()

        assertNotNull(createdComment)
        assertEquals("First feedback remark", createdComment!!.content)
        assertEquals(task.id, createdComment!!.taskId)
        assertEquals(1, viewModel.comments.value.size)
        assertEquals("First feedback remark", viewModel.comments.value[0].content)

        // 2. Reject empty comment
        var commentError: String? = null
        viewModel.createComment(task.id, "   ", onError = { commentError = it })
        advanceUntilIdle()

        assertNotNull(commentError)
        assertEquals("Comment content cannot be empty", commentError)
        assertEquals(1, viewModel.comments.value.size)
    }

    @Test
    fun `subtask lifecycle - createSubtask creates subtask and enforces one-level nesting`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Top Level Task")
        advanceUntilIdle()

        val parentTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals(1, (viewModel.inboxState.value as InboxUiState.Success).tasks.size)

        // 1. Create subtask under top level task
        var createdSubtask: Task? = null
        viewModel.createSubtask(parentTask.id, "Child subtask", onSuccess = { createdSubtask = it })
        advanceUntilIdle()

        assertNotNull(createdSubtask)
        assertEquals("Child subtask", createdSubtask!!.title)
        assertEquals(parentTask.id, createdSubtask!!.parentId)

        // Subtask should NOT be in Inbox view (filterInboxTasks excludes subtasks)
        val inboxTasks = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxTasks.size)
        assertEquals(parentTask.id, inboxTasks[0].id)

        // 2. Attempt creating child under the subtask (nested level 2) - must fail
        var nestingError: String? = null
        viewModel.createSubtask(createdSubtask!!.id, "Illegal nested subtask", onError = { nestingError = it })
        advanceUntilIdle()

        assertNotNull(nestingError)
        assertTrue(nestingError!!.contains("Subtasks cannot have children (one-level nesting only)"))
    }

    @Test
    fun `todayState and upcomingState populate with correct tasks and date groupings`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val settingsService = FakeSettingsService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val fixedNow = Instant.parse("2026-08-19T12:00:00Z")
        val fixedZone = ZoneOffset.UTC

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            nowProvider = { fixedNow },
            zoneIdProvider = { fixedZone }
        )
        advanceUntilIdle()

        assertTrue(viewModel.todayState.value is TodayUiState.Empty)
        assertTrue(viewModel.upcomingState.value is UpcomingUiState.Empty)

        // 1. Create Inbox task (no date)
        viewModel.createTask("Inbox item")
        advanceUntilIdle()

        // 2. Create Today task
        viewModel.createTask("Today item", plan = Plan.DateOnly("2026-08-19"))
        advanceUntilIdle()

        // 3. Create Tomorrow task
        viewModel.createTask("Tomorrow item", plan = Plan.DateOnly("2026-08-20"))
        advanceUntilIdle()

        // 4. Create Overdue task
        viewModel.createTask("Overdue item", plan = Plan.DateOnly("2026-08-18"))
        advanceUntilIdle()

        // Verify Today State contains Overdue and Today items, not Inbox or Tomorrow
        val todayState = viewModel.todayState.value
        assertTrue(todayState is TodayUiState.Success)
        val todayTasks = (todayState as TodayUiState.Success).tasks
        assertEquals(2, todayTasks.size)
        assertEquals("Overdue item", todayTasks[0].title)
        assertEquals("Today item", todayTasks[1].title)

        // Verify Upcoming State contains Overdue strip and groups
        val upcomingState = viewModel.upcomingState.value
        assertTrue(upcomingState is UpcomingUiState.Success)
        val upcoming = upcomingState as UpcomingUiState.Success
        assertEquals(1, upcoming.overdue.size)
        assertEquals("Overdue item", upcoming.overdue[0].title)
        assertEquals(2, upcoming.groups.size)
        assertEquals("2026-08-19", upcoming.groups[0].date)
        assertEquals("Today", upcoming.groups[0].dateLabel)
        assertEquals("2026-08-20", upcoming.groups[1].date)
        assertEquals("Tomorrow", upcoming.groups[1].dateLabel)
    }

    @Test
    fun `updateTask with clearPlan moves task to Inbox and removes from Today and Upcoming`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val settingsService = FakeSettingsService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val fixedNow = Instant.parse("2026-08-19T12:00:00Z")
        val fixedZone = ZoneOffset.UTC

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            nowProvider = { fixedNow },
            zoneIdProvider = { fixedZone }
        )
        advanceUntilIdle()

        viewModel.createTask("Scheduled task", plan = Plan.DateOnly("2026-08-19"))
        advanceUntilIdle()

        // Initially in Today, not in Inbox
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
        assertTrue(viewModel.todayState.value is TodayUiState.Success)

        val scheduledTask = (viewModel.todayState.value as TodayUiState.Success).tasks[0]

        // Clear plan
        viewModel.updateTask(
            UpdateTaskParams(
                id = scheduledTask.id,
                clearPlan = true
            )
        )
        advanceUntilIdle()

        // Now in Inbox, empty in Today and Upcoming
        assertTrue(viewModel.inboxState.value is InboxUiState.Success)
        assertTrue(viewModel.todayState.value is TodayUiState.Empty)
        assertTrue(viewModel.upcomingState.value is UpcomingUiState.Empty)
    }
}
