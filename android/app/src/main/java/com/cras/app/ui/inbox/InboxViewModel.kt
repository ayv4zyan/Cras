package com.cras.app.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cras.app.auth.AuthService
import com.cras.app.auth.OperatorSession
import com.cras.app.data.AccountDeletionState
import com.cras.app.data.AccountService
import com.cras.app.data.AccountStatus
import com.cras.app.data.CommentService
import com.cras.app.data.CreateCommentParams
import com.cras.app.data.CreateLabelParams
import com.cras.app.data.CreateTaskParams
import com.cras.app.data.DeletionConfirmation
import com.cras.app.data.InMemoryOutboxStore
import com.cras.app.data.InvalidationPayload
import com.cras.app.data.LabelService
import com.cras.app.data.OutboxDrainCallbacks
import com.cras.app.data.OutboxDrainer
import com.cras.app.data.OutboxItem
import com.cras.app.data.OutboxStore
import com.cras.app.data.RealtimeService
import com.cras.app.data.RealtimeSubscription
import com.cras.app.data.SettingsService
import com.cras.app.data.TaskService
import com.cras.app.data.UpdateLabelParams
import com.cras.app.data.UpdateTaskParams
import com.cras.app.data.applyOutboxToTasks
import com.cras.app.data.isNetworkError
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
import com.cras.app.notification.AndroidNotificationStatus
import com.cras.app.notification.NotificationInstallationSync
import com.cras.app.voice.VoiceRecordingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private sealed interface QueueItem {
    val session: OperatorSession

    data class Invalidation(
        override val session: OperatorSession,
        val payload: InvalidationPayload
    ) : QueueItem

    data class Reload(
        override val session: OperatorSession
    ) : QueueItem
}

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
    private val accountService: AccountService? = null,
    private val outboxStore: OutboxStore = InMemoryOutboxStore(),
    private val voiceRecordingStore: VoiceRecordingStore? = null,
    private val installationSync: NotificationInstallationSync? = null,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private val _accountStatus = MutableStateFlow<AccountStatus?>(null)
    val accountStatus: StateFlow<AccountStatus?> = _accountStatus.asStateFlow()

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

    private val _operatorTimedPlanType = MutableStateFlow<TimedPlanType?>(null)
    val operatorTimedPlanType: StateFlow<TimedPlanType?> = _operatorTimedPlanType.asStateFlow()

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

    private var routedTaskId: String? = null
    private var pendingCompletionTaskId: String? = null

    private val _isCreatingTask = MutableStateFlow(false)
    val isCreatingTask: StateFlow<Boolean> = _isCreatingTask.asStateFlow()

    private val _createTaskError = MutableStateFlow<String?>(null)
    val createTaskError: StateFlow<String?> = _createTaskError.asStateFlow()

    private val _isCreatingLabel = MutableStateFlow(false)
    val isCreatingLabel: StateFlow<Boolean> = _isCreatingLabel.asStateFlow()

    private val _createLabelError = MutableStateFlow<String?>(null)
    val createLabelError: StateFlow<String?> = _createLabelError.asStateFlow()

    private val outboxDrainer by lazy { OutboxDrainer(taskService, outboxStore) }

    private var realtimeSubscription: RealtimeSubscription? = null
    private val queue = ArrayDeque<QueueItem>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private val workerJob: Job

    init {
        workerJob = viewModelScope.launch {
            while (isActive) {
                val nextItem = synchronized(queue) {
                    if (queue.isNotEmpty()) queue.removeFirst() else null
                }
                if (nextItem != null) {
                    try {
                        processQueueItem(nextItem)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Guard loop against unexpected non-cancellation errors
                    }
                } else {
                    try {
                        queueSignal.receive()
                    } catch (_: ClosedReceiveChannelException) {
                        break
                    }
                }
            }
        }

        viewModelScope.launch {
            authService.currentSession.collect { session ->
                synchronized(queue) {
                    queue.clear()
                }
                realtimeSubscription?.unsubscribe()
                realtimeSubscription = null
                _accountStatus.value = null
                clearLocalDataInMemory()

                if (session != null) {
                    _authState.value = AuthUiState.Authenticated(session)
                    viewModelScope.launch {
                        checkAccountStatusInternal(session)
                    }
                } else {
                    _authState.value = AuthUiState.Unauthenticated()
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
        synchronized(queue) {
            queue.clear()
        }
        queueSignal.close()
        workerJob.cancel()
    }

    fun selectTask(task: Task?) {
        _selectedTask.value = task
    }

    /**
     * Opens the Task tapped through an Android Notification. When its data has
     * not arrived yet, the identity is retained and applied at the next
     * reconciliation.
     */
    fun focusRoutedTask(taskId: String) {
        if (!isAccountOperationAllowed()) return
        val match = _allTasks.value.firstOrNull { it.id == taskId }
        if (match != null) {
            routedTaskId = null
            _selectedTask.value = match
        } else {
            routedTaskId = taskId
        }
    }

    private fun consumePendingRoutedTask() {
        val pendingId = routedTaskId ?: return
        val match = _allTasks.value.firstOrNull { it.id == pendingId } ?: return
        routedTaskId = null
        _selectedTask.value = match
    }

    /**
     * Completes a Task requested through a widget action or deep-link. When
     * auth is still loading or the task data has not arrived yet, the request is
     * retained and executed once authenticated and the matching task is loaded.
     */
    fun completeRoutedTask(taskId: String) {
        if (!isAccountOperationAllowed()) return
        val currentAuth = _authState.value
        val match = _allTasks.value.firstOrNull { it.id == taskId }
        if (currentAuth is AuthUiState.Authenticated && match != null) {
            pendingCompletionTaskId = null
            completeTask(taskId = taskId)
        } else {
            pendingCompletionTaskId = taskId
        }
    }

    private fun consumePendingCompletionTask() {
        val pendingId = pendingCompletionTaskId ?: return
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) return
        val match = _allTasks.value.firstOrNull { it.id == pendingId } ?: return
        pendingCompletionTaskId = null
        completeTask(taskId = pendingId)
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
        synchronized(queue) {
            queue.clear()
        }
        realtimeSubscription?.unsubscribe()
        realtimeSubscription = null
        viewModelScope.launch {
            val currentAuth = _authState.value
            // Sign-out immediately disables the Operator-bound installation and
            // cancels its jobs before the session itself is cleared.
            if (currentAuth is AuthUiState.Authenticated && installationSync != null) {
                runCatching { installationSync.deactivateForSignOut(currentAuth.session) }
            }
            authService.signOut()
        }
    }

    fun loadTasks() {
        if (!isAccountOperationAllowed()) return
        val currentAuth = _authState.value
        if (currentAuth is AuthUiState.Authenticated) {
            triggerLoadTasks(currentAuth.session)
            viewModelScope.launch {
                loadSettingsInternal(currentAuth.session)
            }
        }
    }

    val installationStatus: StateFlow<AndroidNotificationStatus>?
        get() = installationSync?.status

    /**
     * Reconciles endpoint and permission state when the app resumes while a
     * session is active, per the shared installation lifecycle.
     */
    fun reconcileInstallation() {
        if (!isAccountOperationAllowed()) return
        val currentAuth = _authState.value
        if (currentAuth is AuthUiState.Authenticated) {
            installationSync?.let { sync ->
                viewModelScope.launch {
                    runCatching { sync.reconcile(currentAuth.session) }
                }
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        if (!isAccountOperationAllowed()) return
        installationSync ?: return
        val currentAuth = _authState.value
        viewModelScope.launch {
            runCatching {
                installationSync.setLocalEnabled(
                    enabled,
                    (currentAuth as? AuthUiState.Authenticated)?.session
                )
            }
        }
    }

    private fun triggerLoadTasks(session: OperatorSession) {
        if (!isAccountOperationAllowed()) return
        enqueueReload(session)
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
        if (!isAccountOperationAllowed()) return
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

        if (updated.id == pendingCompletionTaskId) {
            consumePendingCompletionTask()
        }
    }

    fun reconcileFreshTasks(freshTasks: List<Task>) {
        if (!isAccountOperationAllowed()) return
        val currentAuth = _authState.value
        val outbox = if (currentAuth is AuthUiState.Authenticated) {
            outboxStore.getOutbox(currentAuth.session.operatorId)
        } else {
            emptyList()
        }
        val tasksWithOutbox = applyOutboxToTasks(freshTasks, outbox)

        _allTasks.update { current ->
            val prevMap = current.associateBy { it.id }
            tasksWithOutbox.map { fresh ->
                val existing = prevMap[fresh.id]
                if (existing != null && existing.version > fresh.version) existing else fresh
            }
        }
        val reconciled = _allTasks.value
        recalculateViews(reconciled)

        val previousSelected = _selectedTask.value
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
        if (previousSelected != null && _selectedTask.value == null) {
            _comments.value = emptyList()
        }
        consumePendingRoutedTask()
        consumePendingCompletionTask()
    }

    private fun handleInvalidationEvent(session: OperatorSession, event: InvalidationPayload) {
        if (!isAccountOperationAllowed()) return
        enqueueInvalidation(session, event)
    }

    private suspend fun processQueueItem(item: QueueItem) {
        if (!isAccountOperationAllowed()) return
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated || currentAuth.session != item.session) {
            return
        }

        when (item) {
            is QueueItem.Invalidation -> {
                handleInvalidationEventInternal(item.session, item.payload)
            }
            is QueueItem.Reload -> {
                loadTasksInternal(item.session)
            }
        }
    }

    private fun enqueueInvalidation(session: OperatorSession, payload: InvalidationPayload) {
        if (!isAccountOperationAllowed()) return
        synchronized(queue) {
            val hasPendingReload = queue.any { it is QueueItem.Reload && it.session == session }
            if (hasPendingReload) {
                return
            }

            val existingIndex = queue.indexOfFirst { item ->
                item is QueueItem.Invalidation &&
                    item.session == session &&
                    item.payload.resource == payload.resource &&
                    (item.payload.resource == "label" || item.payload.id == payload.id)
            }

            if (existingIndex != -1) {
                queue[existingIndex] = QueueItem.Invalidation(session, payload)
            } else {
                if (queue.size >= MAX_QUEUE_CAPACITY) {
                    queue.removeAll { it.session == session }
                    queue.addLast(QueueItem.Reload(session))
                } else {
                    queue.addLast(QueueItem.Invalidation(session, payload))
                }
            }
        }
        queueSignal.trySend(Unit)
    }

    private fun enqueueReload(session: OperatorSession) {
        if (!isAccountOperationAllowed()) return
        synchronized(queue) {
            val alreadyHasReload = queue.any { it is QueueItem.Reload && it.session == session }
            if (!alreadyHasReload) {
                if (queue.size >= MAX_QUEUE_CAPACITY) {
                    queue.removeAll { it.session == session }
                }
                queue.addLast(QueueItem.Reload(session))
            }
        }
        queueSignal.trySend(Unit)
    }

    private suspend fun handleInvalidationEventInternal(session: OperatorSession, event: InvalidationPayload) {
        if (!isSessionActive(session)) return

        when (event.resource) {
            "task" -> {
                when (event.operation) {
                    "updated", "created" -> {
                        try {
                            val freshTask = taskService.fetchTaskById(session, event.id)
                            if (!isSessionActive(session)) return
                            if (freshTask != null) {
                                applyTaskUpdate(freshTask)
                            } else {
                                val freshTasks = taskService.fetchTasks(session)
                                if (!isSessionActive(session)) return
                                reconcileFreshTasks(freshTasks)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            val freshTasks = runCatching { taskService.fetchTasks(session) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                            if (!isSessionActive(session)) return
                            if (freshTasks != null) {
                                reconcileFreshTasks(freshTasks)
                            }
                        }
                    }
                    "deleted" -> {
                        if (!isSessionActive(session)) return
                        _allTasks.update { current ->
                            current.filterNot { it.id == event.id }
                        }
                        recalculateViews(_allTasks.value)

                        val previousSelected = _selectedTask.value
                        _selectedTask.update { currentSelected ->
                            if (currentSelected?.id == event.id) null else currentSelected
                        }
                        if (previousSelected != null && _selectedTask.value == null) {
                            _comments.value = emptyList()
                        }
                    }
                }
            }
            "label" -> {
                val freshLabels = runCatching { labelService.fetchLabels(session) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (!isSessionActive(session)) return
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
                    if (!isSessionActive(session)) return
                    if (freshComments != null) {
                        _comments.value = freshComments
                        _commentsError.value = null
                    }
                }
            }
        }
    }

    private suspend fun loadSettingsInternal(session: OperatorSession) {
        if (settingsService == null || !isSessionActive(session)) return
        try {
            val settings = settingsService.fetchOperatorSettings(session)
            val effective = settingsService.fetchEffectiveTimedPlanType(session)
            if (!isSessionActive(session)) {
                return
            }
            _operatorTimedPlanType.value = settings?.defaultTimedPlanType
            _effectiveTimedPlanType.value = effective
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep current value on error
        }
    }

    private suspend fun drainOutboxInternal(
        session: OperatorSession,
        onConflictError: ((String) -> Unit)? = null,
        onGeneralError: ((String) -> Unit)? = null,
        onNetworkError: ((String) -> Unit)? = null
    ) {
        if (!isSessionActive(session)) {
            return
        }

        outboxDrainer.drain(
            session = session,
            callbacks = object : OutboxDrainCallbacks {
                private fun isSessionValid(): Boolean {
                    return isSessionActive(session)
                }

                override suspend fun onTaskCreated(task: Task) {
                    if (!isSessionValid()) return
                    applyTaskUpdate(task)
                }

                override suspend fun onTaskCompleted(task: Task) {
                    if (!isSessionValid()) return
                    applyTaskUpdate(task)
                }

                override suspend fun onConflict(error: Throwable, item: OutboxItem) {
                    if (!isSessionValid()) return
                    val errorMsg = error.message ?: "Task version conflict"
                    if (item is OutboxItem.Create) {
                        _createTaskError.value = errorMsg
                    }
                    onConflictError?.invoke(errorMsg)
                    triggerLoadTasks(session)
                }

                override suspend fun onError(error: Throwable, item: OutboxItem) {
                    if (!isSessionValid()) return
                    val errorMsg = error.message ?: "Failed to process outbox item"
                    if (item is OutboxItem.Create) {
                        _allTasks.update { current -> current.filterNot { it.id == item.task.id } }
                        recalculateViews(_allTasks.value)
                        _createTaskError.value = errorMsg
                    }
                    onGeneralError?.invoke(errorMsg)
                    triggerLoadTasks(session)
                }

                override suspend fun onNetworkError(error: Throwable, item: OutboxItem) {
                    if (!isSessionValid() || onNetworkError == null) return
                    val errorMsg = error.message?.takeIf { it.isNotBlank() }
                        ?: "Network unavailable. The task is saved and will sync once you reconnect."
                    // The optimistic task and its outbox entry stay queued for
                    // the next successful drain; only the failure is surfaced.
                    if (item is OutboxItem.Create) {
                        _createTaskError.value = errorMsg
                    }
                    onNetworkError.invoke(errorMsg)
                }
            }
        )
    }

    private suspend fun loadTasksInternal(session: OperatorSession) {
        if (!isSessionActive(session)) return
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

            if (!isSessionActive(session)) {
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
            drainOutboxInternal(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isSessionActive(session)) {
                return
            }
            if (isNetworkError(e)) {
                val outbox = outboxStore.getOutbox(session.operatorId)
                if (outbox.isNotEmpty()) {
                    val tasksWithOutbox = applyOutboxToTasks(_allTasks.value, outbox)
                    _allTasks.value = tasksWithOutbox
                    recalculateViews(tasksWithOutbox)
                    return
                }
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }
        val session = currentAuth.session

        viewModelScope.launch {
            _isCreatingLabel.value = true
            _createLabelError.value = null
            try {
                val created = labelService.createLabel(
                    session = session,
                    params = CreateLabelParams(name = name, color = color)
                )
                if (!isSessionActive(session)) return@launch
                _labels.value = _labels.value + created
                onSuccess(created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionActive(session)) return@launch
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }
        val session = currentAuth.session

        viewModelScope.launch {
            try {
                val updated = labelService.updateLabel(
                    session = session,
                    params = UpdateLabelParams(id = id, name = name, color = color)
                )
                if (!isSessionActive(session)) return@launch
                _labels.value = _labels.value.map { if (it.id == id) updated else it }
                triggerLoadTasks(session)
                onSuccess(updated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionActive(session)) return@launch
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }
        val session = currentAuth.session

        viewModelScope.launch {
            try {
                labelService.deleteLabel(session, id)
                if (!isSessionActive(session)) return@launch
                _labels.value = _labels.value.filterNot { it.id == id }
                triggerLoadTasks(session)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionActive(session)) return@launch
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
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
        val session = currentAuth.session

        viewModelScope.launch {
            try {
                val created = commentService.createComment(
                    session = session,
                    params = CreateCommentParams(taskId = taskId, content = trimmedContent)
                )
                if (!isSessionActive(session)) return@launch
                _comments.value = _comments.value + created
                onSuccess(created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionActive(session)) return@launch
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
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

        val session = currentAuth.session
        val taskId = UUID.randomUUID().toString()
        val now = nowProvider().toString()

        val optimisticTask = Task(
            id = taskId,
            title = trimmedTitle,
            description = null,
            priority = 4,
            plan = null,
            labels = emptyList(),
            parentId = parentId,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            version = 1
        )

        val outboxParams = CreateTaskParams(
            id = taskId,
            title = trimmedTitle,
            parentId = parentId
        )

        val outboxItem = OutboxItem.Create(
            id = taskId,
            task = optimisticTask,
            params = outboxParams,
            createdAt = now
        )

        // 1. Enter persistent Outbox before network acknowledgement
        outboxStore.enqueue(session.operatorId, outboxItem)

        // 2. Apply optimistic update to local UI state
        applyTaskUpdate(optimisticTask)
        onSuccess(optimisticTask)

        // 3. Trigger drain
        viewModelScope.launch {
            drainOutboxInternal(session, onGeneralError = onError)
        }
    }

    fun createTask(
        title: String,
        description: String? = null,
        priority: Int = 4,
        labels: List<String> = emptyList(),
        plan: Plan? = null,
        onError: (String) -> Unit = {},
        onSuccess: () -> Unit = {}
    ) {
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return

        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) return

        val session = currentAuth.session
        val taskId = UUID.randomUUID().toString()
        val now = nowProvider().toString()
        val desc = description?.trim()?.ifEmpty { null }

        val optimisticTask = Task(
            id = taskId,
            title = trimmed,
            description = desc,
            priority = priority,
            plan = plan,
            labels = labels,
            parentId = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
            version = 1
        )

        val outboxParams = CreateTaskParams(
            id = taskId,
            title = trimmed,
            description = desc,
            priority = priority,
            plan = plan,
            parentId = null,
            labels = labels
        )

        val outboxItem = OutboxItem.Create(
            id = taskId,
            task = optimisticTask,
            params = outboxParams,
            createdAt = now
        )

        // 1. Enter persistent Outbox before network acknowledgement
        outboxStore.enqueue(session.operatorId, outboxItem)

        // 2. Apply optimistic update to local UI state
        applyTaskUpdate(optimisticTask)
        onSuccess()

        // 3. Trigger drain
        viewModelScope.launch {
            _isCreatingTask.value = true
            _createTaskError.value = null
            try {
                drainOutboxInternal(
                    session,
                    onGeneralError = onError,
                    onNetworkError = onError
                )
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }
        val session = currentAuth.session

        viewModelScope.launch {
            try {
                val updatedTask = mutation(session)
                if (!isSessionActive(session)) return@launch
                applyTaskUpdate(updatedTask)
                triggerLoadTasks(session)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionActive(session)) return@launch
                val errorMsg = e.message ?: defaultError
                // Reload canonical state after a conflict or error to avoid stale overwrites
                triggerLoadTasks(session)
                onError(errorMsg)
            }
        }
    }

    fun updateTask(
        params: UpdateTaskParams,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val version = params.expectedVersion ?: _allTasks.value.find { it.id == params.id }?.version
        if (version == null) {
            onError("Task state is unavailable. Refresh and try again.")
            return
        }
        val effectiveParams = if (params.expectedVersion == null) {
            params.copy(expectedVersion = version)
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated) {
            onError("Not authenticated")
            return
        }

        val session = currentAuth.session
        val task = _allTasks.value.find { it.id == taskId }
        val version = expectedVersion ?: task?.version
        if (version == null) {
            onError("Task state is unavailable. Refresh and try again.")
            return
        }

        val alreadyQueued = outboxStore.getOutbox(session.operatorId).any {
            it is OutboxItem.Complete && it.taskId == taskId
        }
        if (alreadyQueued) {
            onSuccess()
            return
        }

        val completedTimestamp = completedAt ?: nowProvider().toString()
        val outboxItemId = UUID.randomUUID().toString()

        val outboxItem = OutboxItem.Complete(
            id = outboxItemId,
            taskId = taskId,
            expectedVersion = version,
            completedAt = completedTimestamp,
            createdAt = nowProvider().toString()
        )

        // 1. Enter persistent Outbox before network acknowledgement
        outboxStore.enqueue(session.operatorId, outboxItem)

        // 2. Apply optimistic update to local UI state
        if (task != null) {
            val updatedTask = task.copy(
                completedAt = completedTimestamp,
                updatedAt = completedTimestamp
            )
            applyTaskUpdate(updatedTask)
        }
        onSuccess()

        // 3. Trigger drain
        viewModelScope.launch {
            drainOutboxInternal(
                session = session,
                onConflictError = onError,
                onGeneralError = onError,
                onNetworkError = onError
            )
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
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated || settingsService == null) {
            onError("Not authenticated or settings unavailable")
            return
        }
        val session = currentAuth.session

        viewModelScope.launch {
            try {
                settingsService.updateOperatorTimedPlanType(session, type)
                val effective = settingsService.fetchEffectiveTimedPlanType(session)
                if (!isSessionActive(session)) return@launch
                _operatorTimedPlanType.value = type
                _effectiveTimedPlanType.value = effective
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionActive(session)) return@launch
                onError(e.message ?: "Failed to update default timed plan type")
            }
        }
    }

    private fun startAuthenticatedSession(session: OperatorSession) {
        triggerLoadTasks(session)
        viewModelScope.launch {
            loadSettingsInternal(session)
        }
        installationSync?.let { sync ->
            viewModelScope.launch {
                runCatching { sync.reconcile(session) }
            }
        }

        realtimeSubscription?.unsubscribe()
        realtimeSubscription = realtimeService?.subscribeToInvalidations(
            session = session,
            onInvalidate = { payload ->
                handleInvalidationEvent(session, payload)
            },
            onReconnect = {
                viewModelScope.launch {
                    val auth = _authState.value
                    if (auth is AuthUiState.Authenticated && auth.session == session) {
                        triggerLoadTasks(session)
                    }
                }
            }
        )
    }

    private fun isSessionActive(session: OperatorSession): Boolean =
        isSessionCurrent(session) && isAccountOperationAllowed()

    private fun isSessionCurrent(session: OperatorSession): Boolean {
        val auth = _authState.value
        return auth is AuthUiState.Authenticated && auth.session == session && authService.currentSession.value == session
    }

    private fun isAccountPendingDeletion(): Boolean {
        return _accountStatus.value?.deletionState == AccountDeletionState.PENDING_DELETION
    }

    private fun isAccountStatusVerified(): Boolean {
        return accountService == null || _accountStatus.value != null
    }

    private fun isAccountOperationAllowed(): Boolean {
        return isAccountStatusVerified() && !isAccountPendingDeletion()
    }

    private suspend fun handlePendingDeletion(session: OperatorSession) {
        synchronized(queue) {
            queue.clear()
        }
        realtimeSubscription?.unsubscribe()
        realtimeSubscription = null
        clearLocalData(session.operatorId)
        installationSync?.let { sync ->
            runCatching { sync.deactivateForSignOut(session) }
        }
    }

    private suspend fun checkAccountStatusInternal(session: OperatorSession) {
        if (accountService == null) {
            if (!isSessionCurrent(session)) return
            startAuthenticatedSession(session)
            return
        }

        val status = try {
            accountService.fetchAccountStatus(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isSessionCurrent(session)) return
            val errorMsg = e.message ?: "Failed to verify account status"
            _inboxState.value = InboxUiState.Error(errorMsg)
            _todayState.value = TodayUiState.Error(errorMsg)
            _upcomingState.value = UpcomingUiState.Error(errorMsg)
            _completedState.value = CompletedUiState.Error(errorMsg)
            return
        }

        if (!isSessionCurrent(session)) return

        _accountStatus.value = status
        if (status.deletionState == AccountDeletionState.PENDING_DELETION) {
            handlePendingDeletion(session)
            return
        }

        startAuthenticatedSession(session)
    }

    fun fetchAccountStatus(
        onSuccess: (AccountStatus) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated || accountService == null) {
            return
        }
        viewModelScope.launch {
            try {
                val session = currentAuth.session
                val status = accountService.fetchAccountStatus(session)
                if (!isSessionCurrent(session)) {
                    return@launch
                }
                _accountStatus.value = status
                if (status.deletionState == AccountDeletionState.PENDING_DELETION) {
                    handlePendingDeletion(session)
                    if (!isSessionCurrent(session)) {
                        return@launch
                    }
                }
                onSuccess(status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionCurrent(currentAuth.session)) {
                    return@launch
                }
                onError(e.message ?: "Failed to fetch account status")
            }
        }
    }

    fun requestAccountDeletion(
        onSuccess: (DeletionConfirmation) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isAccountPendingDeletion()) {
            onError("Account deletion is pending")
            return
        }
        if (!isAccountStatusVerified()) {
            onError("Account verification in progress")
            return
        }
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated || accountService == null) {
            onError("Not authenticated or account service unavailable")
            return
        }

        viewModelScope.launch {
            try {
                val session = currentAuth.session
                val confirmation = accountService.requestAccountDeletion(session)
                if (!isSessionCurrent(session)) {
                    return@launch
                }
                if (!confirmation.confirmed) {
                    onError("Account deletion was not confirmed. Please try again.")
                    return@launch
                }
                _accountStatus.value = AccountStatus(
                    deletionState = AccountDeletionState.PENDING_DELETION,
                    deletionDeadline = confirmation.deletionDeadline,
                    recoveryAvailable = true
                )
                synchronized(queue) {
                    queue.clear()
                }
                realtimeSubscription?.unsubscribe()
                realtimeSubscription = null
                clearLocalData(session.operatorId)
                if (installationSync != null) {
                    runCatching { installationSync.deactivateForSignOut(session) }
                }
                if (!isSessionCurrent(session)) {
                    return@launch
                }
                authService.signOut()
                onSuccess(confirmation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionCurrent(currentAuth.session)) {
                    return@launch
                }
                onError(e.message ?: "Failed to request account deletion")
            }
        }
    }

    fun recoverAccount(
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated || accountService == null) {
            onError("Not authenticated or account service unavailable")
            return
        }

        viewModelScope.launch {
            try {
                val session = currentAuth.session
                accountService.recoverAccount(session)
                if (!isSessionCurrent(session)) {
                    return@launch
                }
                _accountStatus.value = AccountStatus(AccountDeletionState.ACTIVE, null, false)
                startAuthenticatedSession(session)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionCurrent(currentAuth.session)) {
                    return@launch
                }
                onError(e.message ?: "Failed to recover account")
            }
        }
    }

    fun exportOperatorData(
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentAuth = _authState.value
        if (currentAuth !is AuthUiState.Authenticated || accountService == null) {
            onError("Not authenticated or account service unavailable")
            return
        }

        viewModelScope.launch {
            try {
                val exportJson = accountService.exportOperatorData(currentAuth.session)
                if (!isSessionCurrent(currentAuth.session)) {
                    return@launch
                }
                onSuccess(exportJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isSessionCurrent(currentAuth.session)) {
                    return@launch
                }
                onError(e.message ?: "Failed to export data")
            }
        }
    }

    fun clearLocalData(operatorId: String) {
        outboxStore.clear(operatorId)
        voiceRecordingStore?.clearAll()
        clearLocalDataInMemory()
    }

    private fun clearLocalDataInMemory() {
        _allTasks.value = emptyList()
        _inboxState.value = InboxUiState.Empty
        _todayState.value = TodayUiState.Empty
        _upcomingState.value = UpcomingUiState.Empty
        _completedState.value = CompletedUiState.Empty
        _operatorTimedPlanType.value = null
        _effectiveTimedPlanType.value = TimedPlanType.INSTANT
        _labels.value = emptyList()
        _comments.value = emptyList()
        _commentsError.value = null
        _selectedTask.value = null
        _createTaskError.value = null
        _createLabelError.value = null
    }

    companion object {
        private const val MAX_QUEUE_CAPACITY = 64
    }
}
