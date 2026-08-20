package com.cras.app.ui.inbox

import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CommentService
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.DeploymentConfig
import com.cras.app.data.InvalidationPayload
import com.cras.app.data.LabelService
import com.cras.app.data.OperatorSettings
import com.cras.app.data.RealtimeService
import com.cras.app.data.RealtimeSubscription
import com.cras.app.data.SettingsService
import com.cras.app.data.TaskConflictException
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

        override suspend fun fetchTaskById(session: OperatorSession, taskId: String): Task? {
            if (shouldFail) throw RuntimeException(failureMessage)
            return tasksInDb.find { it.id == taskId }
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
                throw TaskConflictException(
                    message = "Task version conflict: expected ${params.expectedVersion}, found ${existing.version}",
                    code = "P0003",
                    expectedVersion = params.expectedVersion,
                    foundVersion = existing.version
                )
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
            expectedVersion: Int,
            completedAt: String?
        ): Task {
            if (shouldFail) throw RuntimeException(failureMessage)
            val index = tasksInDb.indexOfFirst { it.id == taskId }
            if (index == -1) throw RuntimeException("Task not found")
            val existing = tasksInDb[index]
            if (existing.version != expectedVersion) {
                throw TaskConflictException(
                    message = "Task version conflict: expected $expectedVersion, found ${existing.version}",
                    code = "P0003",
                    expectedVersion = expectedVersion,
                    foundVersion = existing.version
                )
            }
            val completed = existing.copy(
                completedAt = completedAt ?: "2026-08-19T10:00:00Z",
                updatedAt = "2026-08-19T10:00:00Z",
                version = existing.version + 1
            )
            tasksInDb[index] = completed
            return completed
        }

        override suspend fun uncompleteTask(
            session: OperatorSession,
            taskId: String,
            expectedVersion: Int
        ): Task {
            if (shouldFail) throw RuntimeException(failureMessage)
            val index = tasksInDb.indexOfFirst { it.id == taskId }
            if (index == -1) throw RuntimeException("Task not found")
            val existing = tasksInDb[index]
            if (existing.version != expectedVersion) {
                throw TaskConflictException(
                    message = "Task version conflict: expected $expectedVersion, found ${existing.version}",
                    code = "P0003",
                    expectedVersion = expectedVersion,
                    foundVersion = existing.version
                )
            }
            val uncompleted = existing.copy(
                completedAt = null,
                updatedAt = "2026-08-19T10:05:00Z",
                version = existing.version + 1
            )
            tasksInDb[index] = uncompleted
            return uncompleted
        }
    }

    private class FakeRealtimeService : RealtimeService {
        var onInvalidateCallback: ((InvalidationPayload) -> Unit)? = null
        var onReconnectCallback: (() -> Unit)? = null
        var isSubscribed = false

        override fun subscribeToInvalidations(
            session: OperatorSession,
            onInvalidate: (InvalidationPayload) -> Unit,
            onReconnect: (() -> Unit)?
        ): RealtimeSubscription {
            onInvalidateCallback = onInvalidate
            onReconnectCallback = onReconnect
            isSubscribed = true

            return object : RealtimeSubscription {
                override fun unsubscribe() {
                    isSubscribed = false
                    onInvalidateCallback = null
                    onReconnectCallback = null
                }
            }
        }

        fun emitInvalidate(payload: InvalidationPayload) {
            onInvalidateCallback?.invoke(payload)
        }

        fun triggerReconnect() {
            onReconnectCallback?.invoke()
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
    fun `signOut cancels load job and unsubscribes realtime service`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val realtimeService = FakeRealtimeService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()
        assertTrue(realtimeService.isSubscribed)

        viewModel.signOut()
        advanceUntilIdle()
        assertFalse(realtimeService.isSubscribed)
        assertTrue(viewModel.authState.value is AuthUiState.Unauthenticated)
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
    fun `loadTasks preserves task success state when labelService fails`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Buy groceries")
        advanceUntilIdle()

        assertTrue(viewModel.inboxState.value is InboxUiState.Success)

        // Make label service fail
        labelService.shouldFail = true
        viewModel.loadTasks()
        advanceUntilIdle()

        // Tasks state must remain Success, not Error
        val inboxState = viewModel.inboxState.value
        assertTrue(inboxState is InboxUiState.Success)
        assertEquals(1, (inboxState as InboxUiState.Success).tasks.size)
        assertEquals("Buy groceries", inboxState.tasks[0].title)
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
        viewModel.completeTask(taskOne.id, expectedVersion = taskOne.version, completedAt = "2026-08-19T10:00:00Z")
        advanceUntilIdle()

        // Complete Task Two at 11:00
        viewModel.completeTask(taskTwo.id, expectedVersion = taskTwo.version, completedAt = "2026-08-19T11:00:00Z")
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
        viewModel.uncompleteTask(taskOne.id, expectedVersion = 2)
        advanceUntilIdle()

        val inboxAfterUncomplete = (viewModel.inboxState.value as InboxUiState.Success).tasks
        assertEquals(1, inboxAfterUncomplete.size)
        assertEquals("Task One", inboxAfterUncomplete[0].title)

        val completedAfterUncomplete = (viewModel.completedState.value as CompletedUiState.Success).tasks
        assertEquals(1, completedAfterUncomplete.size)
        assertEquals("Task Two", completedAfterUncomplete[0].title)
    }

    @Test
    fun `completeTask and uncompleteTask fail when task version cannot be resolved`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        var completeError: String? = null
        viewModel.completeTask(
            taskId = "non-existent-task-id",
            onError = { completeError = it }
        )
        advanceUntilIdle()
        assertEquals("Task state is unavailable. Refresh and try again.", completeError)

        var uncompleteError: String? = null
        viewModel.uncompleteTask(
            taskId = "non-existent-task-id",
            onError = { uncompleteError = it }
        )
        advanceUntilIdle()
        assertEquals("Task state is unavailable. Refresh and try again.", uncompleteError)
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

        viewModel.selectTask(createdTask)
        advanceUntilIdle()
        assertEquals("Initial Title", viewModel.selectedTask.value?.title)

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
        assertEquals("Updated Title", viewModel.selectedTask.value?.title)
        assertEquals(2, viewModel.selectedTask.value?.version)

        // 2. Complete task
        viewModel.completeTask(updatedTask.id, expectedVersion = updatedTask.version)
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
        viewModel.uncompleteTask(updatedTask.id, expectedVersion = 3)
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

    @Test
    fun `signOut clears selectedTask and resets all ui states`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Sign out test task")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        viewModel.selectTask(task)
        advanceUntilIdle()
        assertNotNull(viewModel.selectedTask.value)

        authService.sessionFlow.value = null
        advanceUntilIdle()

        assertNull(viewModel.selectedTask.value)
        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
        assertTrue(viewModel.todayState.value is TodayUiState.Empty)
        assertTrue(viewModel.upcomingState.value is UpcomingUiState.Empty)
        assertTrue(viewModel.completedState.value is CompletedUiState.Empty)
        assertTrue(viewModel.labels.value.isEmpty())
        assertTrue(viewModel.comments.value.isEmpty())
    }

    @Test
    fun `completing or uncompleting a subtask does not change selected parent task`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        viewModel.createTask("Parent Task")
        advanceUntilIdle()

        val parentTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        viewModel.selectTask(parentTask)
        advanceUntilIdle()
        assertEquals(parentTask.id, viewModel.selectedTask.value?.id)

        var createdSubtask: Task? = null
        viewModel.createSubtask(parentTask.id, "Subtask 1", onSuccess = { createdSubtask = it })
        advanceUntilIdle()
        assertNotNull(createdSubtask)

        // Complete subtask
        viewModel.completeTask(createdSubtask!!.id, expectedVersion = createdSubtask!!.version)
        advanceUntilIdle()

        // Parent task should still be selected
        assertEquals(parentTask.id, viewModel.selectedTask.value?.id)
        assertEquals("Parent Task", viewModel.selectedTask.value?.title)

        // Uncomplete subtask
        viewModel.uncompleteTask(createdSubtask!!.id, expectedVersion = 2)
        advanceUntilIdle()

        // Parent task should still be selected
        assertEquals(parentTask.id, viewModel.selectedTask.value?.id)
        assertEquals("Parent Task", viewModel.selectedTask.value?.title)
    }

    @Test
    fun `realtime task invalidation applies updates without full reload`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val realtimeService = FakeRealtimeService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()

        viewModel.createTask("Task A")
        advanceUntilIdle()

        val taskA = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]

        // Remote client (e.g. Web) updates Task A in DB to version 2
        val remoteUpdatedTask = taskA.copy(title = "Task A Updated by Web", version = 2)
        val idx = taskService.tasksInDb.indexOfFirst { it.id == taskA.id }
        taskService.tasksInDb[idx] = remoteUpdatedTask

        // Realtime event arrives
        realtimeService.emitInvalidate(
            InvalidationPayload(
                resource = "task",
                id = taskA.id,
                operation = "updated"
            )
        )
        advanceUntilIdle()

        val updatedLocalTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Task A Updated by Web", updatedLocalTask.title)
        assertEquals(2, updatedLocalTask.version)
    }

    @Test
    fun `realtime task invalidation inserts absent task when updated from another session`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val realtimeService = FakeRealtimeService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()

        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)

        // Web creates a task
        val webTask = Task(
            id = UUID.randomUUID().toString(),
            title = "Task from Web",
            description = null,
            priority = 3,
            plan = null,
            labels = emptyList(),
            parentId = null,
            completedAt = null,
            createdAt = "2026-08-19T00:00:00Z",
            updatedAt = "2026-08-19T00:00:00Z",
            version = 1
        )
        taskService.tasksInDb.add(webTask)

        // Broadcast arrives
        realtimeService.emitInvalidate(
            InvalidationPayload(
                resource = "task",
                id = webTask.id,
                operation = "created"
            )
        )
        advanceUntilIdle()

        val state = viewModel.inboxState.value
        assertTrue(state is InboxUiState.Success)
        assertEquals(1, (state as InboxUiState.Success).tasks.size)
        assertEquals("Task from Web", state.tasks[0].title)
    }

    @Test
    fun `realtime task invalidation discards stale version without overwriting newer state`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val realtimeService = FakeRealtimeService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()

        viewModel.createTask("Newer Task")
        advanceUntilIdle()

        val currentTask = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        // Local state is at version 3
        val localV3 = currentTask.copy(title = "Local Newer Title", version = 3)
        viewModel.applyTaskUpdate(localV3)

        // An older version (version 2) is fetched from DB
        val staleV2 = currentTask.copy(title = "Stale Remote Title", version = 2)
        taskService.tasksInDb[0] = staleV2

        realtimeService.emitInvalidate(
            InvalidationPayload(
                resource = "task",
                id = currentTask.id,
                operation = "updated"
            )
        )
        advanceUntilIdle()

        // Local state must retain version 3
        val taskAfter = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        assertEquals("Local Newer Title", taskAfter.title)
        assertEquals(3, taskAfter.version)
    }

    @Test
    fun `realtime task delete invalidation removes task from state and clears selectedTask`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val realtimeService = FakeRealtimeService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()

        viewModel.createTask("Task to Delete")
        advanceUntilIdle()

        val task = (viewModel.inboxState.value as InboxUiState.Success).tasks[0]
        viewModel.selectTask(task)
        advanceUntilIdle()
        assertNotNull(viewModel.selectedTask.value)

        // Remove from db
        taskService.tasksInDb.removeIf { it.id == task.id }

        // Realtime delete broadcast arrives
        realtimeService.emitInvalidate(
            InvalidationPayload(
                resource = "task",
                id = task.id,
                operation = "deleted"
            )
        )
        advanceUntilIdle()

        assertTrue(viewModel.inboxState.value is InboxUiState.Empty)
        assertNull(viewModel.selectedTask.value)
    }

    @Test
    fun `realtime label invalidation triggers refetch of labels`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val realtimeService = FakeRealtimeService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()

        assertEquals(0, viewModel.labels.value.size)

        // Label added in DB externally
        val label = Label(id = UUID.randomUUID().toString(), name = "Feature", color = "#3b82f6")
        labelService.labelsInDb.add(label)

        realtimeService.emitInvalidate(
            InvalidationPayload(
                resource = "label",
                id = label.id,
                operation = "created"
            )
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.labels.value.size)
        assertEquals("Feature", viewModel.labels.value[0].name)
    }

    @Test
    fun `realtime reconnect triggers full canonical reload`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val realtimeService = FakeRealtimeService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            realtimeService = realtimeService
        )
        advanceUntilIdle()

        // During offline period, 2 tasks are created on server
        val task1 = Task(id = UUID.randomUUID().toString(), title = "Offline Created 1", description = null, priority = 4, plan = null, labels = emptyList(), parentId = null, completedAt = null, createdAt = "2026-08-19T00:00:00Z", updatedAt = "2026-08-19T00:00:00Z", version = 1)
        val task2 = Task(id = UUID.randomUUID().toString(), title = "Offline Created 2", description = null, priority = 4, plan = null, labels = emptyList(), parentId = null, completedAt = null, createdAt = "2026-08-19T00:00:00Z", updatedAt = "2026-08-19T00:00:00Z", version = 1)
        taskService.tasksInDb.addAll(listOf(task1, task2))

        // Trigger reconnect
        realtimeService.triggerReconnect()
        advanceUntilIdle()

        val state = viewModel.inboxState.value
        assertTrue(state is InboxUiState.Success)
        assertEquals(2, (state as InboxUiState.Success).tasks.size)
    }

    @Test
    fun `cancelling loadTasks does not set state to Error`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val deferred = CompletableDeferred<List<Task>>()
        val customTaskService = object : TaskService by taskService {
            override suspend fun fetchTasks(session: OperatorSession): List<Task> {
                return deferred.await()
            }
        }

        val viewModel = InboxViewModel(authService, customTaskService, labelService, commentService)
        viewModel.loadTasks()

        // CancellationException when the deferred is cancelled / task cancelled
        deferred.cancel()
        advanceUntilIdle()

        assertFalse(viewModel.inboxState.value is InboxUiState.Error)
        assertFalse(viewModel.todayState.value is TodayUiState.Error)
        assertFalse(viewModel.upcomingState.value is UpcomingUiState.Error)
        assertFalse(viewModel.completedState.value is CompletedUiState.Error)
    }

    @Test
    fun `taskService throwing CancellationException does not transition state to Error`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val customTaskService = object : TaskService by taskService {
            override suspend fun fetchTasks(session: OperatorSession): List<Task> {
                throw CancellationException("Fetch tasks cancelled")
            }
        }

        val viewModel = InboxViewModel(authService, customTaskService, labelService, commentService)
        viewModel.loadTasks()
        advanceUntilIdle()

        assertFalse(viewModel.inboxState.value is InboxUiState.Error)
        assertFalse(viewModel.todayState.value is TodayUiState.Error)
        assertFalse(viewModel.upcomingState.value is UpcomingUiState.Error)
        assertFalse(viewModel.completedState.value is CompletedUiState.Error)
    }

    @Test
    fun `cancellation during comments fetch does not set state to Error or commentsError`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val customCommentService = object : CommentService by commentService {
            override suspend fun fetchComments(session: OperatorSession, taskId: String?): List<Comment> {
                throw CancellationException("Fetch comments cancelled")
            }
        }

        val viewModel = InboxViewModel(authService, taskService, labelService, customCommentService)
        viewModel.loadTasks()
        advanceUntilIdle()

        assertFalse(viewModel.inboxState.value is InboxUiState.Error)
        assertNull(viewModel.commentsError.value)
    }

    @Test
    fun `loadTasks preserves comments when no task is selected and clears comments when selected task is dropped`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val taskId = UUID.randomUUID().toString()
        val task1 = Task(id = taskId, title = "Task 1", description = null, priority = 4, plan = null, labels = emptyList(), parentId = null, completedAt = null, createdAt = "2026-08-19T00:00:00Z", updatedAt = "2026-08-19T00:00:00Z", version = 1)
        taskService.tasksInDb.add(task1)
        val commentId = UUID.randomUUID().toString()
        commentService.commentsInDb.add(Comment(id = commentId, taskId = taskId, content = "General comment", createdAt = "2026-08-19T10:00:00Z"))

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        // When no task is selected, loadTasks should NOT clear freshly loaded comments
        assertEquals(1, viewModel.comments.value.size)
        assertEquals(commentId, viewModel.comments.value[0].id)
        assertNull(viewModel.selectedTask.value)

        // Select task-1
        viewModel.selectTask(task1)
        advanceUntilIdle()
        assertNotNull(viewModel.selectedTask.value)

        // Dropping selection via reconcileFreshTasks when task is deleted
        viewModel.reconcileFreshTasks(emptyList())
        advanceUntilIdle()

        assertNull(viewModel.selectedTask.value)
        assertTrue(viewModel.comments.value.isEmpty())
    }

    @Test
    fun `updateTask fails with error when expectedVersion is absent and task is not in allTasks`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val labelService = FakeLabelService()
        val commentService = FakeCommentService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService, labelService, commentService)
        advanceUntilIdle()

        var errorMsg: String? = null
        var updateCalled = false
        val customTaskService = object : TaskService by taskService {
            override suspend fun updateTask(session: OperatorSession, params: UpdateTaskParams): Task {
                updateCalled = true
                return taskService.updateTask(session, params)
            }
        }
        val vmWithCustomTaskService = InboxViewModel(authService, customTaskService, labelService, commentService)
        advanceUntilIdle()

        val nonExistentId = UUID.randomUUID().toString()
        vmWithCustomTaskService.updateTask(
            UpdateTaskParams(id = nonExistentId, title = "New title"),
            onError = { errorMsg = it }
        )
        advanceUntilIdle()

        assertEquals("Task state is unavailable. Refresh and try again.", errorMsg)
        assertFalse(updateCalled)
    }
}
