package com.cras.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.TaskService
import com.cras.app.domain.filterInboxTasks
import com.cras.app.models.Task
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthUiState {
    object Loading : AuthUiState
    object Unauthenticated : AuthUiState
    data class Authenticated(val session: OperatorSession) : AuthUiState
}

sealed interface InboxUiState {
    object Loading : InboxUiState
    object Empty : InboxUiState
    data class Success(val tasks: List<Task>) : InboxUiState
    data class Error(val message: String) : InboxUiState
}

class InboxViewModel(
    private val authService: AuthService,
    private val taskService: TaskService
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _inboxState = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val inboxState: StateFlow<InboxUiState> = _inboxState.asStateFlow()

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
                    _authState.value = AuthUiState.Unauthenticated
                    _inboxState.value = InboxUiState.Empty
                }
            }
        }

        viewModelScope.launch {
            authService.restoreSession()
        }
    }

    fun signInWithGoogleIdToken(idToken: String, nonce: String? = null) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            try {
                authService.signInWithGoogleIdToken(idToken, nonce)
            } catch (e: Exception) {
                _authState.value = AuthUiState.Unauthenticated
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
        try {
            val allTasks = taskService.fetchTasks(session)
            val inboxTasks = filterInboxTasks(allTasks)
            _inboxState.value = if (inboxTasks.isEmpty()) {
                InboxUiState.Empty
            } else {
                InboxUiState.Success(inboxTasks)
            }
        } catch (e: Exception) {
            _inboxState.value = InboxUiState.Error(e.message ?: "Failed to load tasks")
        }
    }

    fun createTask(title: String) {
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
                    params = CreateTaskParams(title = trimmed)
                )
                // Reload tasks to reflect persisted state
                loadTasksInternal(currentAuth.session)
            } catch (e: Exception) {
                _createTaskError.value = e.message ?: "Failed to create task"
            } finally {
                _isCreatingTask.value = false
            }
        }
    }
}
