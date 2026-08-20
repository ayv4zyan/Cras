package com.cras.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.CommentService
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.InvalidationPayload
import com.cras.app.data.LabelService
import com.cras.app.data.RealtimeService
import com.cras.app.data.RealtimeSubscription
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val realtimeService: RealtimeService? = null,
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

    private val _commentsError = MutableStateFlow<String?>(null)
    val commentsError: StateFlow<String?> = _commentsError.asStateFlow()

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

    private var realtimeSubscription: RealtimeSubscription? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            authService.currentSession.collect { session ->
                loadJob?.cancel()
                loadJob = null
                realtimeSubscription?.unsubscribe()
                realtimeSubscription = null

                if (session != null) {
                    _authState.value = AuthUiState.Authenticated(session)
                    loadJob = viewModelScope.launch {
                        loadTasksInternal(session)
                        loadSettingsInternal(session)
                    }

                    realtimeSubscription = realtimeService?.subscribeToInvalidations(
                        session = session,
                        onInvalidate = { payload ->
                            handleInvalidationEvent(session, payload)
                        },
                        onReconnect = {
                            val currentAuth = _authState.value
                            if (currentAuth is AuthUiState.Authenticated && currentAuth.session == session) {
                                triggerLoadTasks(session)
                            }
                        }
                    )
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
                    _commentsError.value = null
                    _selectedTask.value = null
                }
            }
        }

        viewModelScope.launch {
            authService.restoreSession()
        }
    }

    override fun onCleared() {
        super.onCleared()
        realtimeSubscription?.unsubscribe()
        realtimeSubscription = null
    }

    fun selectTask(task: Task?) {
        _selectedTask.value = task
    }

    fun signInWithGoogleIdToken(idToken: String, nonce: String? = null) {
        viewModelScope.launch {
            _authState.value = AuthUiState.Loading
            try {
                authService.signInWithGoogleIdToken(idToken, nonce)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _authState.value = AuthUiState.Unauthenticated(e.message ?: "Sign in failed")
            }
        }
    }

    fun signOut() {
        loadJob?.cancel()
        loadJob = null
        realtimeSubscription?.unsubscribe()
        realtimeSubscription = null
        viewModelScope.launch {
            authService.signOut()
        }
    }

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

    private fun triggerLoadTasks(session: OperatorSession) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadTasksInternal(session)
        }
    }

    private fun recalculateViews(tasks: List<Task>) {
        val now = nowProvider()
        val zoneId = zoneIdProvider()

        val inboxTasks = filterInboxTasks(tasks)
        val todayTasks = filterTodayTasks(tasks, now, zoneId)
        val upcomingResult = filterUpcomingTasks(tasks, now, zoneId)
        val completedTasks = filterCompletedTasks(tasks)

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
    }

    fun applyTaskUpdate(updated: Task) {
        _allTasks.update { current ->
            val exists = current.any { it.id == updated.id }
            if (!exists) {
                listOf(updated) + current
            } else {
                current.map { t ->
                    if (t.id == updated.id) {
                        if (t.version > updated.version) t else updated
                    } else {
                        t
                    }
                }
            }
        }
        recalculateViews(_allTasks.value)

        _selectedTask.update { currentSelected ->
            if (currentSelected?.id == updated.id) {
                if (currentSelected.version > updated.version) currentSelected else updated
            } else {
                currentSelected
            }
        }
    }

    fun reconcileFreshTasks(freshTasks: List<Task>) {
        _allTasks.update { current ->
            val prevMap = current.associateBy { it.id }
            freshTasks.map { fresh ->
                val existing = prevMap[fresh.id]
                if (existing != null && existing.version > fresh.version) existing else fresh
            }
        }
        val reconciled = _allTasks.value
        recalculateViews(reconciled)

        _selectedTask.update { currentSelected ->
            if (currentSelected != null) {
                val freshSelected = reconciled.find { it.id == currentSelected.id }
                if (freshSelected != null) {
                    if (currentSelected.version > freshSelected.version) currentSelected else freshSelected
                } else {
                    null
                }
            } else {
                null
            }
        }
        if (_selectedTask.value == null) {
            _comments.value = emptyList()
        }
    }

    private fun handleInvalidationEvent(session: OperatorSession, event: InvalidationPayload) {
        viewModelScope.launch {
            val currentAuth = _authState.value
            if (currentAuth !is AuthUiState.Authenticated || currentAuth.session != session) return@launch

            when (event.resource) {
                "task" -> {
                    when (event.operation) {
                        "updated", "created" -> {
                            try {
                                val freshTask = taskService.fetchTaskById(session, event.id)
                                if (freshTask != null) {
                                    applyTaskUpdate(freshTask)
                                } else {
                                    val freshTasks = taskService.fetchTasks(session)
                                    reconcileFreshTasks(freshTasks)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                val freshTasks = runCatching { taskService.fetchTasks(session) }
                                    .onFailure { if (it is CancellationException) throw it }
                                    .getOrNull()
                                if (freshTasks != null) {
                                    reconcileFreshTasks(freshTasks)
                                }
                            }
                        }
                        "deleted" -> {
                            _allTasks.update { current ->
                                current.filterNot { it.id == event.id }
                            }
                            recalculateViews(_allTasks.value)

                            _selectedTask.update { currentSelected ->
                                if (currentSelected?.id == event.id) null else currentSelected
                            }
                            if (_selectedTask.value == null) {
                                _comments.value = emptyList()
                            }
                        }
                    }
                }
                "label" -> {
                    val freshLabels = runCatching { labelService.fetchLabels(session) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    if (freshLabels != null) {
                        _labels.value = freshLabels
                    }
                }
                "comment" -> {
                    val currentSelected = _selectedTask.value
                    if (currentSelected != null && (event.taskId == currentSelected.id || event.id == currentSelected.id)) {
                        val freshComments = runCatching { commentService.fetchComments(session) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                        if (freshComments != null) {
                            _comments.value = freshComments
                            _commentsError.value = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadSettingsInternal(session: OperatorSession) {
        if (settingsService == null) return
        try {
            val effective = settingsService.fetchEffectiveTimedPlanType(session)
            val currentAuth = _authState.value
            if (currentAuth is AuthUiState.Authenticated && currentAuth.session == session) {
                _effectiveTimedPlanType.value = effective
            }
        } catch (e: CancellationException) {
            throw e
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
            val labelsResult = runCatching { labelService.fetchLabels(session) }
                .onFailure { if (it is CancellationException) throw it }
            val commentsResult = runCatching { commentService.fetchComments(session) }
                .onFailure { if (it is CancellationException) throw it }

            val currentAuth = _authState.value
            if (currentAuth !is AuthUiState.Authenticated || currentAuth.session != session) {
                return
            }

            labelsResult.onSuccess { _labels.value = it }
            commentsResult.onSuccess {
                _comments.value = it
                _commentsError.value = null
            }.onFailure {
                _commentsError.value = it.message ?: "Failed to fetch comments"
            }

            reconcileFreshTasks(allTasksList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val currentAuth = _authState.value
            if (currentAuth !is AuthUiState.Authenticated || currentAuth.session != session) {
                return
            }
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
            } catch (e: CancellationException) {
                throw e
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
                triggerLoadTasks(currentAuth.session)
                onSuccess(updated)
            } catch (e: CancellationException) {
                throw e
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
                triggerLoadTasks(currentAuth.session)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
                triggerLoadTasks(currentAuth.session)
                onSuccess(created)
            } catch (e: CancellationException) {
                throw e
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
                val created = taskService.createTask(
                    session = currentAuth.session,
                    params = CreateTaskParams(
                        title = trimmed,
                        description = description?.trim()?.ifEmpty { null },
                        priority = priority,
                        plan = plan,
                        labels = labels
                    )
                )
                applyTaskUpdate(created)
                triggerLoadTasks(currentAuth.session)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
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
                val updatedTask = mutation(currentAuth.session)
                applyTaskUpdate(updatedTask)
                triggerLoadTasks(currentAuth.session)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errorMsg = e.message ?: defaultError
                // Reload canonical state after a conflict or error to avoid stale overwrites
                triggerLoadTasks(currentAuth.session)
                onError(errorMsg)
            }
        }
    }

    fun updateTask(
        params: UpdateTaskParams,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val effectiveParams = if (params.expectedVersion == null) {
            val currentVer = _allTasks.value.find { it.id == params.id }?.version
            params.copy(expectedVersion = currentVer)
        } else {
            params
        }
        mutateTask("Failed to update task", onSuccess, onError) { session ->
            taskService.updateTask(session, effectiveParams)
        }
    }

    fun completeTask(
        taskId: String,
        expectedVersion: Int? = null,
        completedAt: String? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val version = expectedVersion ?: _allTasks.value.find { it.id == taskId }?.version
        if (version == null) {
            onError("Task state is unavailable. Refresh and try again.")
            return
        }
        mutateTask("Failed to complete task", onSuccess, onError) { session ->
            taskService.completeTask(session, taskId, version, completedAt)
        }
    }

    fun uncompleteTask(
        taskId: String,
        expectedVersion: Int? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val version = expectedVersion ?: _allTasks.value.find { it.id == taskId }?.version
        if (version == null) {
            onError("Task state is unavailable. Refresh and try again.")
            return
        }
        mutateTask("Failed to uncomplete task", onSuccess, onError) { session ->
            taskService.uncompleteTask(session, taskId, version)
        }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Failed to update default timed plan type")
            }
        }
    }
}
