package com.cras.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateTaskParams
import com.cras.app.domain.filterCompletedTasks
import com.cras.app.domain.filterInboxTasks
import com.cras.app.models.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    object Loading : AuthUiState
    data class Unauthenticated(val errorMessage: String? = null) : AuthUiState
    data class Authenticated(val session: OperatorSession) : AuthUiState
}

sealed interface InboxUiState {
    object Loading : InboxUiState
    object Empty : InboxUiState
    data class Success(val tasks: List<Task>) : InboxUiState
    data class Error(val message: String) : InboxUiState
}

sealed interface CompletedUiState {
    object Loading : CompletedUiState
    object Empty : CompletedUiState
    data class Success(val tasks: List<Task>) : CompletedUiState
    data class Error(val message: String) : CompletedUiState
}

class InboxViewModel(
    private val authService: AuthService,
    private val taskService: TaskService
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _inboxState = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val inboxState: StateFlow<InboxUiState> = _inboxState.asStateFlow()

    private val _completedState = MutableStateFlow<CompletedUiState>(CompletedUiState.Loading)
    val completedState: StateFlow<CompletedUiState> = _completedState.asStateFlow()

    private val _selectedTask = MutableStateFlow<Task?>(null)
    val selectedTask: StateFlow<Task?> = _selectedTask.asStateFlow()

    private val _isCreatingTask = MutableStateFlow(false)
    val isCreatingTask: StateFlow<Boolean> = _isCreatingTask.asStateFlow()

    private val _createTaskError = MutableStateFlow<String?>(null)
    val createTaskError: StateFlow<String?> = _createTaskError.asStateFlow()

    init {
        viewModelScope.launch {
            authService.currentSession.collect { session ->
                if (session != null) {
                    _authState.value = AuthUiState.Authenticated(session)
                    loadTasksInternal(session)
                } else {
                    _authState.value = AuthUiState.Unauthenticated()
                    _inboxState.value = InboxUiState.Empty
                    _completedState.value = CompletedUiState.Empty
                    _selectedTask.value = null
                }
            }
        }

        viewModelScope.launch {
            authService.restoreSession()
        }
    }

    fun selectTask(task: Task?) {
        _selectedTask.value = task
    }

    fun signInWithGoogleIdToken(idToken: String, nonce: String? = null) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            try {
                authService.signInWithGoogleIdToken(idToken, nonce)
            } catch (e: Exception) {
                _authState.value = AuthUiState.Unauthenticated(e.message ?: "Sign in failed")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authService.signOut()
        }
    }

    fun loadTasks() {
        val currentAuth = _authState.value
        if (currentAuth is AuthUiState.Authenticated) {
            viewModelScope.launch {
                loadTasksInternal(currentAuth.session)
            }
        }
    }

    private suspend fun loadTasksInternal(session: OperatorSession) {
        _inboxState.value = InboxUiState.Loading
        _completedState.value = CompletedUiState.Loading
        try {
            val allTasks = taskService.fetchTasks(session)
            val inboxTasks = filterInboxTasks(allTasks)
            val completedTasks = filterCompletedTasks(allTasks)

            _inboxState.value = if (inboxTasks.isEmpty()) {
                InboxUiState.Empty
            } else {
                InboxUiState.Success(inboxTasks)
            }

            _completedState.value = if (completedTasks.isEmpty()) {
                CompletedUiState.Empty
            } else {
                CompletedUiState.Success(completedTasks)
            }

            // Refresh selectedTask if it's currently selected
            val currentSelected = _selectedTask.value
            if (currentSelected != null) {
                _selectedTask.value = allTasks.find { it.id == currentSelected.id }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to load tasks"
            _inboxState.value = InboxUiState.Error(errorMsg)
            _completedState.value = CompletedUiState.Error(errorMsg)
        }
    }

    fun createTask(title: String, description: String? = null, priority: Int = 4) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return

        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) return

        viewModelScope.launch {
            _isCreatingTask.value = true
            _createTaskError.value = null
            try {
                taskService.createTask(
                    session = currentAuth.session,
                    params = CreateTaskParams(
                        title = trimmed,
                        description = description?.trim()?.ifEmpty { null },
                        priority = priority
                    )
                )
                loadTasksInternal(currentAuth.session)
            } catch (e: Exception) {
                _createTaskError.value = e.message ?: "Failed to create task"
            } finally {
                _isCreatingTask.value = false
            }
        }
    }

    fun updateTask(
        params: UpdateTaskParams,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        viewModelScope.launch {
            try {
                val updatedTask = taskService.updateTask(currentAuth.session, params)
                _selectedTask.value = updatedTask
                loadTasksInternal(currentAuth.session)
                onSuccess()
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to update task"
                // On error (e.g. stale version or completed rejection), reload to recover state
                loadTasksInternal(currentAuth.session)
                onError(errorMsg)
            }
        }
    }

    fun completeTask(
        taskId: String,
        completedAt: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        viewModelScope.launch {
            try {
                val completedTask = taskService.completeTask(currentAuth.session, taskId, completedAt)
                _selectedTask.value = completedTask
                loadTasksInternal(currentAuth.session)
                onSuccess()
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to complete task"
                loadTasksInternal(currentAuth.session)
                onError(errorMsg)
            }
        }
    }

    fun uncompleteTask(
        taskId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        viewModelScope.launch {
            try {
                val uncompletedTask = taskService.uncompleteTask(currentAuth.session, taskId)
                _selectedTask.value = uncompletedTask
                loadTasksInternal(currentAuth.session)
                onSuccess()
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to uncomplete task"
                loadTasksInternal(currentAuth.session)
                onError(errorMsg)
            }
        }
    }
}
