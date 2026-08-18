package com.cras.app.ui.inbox

import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.TaskService
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

        override suspend fun fetchTasks(session: OperatorSession): List<Task> {
            if (shouldFail) throw RuntimeException("Network error")
            return tasksInDb.toList()
        }

        override suspend fun createTask(session: OperatorSession, params: CreateTaskParams): Task {
            if (shouldFail) throw RuntimeException("Create failed")
            val task = Task(
                id = java.util.UUID.randomUUID().toString(),
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
    }

    @Test
    fun `createTask with duplicate titles creates distinct tasks in inbox`() = runTest {
        val authService = FakeAuthService()
        val taskService = FakeTaskService()
        val session = OperatorSession("op-1", "alice@cras.app", "token-1")
        authService.sessionFlow.value = session

        val viewModel = InboxViewModel(authService, taskService)
        advanceUntilIdle()

        viewModel.createTask("Duplicate Title")
        advanceUntilIdle()
        viewModel.createTask("Duplicate Title")
        advanceUntilIdle()

        val state = viewModel.inboxState.value
        assertTrue(state is InboxUiState.Success)
        val tasks = (state as InboxUiState.Success).tasks
        assertEquals(2, tasks.size)
        assertEquals("Duplicate Title", tasks[0].title)
        assertEquals("Duplicate Title", tasks[1].title)
        assertFalse(tasks[0].id == tasks[1].id)
    }
}
