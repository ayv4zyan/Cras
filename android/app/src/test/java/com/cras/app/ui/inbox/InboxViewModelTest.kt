package com.cras.app.ui.inbox

import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateTaskParams
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
                plan = params.plan ?: existing.plan,
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
        val viewModel = InboxViewModel(authService, taskService)

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
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService)
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
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService)
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
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService)
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
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService)
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
                expectedVersion = 1 // Stale expected version (current is 4)
            ),
            onError = { conflictError = it }
        )
        advanceUntilIdle()

        assertNotNull(conflictError)
        assertTrue(conflictError!!.contains("version conflict"))
    }
}
