package com.cras.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CommentService
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.LabelService
import com.cras.app.data.SettingsService
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.UpcomingDayGroup
import com.cras.app.domain.filterCompletedTasks
import com.cras.app.domain.filterInboxTasks
import com.cras.app.domain.filterTodayTasks
import com.cras.app.domain.filterUpcomingTasks
import com.cras.app.models.Comment
import com.cras.app.models.Label
import com.cras.app.models.Plan
import com.cras.app.models.Task
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

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

sealed interface TodayUiState {
    object Loading : TodayUiState
    object Empty : TodayUiState
    data class Success(val tasks: List<Task>) : TodayUiState
    data class Error(val message: String) : TodayUiState
}

sealed interface UpcomingUiState {
    object Loading : UpcomingUiState
    object Empty : UpcomingUiState
    data class Success(val overdue: List<Task>, val groups: List<UpcomingDayGroup>) : UpcomingUiState
    data class Error(val message: String) : UpcomingUiState
}

sealed interface CompletedUiState {
    object Loading : CompletedUiState
    object Empty : CompletedUiState
    data class Success(val tasks: List<Task>) : CompletedUiState
    data class Error(val message: String) : CompletedUiState
}

class InboxViewModel(
    private val authService: AuthService,
    private val taskService: TaskService,
    private val labelService: LabelService,
    private val commentService: CommentService,
    private val settingsService: SettingsService? = null,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _allTasks = MutableStateFlow<List<Task>>(emptyList())
    val allTasks: StateFlow<List<Task>> = _allTasks.asStateFlow()

    private val _inboxState = MutableStateFlow<InboxUiState>(InboxUiState.Loading)
    val inboxState: StateFlow<InboxUiState> = _inboxState.asStateFlow()

    private val _todayState = MutableStateFlow<TodayUiState>(TodayUiState.Loading)
    val todayState: StateFlow<TodayUiState> = _todayState.asStateFlow()

    private val _upcomingState = MutableStateFlow<UpcomingUiState>(UpcomingUiState.Loading)
    val upcomingState: StateFlow<UpcomingUiState> = _upcomingState.asStateFlow()

    private val _completedState = MutableStateFlow<CompletedUiState>(CompletedUiState.Loading)
    val completedState: StateFlow<CompletedUiState> = _completedState.asStateFlow()

    private val _effectiveTimedPlanType = MutableStateFlow<TimedPlanType>(TimedPlanType.INSTANT)
    val effectiveTimedPlanType: StateFlow<TimedPlanType> = _effectiveTimedPlanType.asStateFlow()

    private val _labels = MutableStateFlow<List<Label>>(emptyList())
    val labels: StateFlow<List<Label>> = _labels.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _selectedTask = MutableStateFlow<Task?>(null)
    val selectedTask: StateFlow<Task?> = _selectedTask.asStateFlow()

    private val _isCreatingTask = MutableStateFlow(false)
    val isCreatingTask: StateFlow<Boolean> = _isCreatingTask.asStateFlow()

    private val _createTaskError = MutableStateFlow<String?>(null)
    val createTaskError: StateFlow<String?> = _createTaskError.asStateFlow()

    private val _isCreatingLabel = MutableStateFlow(false)
    val isCreatingLabel: StateFlow<Boolean> = _isCreatingLabel.asStateFlow()

    private val _createLabelError = MutableStateFlow<String?>(null)
    val createLabelError: StateFlow<String?> = _createLabelError.asStateFlow()

    init {
        viewModelScope.launch {
            authService.currentSession.collect { session ->
                if (session != null) {
                    _authState.value = AuthUiState.Authenticated(session)
                    loadTasksInternal(session)
                    loadSettingsInternal(session)
                } else {
                    _authState.value = AuthUiState.Unauthenticated()
                    _allTasks.value = emptyList()
                    _inboxState.value = InboxUiState.Empty
                    _todayState.value = TodayUiState.Empty
                    _upcomingState.value = UpcomingUiState.Empty
                    _completedState.value = CompletedUiState.Empty
                    _effectiveTimedPlanType.value = TimedPlanType.INSTANT
                    _labels.value = emptyList()
                    _comments.value = emptyList()
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

    private var loadJob: Job? = null

    fun loadTasks() {
        val currentAuth = _authState.value
        if (currentAuth is AuthUiState.Authenticated) {
            loadJob?.cancel()
            loadJob = viewModelScope.launch {
                loadTasksInternal(currentAuth.session)
                loadSettingsInternal(currentAuth.session)
            }
        }
    }

    private suspend fun loadSettingsInternal(session: OperatorSession) {
        if (settingsService == null) return
        try {
            val effective = settingsService.fetchEffectiveTimedPlanType(session)
            _effectiveTimedPlanType.value = effective
        } catch (_: Exception) {
            // Keep current value on error
        }
    }

    private suspend fun loadTasksInternal(session: OperatorSession) {
        if (_inboxState.value !is InboxUiState.Success) {
            _inboxState.value = InboxUiState.Loading
        }
        if (_todayState.value !is TodayUiState.Success) {
            _todayState.value = TodayUiState.Loading
        }
        if (_upcomingState.value !is UpcomingUiState.Success) {
            _upcomingState.value = UpcomingUiState.Loading
        }
        if (_completedState.value !is CompletedUiState.Success) {
            _completedState.value = CompletedUiState.Loading
        }
        try {
            val allTasksList = taskService.fetchTasks(session)
            val allLabels = labelService.fetchLabels(session)
            val commentsResult = runCatching { commentService.fetchComments(session) }
            _allTasks.value = allTasksList
            _labels.value = allLabels
            commentsResult.onSuccess { _comments.value = it }

            val now = nowProvider()
            val zoneId = zoneIdProvider()

            val inboxTasks = filterInboxTasks(allTasksList)
            val todayTasks = filterTodayTasks(allTasksList, now, zoneId)
            val upcomingResult = filterUpcomingTasks(allTasksList, now, zoneId)
            val completedTasks = filterCompletedTasks(allTasksList)

            _inboxState.value = if (inboxTasks.isEmpty()) {
                InboxUiState.Empty
            } else {
                InboxUiState.Success(inboxTasks)
            }

            _todayState.value = if (todayTasks.isEmpty()) {
                TodayUiState.Empty
            } else {
                TodayUiState.Success(todayTasks)
            }

            _upcomingState.value = if (upcomingResult.overdue.isEmpty() && upcomingResult.groups.isEmpty()) {
                UpcomingUiState.Empty
            } else {
                UpcomingUiState.Success(upcomingResult.overdue, upcomingResult.groups)
            }

            _completedState.value = if (completedTasks.isEmpty()) {
                CompletedUiState.Empty
            } else {
                CompletedUiState.Success(completedTasks)
            }

            // Refresh selectedTask if it's currently selected
            val currentSelected = _selectedTask.value
            if (currentSelected != null) {
                _selectedTask.value = allTasksList.find { it.id == currentSelected.id }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to load tasks"
            _inboxState.value = InboxUiState.Error(errorMsg)
            _todayState.value = TodayUiState.Error(errorMsg)
            _upcomingState.value = UpcomingUiState.Error(errorMsg)
            _completedState.value = CompletedUiState.Error(errorMsg)
        }
    }

    fun createLabel(
        name: String,
        color: String,
        onSuccess: (Label) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        viewModelScope.launch {
            _isCreatingLabel.value = true
            _createLabelError.value = null
            try {
                val created = labelService.createLabel(
                    session = currentAuth.session,
                    params = CreateLabelParams(name = name, color = color)
                )
                _labels.value = _labels.value + created
                onSuccess(created)
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to create label"
                _createLabelError.value = errorMsg
                onError(errorMsg)
            } finally {
                _isCreatingLabel.value = false
            }
        }
    }

    fun updateLabel(
        id: String,
        name: String? = null,
        color: String? = null,
        onSuccess: (Label) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        viewModelScope.launch {
            try {
                val updated = labelService.updateLabel(
                    session = currentAuth.session,
                    params = UpdateLabelParams(id = id, name = name, color = color)
                )
                _labels.value = _labels.value.map { if (it.id == id) updated else it }
                loadTasksInternal(currentAuth.session)
                onSuccess(updated)
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to update label"
                onError(errorMsg)
            }
        }
    }

    fun deleteLabel(
        id: String,
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
                labelService.deleteLabel(currentAuth.session, id)
                _labels.value = _labels.value.filterNot { it.id == id }
                loadTasksInternal(currentAuth.session)
                onSuccess()
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to delete label"
                onError(errorMsg)
            }
        }
    }

    fun createComment(
        taskId: String,
        content: String,
        onSuccess: (Comment) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val trimmedContent = content.trim()
        if (trimmedContent.isEmpty()) {
            onError("Comment content cannot be empty")
            return
        }

        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        viewModelScope.launch {
            try {
                val created = commentService.createComment(
                    session = currentAuth.session,
                    params = CreateCommentParams(taskId = taskId, content = trimmedContent)
                )
                _comments.value = _comments.value + created
                onSuccess(created)
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to create comment"
                onError(errorMsg)
            }
        }
    }

    fun createSubtask(
        parentId: String,
        title: String,
        onSuccess: (Task) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            onError("Task title cannot be empty")
            return
        }

        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        val parentTask = _allTasks.value.find { it.id == parentId }
        if (parentTask != null && parentTask.parentId != null) {
            onError("Subtasks cannot have children (one-level nesting only)")
            return
        }

        viewModelScope.launch {
            try {
                val created = taskService.createTask(
                    session = currentAuth.session,
                    params = CreateTaskParams(
                        title = trimmedTitle,
                        parentId = parentId
                    )
                )
                loadTasksInternal(currentAuth.session)
                onSuccess(created)
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to create subtask"
                onError(errorMsg)
            }
        }
    }

    fun createTask(
        title: String,
        description: String? = null,
        priority: Int = 4,
        labels: List<String> = emptyList(),
        plan: Plan? = null,
        onSuccess: () -> Unit = {}
    ) {
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
                        priority = priority,
                        plan = plan,
                        labels = labels
                    )
                )
                loadTasksInternal(currentAuth.session)
                onSuccess()
            } catch (e: Exception) {
                _createTaskError.value = e.message ?: "Failed to create task"
            } finally {
                _isCreatingTask.value = false
            }
        }
    }

    private fun mutateTask(
        defaultError: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        mutation: suspend (OperatorSession) -> Task
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        viewModelScope.launch {
            try {
                _selectedTask.value = mutation(currentAuth.session)
                loadTasksInternal(currentAuth.session)
                onSuccess()
            } catch (e: Exception) {
                val errorMsg = e.message ?: defaultError
                // Reload to recover canonical state after a conflict or a rejection.
                loadTasksInternal(currentAuth.session)
                onError(errorMsg)
            }
        }
    }

    fun updateTask(
        params: UpdateTaskParams,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) = mutateTask("Failed to update task", onSuccess, onError) { session ->
        taskService.updateTask(session, params)
    }

    fun completeTask(
        taskId: String,
        completedAt: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) = mutateTask("Failed to complete task", onSuccess, onError) { session ->
        taskService.completeTask(session, taskId, completedAt)
    }

    fun uncompleteTask(
        taskId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) = mutateTask("Failed to uncomplete task", onSuccess, onError) { session ->
        taskService.uncompleteTask(session, taskId)
    }

    fun updateOperatorTimedPlanType(
        type: TimedPlanType?,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated || settingsService == null) {
            onError("Not authenticated or settings unavailable")
            return
        }

        viewModelScope.launch {
            try {
                settingsService.updateOperatorTimedPlanType(currentAuth.session, type)
                val effective = settingsService.fetchEffectiveTimedPlanType(currentAuth.session)
                _effectiveTimedPlanType.value = effective
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update default timed plan type")
            }
        }
    }
}
