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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cras.app.ui.auth.SignInScreen
import com.cras.app.domain.filterSubtasks
import com.cras.app.ui.completed.CompletedScreen
import com.cras.app.ui.detail.TaskDetailDialog
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.InboxScreen
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.labels.LabelManagerDialog
import com.cras.app.ui.today.TodayScreen
import com.cras.app.ui.upcoming.UpcomingScreen

enum class AppView(
    val title: String,
    val icon: ImageVector,
    val emptyIcon: ImageVector,
    val emptyMessage: String
) {
    INBOX("Inbox", Icons.Default.Inbox, Icons.Default.Inbox, "No tasks in Inbox"),
    TODAY("Today", Icons.Default.CalendarToday, Icons.Default.CalendarToday, "No tasks for Today"),
    UPCOMING("Upcoming", Icons.Default.CalendarMonth, Icons.Default.CalendarMonth, "No upcoming tasks"),
    COMPLETED("Completed", Icons.Default.CheckCircleOutline, Icons.Default.CheckCircleOutline, "No completed tasks")
}

@Composable
fun CrasApp(
    viewModel: InboxViewModel,
    onGoogleSignInRequested: (() -> Unit)? = null
) {
    val authState by viewModel.authState.collectAsState()
    val inboxState by viewModel.inboxState.collectAsState()
    val todayState by viewModel.todayState.collectAsState()
    val upcomingState by viewModel.upcomingState.collectAsState()
    val completedState by viewModel.completedState.collectAsState()
    val effectiveTimedPlanType by viewModel.effectiveTimedPlanType.collectAsState()
    val labels by viewModel.labels.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val allTasks by viewModel.allTasks.collectAsState()
    val selectedTask by viewModel.selectedTask.collectAsState()
    val isCreatingTask by viewModel.isCreatingTask.collectAsState()
    val createTaskError by viewModel.createTaskError.collectAsState()

    var currentView by remember { mutableStateOf(AppView.INBOX) }
    var isLabelManagerOpen by remember { mutableStateOf(false) }

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
            Scaffold(
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
                                onCreateTask = { title, description, priority, taskLabels, plan ->
                                    viewModel.createTask(title, description, priority, taskLabels, plan)
                                },
                                onCompleteTask = { taskId ->
                                    viewModel.completeTask(taskId)
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
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
                                onCreateTask = { title, description, priority, taskLabels, plan ->
                                    viewModel.createTask(title, description, priority, taskLabels, plan)
                                },
                                onCompleteTask = { taskId ->
                                    viewModel.completeTask(taskId)
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
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
                                    viewModel.completeTask(taskId)
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
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
                                    viewModel.uncompleteTask(taskId)
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onOpenLabelManager = { isLabelManagerOpen = true },
                                onRefresh = { viewModel.loadTasks() },
                                onSignOut = { viewModel.signOut() }
                            )
                        }
                    }

                    if (selectedTask != null) {
                        val currentTaskId = selectedTask!!.id
                        val taskComments = comments.filter { it.taskId == currentTaskId }
                        val taskSubtasks = filterSubtasks(allTasks, currentTaskId)

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
                                viewModel.completeTask(taskId, completedAt, onSuccess, onError)
                            },
                            onUncomplete = { taskId, onSuccess, onError ->
                                viewModel.uncompleteTask(taskId, onSuccess, onError)
                            },
                            onAddComment = { taskId, content, onSuccess, onError ->
                                viewModel.createComment(taskId, content, onSuccess = { onSuccess() }, onError = onError)
                            },
                            onCreateSubtask = { parentId, title, onSuccess, onError ->
                                viewModel.createSubtask(parentId, title, onSuccess = { onSuccess() }, onError = onError)
                            },
                            onSelectSubtask = { subtask ->
                                viewModel.selectTask(subtask)
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
                }
            }
        }
    }
}
