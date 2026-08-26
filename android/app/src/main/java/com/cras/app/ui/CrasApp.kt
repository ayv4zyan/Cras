package com.cras.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cras.app.auth.OperatorSession
import com.cras.app.data.AccountDeletionState
import com.cras.app.data.UpdateTaskParams
import com.cras.app.domain.TimedPlanType
import com.cras.app.domain.filterSubtasks
import com.cras.app.models.Label
import com.cras.app.models.Task
import com.cras.app.quickaccess.DeepLinkAction
import com.cras.app.ui.account.AccountDeletionDialog
import com.cras.app.ui.account.DeletionFlowStep
import com.cras.app.ui.account.FrozenAccountScreen
import com.cras.app.ui.auth.SignInScreen
import com.cras.app.ui.completed.CompletedScreen
import com.cras.app.ui.detail.TaskDetailDialog
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.InboxScreen
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.labels.LabelManagerDialog
import com.cras.app.ui.settings.SettingsDialog
import com.cras.app.ui.today.TodayScreen
import com.cras.app.ui.upcoming.UpcomingScreen
import com.cras.app.ui.voice.VoiceCaptureDialog
import com.cras.app.ui.voice.VoiceViewModel
import kotlinx.coroutines.launch

private enum class AppView(
    val title: String,
    val icon: ImageVector
) {
    INBOX("Inbox", Icons.Default.Inbox),
    TODAY("Today", Icons.Default.CalendarToday),
    UPCOMING("Upcoming", Icons.Default.CalendarMonth),
    COMPLETED("Completed", Icons.Default.CheckCircleOutline)
}

@Composable
fun CrasApp(
    viewModel: InboxViewModel,
    onGoogleSignInRequested: (() -> Unit)? = null,
    onGoogleReauthRequested: (((Boolean, String?) -> Unit) -> Unit)? = null,
    onExportDataReady: ((String, (Boolean, String?) -> Unit) -> Unit)? = null,
    voiceViewModel: VoiceViewModel? = null,
    onRequestMicPermission: (() -> Unit)? = null,
    /**
     * A one-shot deep-link action from a Launchpad button, Shortcut, or
     * Today Glance widget tap. Consumed in a [LaunchedEffect] to switch the
     * active view or open a dialog; null means no pending action.
     */
    pendingDeepLinkAction: DeepLinkAction? = null,
    /** Notifies the host that [pendingDeepLinkAction] has been consumed. */
    onDeepLinkConsumed: () -> Unit = {}
) {
    val authState by viewModel.authState.collectAsState()
    val accountStatus by viewModel.accountStatus.collectAsState()
    val inboxState by viewModel.inboxState.collectAsState()
    val todayState by viewModel.todayState.collectAsState()
    val upcomingState by viewModel.upcomingState.collectAsState()
    val completedState by viewModel.completedState.collectAsState()
    val operatorTimedPlanType by viewModel.operatorTimedPlanType.collectAsState()
    val effectiveTimedPlanType by viewModel.effectiveTimedPlanType.collectAsState()
    val labels by viewModel.labels.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val allTasks by viewModel.allTasks.collectAsState()
    val selectedTask by viewModel.selectedTask.collectAsState()
    val isCreatingTask by viewModel.isCreatingTask.collectAsState()
    val createTaskError by viewModel.createTaskError.collectAsState()

    var currentView by remember { mutableStateOf(AppView.INBOX) }
    var isLabelManagerOpen by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isAccountDeletionOpen by remember { mutableStateOf(false) }
    var accountDeletionStep by remember { mutableStateOf(DeletionFlowStep.OVERVIEW) }
    var isRecovering by remember { mutableStateOf(false) }
    var recoveryErrorMessage by remember { mutableStateOf<String?>(null) }
    var isVoiceCaptureOpen by remember { mutableStateOf(false) }
    var isCreateFocused by remember { mutableStateOf(false) }
    var voiceFocusedTask by remember { mutableStateOf<Task?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Consume a pending deep-link action once: navigate to the correct view
    // or open the appropriate dialog/input. Retried when authentication resolves.
    LaunchedEffect(pendingDeepLinkAction, authState) {
        if (pendingDeepLinkAction != null && authState is AuthUiState.Authenticated) {
            when (pendingDeepLinkAction) {
                is DeepLinkAction.OpenToday -> currentView = AppView.TODAY
                is DeepLinkAction.OpenUpcoming -> currentView = AppView.UPCOMING
                is DeepLinkAction.OpenVoice -> {
                    voiceFocusedTask = null
                    voiceViewModel?.open(null)
                    isVoiceCaptureOpen = true
                }
                is DeepLinkAction.OpenCreate -> {
                    currentView = AppView.INBOX
                    isCreateFocused = true
                }
                is DeepLinkAction.OpenTask -> {
                    viewModel.focusRoutedTask(pendingDeepLinkAction.taskId)
                }
                is DeepLinkAction.CompleteTask -> {
                    viewModel.completeRoutedTask(pendingDeepLinkAction.taskId)
                }
            }
            onDeepLinkConsumed()
        }
    }

    when (val state = authState) {
        is AuthUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is AuthUiState.Unauthenticated -> {
            SignInScreen(
                onSignInClick = {
                    if (onGoogleSignInRequested != null) {
                        onGoogleSignInRequested()
                    } else {
                        viewModel.signInWithGoogleIdToken("demo-google-id-token")
                    }
                },
                errorMessage = state.errorMessage
            )
        }

        is AuthUiState.Authenticated -> {
            if (accountStatus?.deletionState == AccountDeletionState.PENDING_DELETION) {
                FrozenAccountScreen(
                    userEmail = state.session.email,
                    deletionDeadline = accountStatus?.deletionDeadline,
                    recoveryAvailable = accountStatus?.recoveryAvailable ?: false,
                    isRecovering = isRecovering,
                    errorMessage = recoveryErrorMessage,
                    onRecover = {
                        isRecovering = true
                        recoveryErrorMessage = null
                        viewModel.recoverAccount(
                            onSuccess = {
                                isRecovering = false
                            },
                            onError = { error ->
                                isRecovering = false
                                recoveryErrorMessage = error
                            }
                        )
                    },
                    onSignOut = {
                        viewModel.signOut()
                    }
                )
                return
            }

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        AppView.entries.forEach { view ->
                            NavigationBarItem(
                                selected = currentView == view,
                                onClick = { currentView = view },
                                icon = { Icon(view.icon, contentDescription = view.title) },
                                label = { Text(view.title) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentView) {
                        AppView.INBOX -> {
                            InboxScreen(
                                session = state.session,
                                inboxState = inboxState,
                                labels = labels,
                                effectiveDefault = effectiveTimedPlanType,
                                isCreatingTask = isCreatingTask,
                                createTaskError = createTaskError,
                                isCreateFocused = isCreateFocused,
                                onFocusCreateHandled = { isCreateFocused = false },
                                onCreateTask = { title, description, priority, taskLabels, plan, onSuccess ->
                                    viewModel.createTask(title, description, priority, taskLabels, plan, onSuccess = onSuccess)
                                },
                                onCompleteTask = { taskId ->
                                    viewModel.completeTask(
                                        taskId = taskId,
                                        onError = { errorMsg ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMsg)
                                            }
                                        }
                                    )
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
                                onOpenSettings = { isSettingsOpen = true },
                                onStartVoiceCapture = {
                                    voiceFocusedTask = null
                                    voiceViewModel?.open(null)
                                    isVoiceCaptureOpen = true
                                },
                                onRefresh = { viewModel.loadTasks() },
                                onSignOut = { viewModel.signOut() }
                            )
                        }

                        AppView.TODAY -> {
                            TodayScreen(
                                session = state.session,
                                todayState = todayState,
                                labels = labels,
                                effectiveDefault = effectiveTimedPlanType,
                                isCreatingTask = isCreatingTask,
                                createTaskError = createTaskError,
                                onCreateTask = { title, description, priority, taskLabels, plan, onSuccess ->
                                    viewModel.createTask(title, description, priority, taskLabels, plan, onSuccess = onSuccess)
                                },
                                onCompleteTask = { taskId ->
                                    viewModel.completeTask(
                                        taskId = taskId,
                                        onError = { errorMsg ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMsg)
                                            }
                                        }
                                    )
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
                                onOpenSettings = { isSettingsOpen = true },
                                onRefresh = { viewModel.loadTasks() },
                                onSignOut = { viewModel.signOut() }
                            )
                        }

                        AppView.UPCOMING -> {
                            UpcomingScreen(
                                session = state.session,
                                upcomingState = upcomingState,
                                labels = labels,
                                onCompleteTask = { taskId ->
                                    viewModel.completeTask(
                                        taskId = taskId,
                                        onError = { errorMsg ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMsg)
                                            }
                                        }
                                    )
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
                                onOpenSettings = { isSettingsOpen = true },
                                onRefresh = { viewModel.loadTasks() },
                                onSignOut = { viewModel.signOut() }
                            )
                        }

                        AppView.COMPLETED -> {
                            CompletedScreen(
                                session = state.session,
                                completedState = completedState,
                                labels = labels,
                                onUncompleteTask = { taskId ->
                                    viewModel.uncompleteTask(
                                        taskId = taskId,
                                        onError = { errorMsg ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMsg)
                                            }
                                        }
                                    )
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
                                onOpenSettings = { isSettingsOpen = true },
                                onRefresh = { viewModel.loadTasks() },
                                onSignOut = { viewModel.signOut() }
                            )
                        }
                    }

                    if (isSettingsOpen) {
                        SettingsDialog(
                            userEmail = state.session.email,
                            operatorTimedPlanType = operatorTimedPlanType,
                            effectiveDefaultTimedPlanType = effectiveTimedPlanType,
                            onDismiss = { isSettingsOpen = false },
                            onTimedPlanTypeChanged = { type ->
                                viewModel.updateOperatorTimedPlanType(type)
                            },
                            onDeleteAccountRequested = {
                                isSettingsOpen = false
                                accountDeletionStep = DeletionFlowStep.OVERVIEW
                                isAccountDeletionOpen = true
                            }
                        )
                    }

                    if (isAccountDeletionOpen) {
                        AccountDeletionDialog(
                            userEmail = state.session.email,
                            initialStep = accountDeletionStep,
                            onDismiss = { isAccountDeletionOpen = false },
                            onDownloadExport = { onSuccess, onError ->
                                viewModel.exportOperatorData(
                                    onSuccess = { json ->
                                        if (onExportDataReady != null) {
                                            onExportDataReady(json) { success, errorMsg ->
                                                if (success) {
                                                    onSuccess()
                                                } else {
                                                    onError(errorMsg ?: "Failed to export or save data")
                                                }
                                            }
                                        } else {
                                            onSuccess()
                                        }
                                    },
                                    onError = { error ->
                                        onError(error)
                                    }
                                )
                            },
                            onReauthenticate = {
                                if (onGoogleReauthRequested != null) {
                                    onGoogleReauthRequested { success, errorMsg ->
                                        if (success) {
                                            accountDeletionStep = DeletionFlowStep.CONFIRM
                                        } else if (errorMsg != null) {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMsg)
                                            }
                                        }
                                    }
                                } else {
                                    accountDeletionStep = DeletionFlowStep.CONFIRM
                                }
                            },
                            onConfirmDeletion = { onSuccess, onError ->
                                viewModel.requestAccountDeletion(
                                    onSuccess = { confirmation ->
                                        isAccountDeletionOpen = false
                                        onSuccess()
                                    },
                                    onError = { error ->
                                        onError(error)
                                    }
                                )
                            }
                        )
                    }

                    if (selectedTask != null) {
                        val currentTaskId = selectedTask!!.id
                        val taskComments = remember(comments, currentTaskId) {
                            comments.filter { it.taskId == currentTaskId }
                        }
                        val taskSubtasks = remember(allTasks, currentTaskId) {
                            filterSubtasks(allTasks, currentTaskId)
                        }

                        TaskDetailDialog(
                            task = selectedTask,
                            availableLabels = labels,
                            comments = taskComments,
                            subtasks = taskSubtasks,
                            effectiveDefault = effectiveTimedPlanType,
                            onDismiss = { viewModel.selectTask(null) },
                            onSave = { params, onSuccess, onError ->
                                viewModel.updateTask(params, onSuccess, onError)
                            },
                            onComplete = { taskId, completedAt, onSuccess, onError ->
                                viewModel.completeTask(taskId, expectedVersion = selectedTask?.version, completedAt = completedAt, onSuccess = onSuccess, onError = onError)
                            },
                            onUncomplete = { taskId, onSuccess, onError ->
                                viewModel.uncompleteTask(taskId, expectedVersion = selectedTask?.version, onSuccess = onSuccess, onError = onError)
                            },
                            onAddComment = { taskId, content, onSuccess, onError ->
                                viewModel.createComment(taskId, content, onSuccess = { onSuccess() }, onError = onError)
                            },
                            onCreateSubtask = { parentId, title, onSuccess, onError ->
                                viewModel.createSubtask(parentId, title, onSuccess = { onSuccess() }, onError = onError)
                            },
                            onSelectSubtask = { subtask ->
                                viewModel.selectTask(subtask)
                            },
                            onVoiceEdit = {
                                voiceFocusedTask = selectedTask
                                voiceViewModel?.open(selectedTask)
                                isVoiceCaptureOpen = true
                            }
                        )
                    }

                    if (isLabelManagerOpen) {
                        LabelManagerDialog(
                            labels = labels,
                            onDismiss = { isLabelManagerOpen = false },
                            onCreateLabel = { name, color, onSuccess, onError ->
                                viewModel.createLabel(name, color, onSuccess, onError)
                            },
                            onUpdateLabel = { id, name, color, onSuccess, onError ->
                                viewModel.updateLabel(id, name, color, onSuccess, onError)
                            },
                            onDeleteLabel = { id, onSuccess, onError ->
                                viewModel.deleteLabel(id, onSuccess, onError)
                            }
                        )
                    }

                    if (isVoiceCaptureOpen && voiceViewModel != null) {
                        val voiceState by voiceViewModel.uiState.collectAsState()
                        val retainedRecordings by voiceViewModel.retainedRecordings.collectAsState()
                        VoiceCaptureDialog(
                            uiState = voiceState,
                            effectiveDefault = effectiveTimedPlanType,
                            retainedRecordings = retainedRecordings,
                            focusedTaskTitle = voiceFocusedTask?.title,
                            onStartRecording = { voiceViewModel.startRecording() },
                            onStopAndProcess = { voiceViewModel.stopAndProcess() },
                            onCancelRecording = { voiceViewModel.cancelRecording() },
                            onRetryProcessing = { voiceViewModel.retryProcessing() },
                            onCorrectByVoice = { voiceViewModel.correctByVoice() },
                            onStartOver = { voiceViewModel.startOver() },
                            onDraftChange = { index, draft -> voiceViewModel.replaceDraft(index, draft) },
                            onSwitchDraftPlanType = { index, type ->
                                voiceViewModel.switchDraftPlanType(index, type)
                            },
                            onRemoveDraft = { index -> voiceViewModel.removeDraft(index) },
                            onDeleteRetained = { id -> voiceViewModel.deleteRetainedRecording(id) },
                            onDeleteAllRetained = { voiceViewModel.deleteAllRetainedRecordings() },
                            onRequestMicPermission = {
                                val request = onRequestMicPermission
                                if (request != null) {
                                    request()
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Microphone permission unavailable")
                                    }
                                }
                            },
                            onAcceptCreate = { drafts ->
                                drafts.forEach { draft ->
                                    viewModel.createTask(
                                        title = draft.title,
                                        description = draft.description,
                                        priority = draft.priority,
                                        plan = draft.plan,
                                        onError = { errorMsg ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMsg)
                                            }
                                        }
                                    )
                                }
                                isVoiceCaptureOpen = false
                                voiceViewModel.close()
                            },
                            onAcceptEdit = { draft ->
                                val focused = voiceFocusedTask
                                if (focused != null) {
                                    viewModel.updateTask(
                                        params = UpdateTaskParams(
                                            id = focused.id,
                                            title = draft.title.takeIf { it != focused.title },
                                            description = draft.description,
                                            clearDescription =
                                                draft.description == null && focused.description != null,
                                            priority = draft.priority.takeIf { it != focused.priority },
                                            plan = draft.plan?.takeIf { it != focused.plan },
                                            clearPlan = draft.plan == null && focused.plan != null,
                                            expectedVersion = focused.version
                                        ),
                                        onError = { errorMsg ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(errorMsg)
                                            }
                                        }
                                    )
                                }
                                isVoiceCaptureOpen = false
                                voiceViewModel.close()
                            },
                            onDismiss = {
                                isVoiceCaptureOpen = false
                                voiceViewModel.close()
                            }
                        )
                    }
                }
            }
        }
    }
}
