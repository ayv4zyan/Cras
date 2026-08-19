package com.cras.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cras.app.auth.SharedPreferencesSessionStore
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.getPublicSupabaseConfig
import com.cras.app.data.SupabaseTaskService
import com.cras.app.ui.auth.SignInScreen
import com.cras.app.ui.completed.CompletedScreen
import com.cras.app.ui.detail.TaskDetailDialog
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.InboxScreen
import com.cras.app.ui.inbox.InboxViewModel

enum class AppView(
    val title: String,
    val emptyMessage: String,
    val icon: ImageVector,
    val emptyIcon: ImageVector
) {
    INBOX(
        title = "Inbox",
        emptyMessage = "No tasks in Inbox",
        icon = Icons.Default.Inbox,
        emptyIcon = Icons.AutoMirrored.Filled.List
    ),
    TODAY(
        title = "Today",
        emptyMessage = "No tasks for Today",
        icon = Icons.Default.CalendarToday,
        emptyIcon = Icons.Default.CalendarToday
    ),
    UPCOMING(
        title = "Upcoming",
        emptyMessage = "No upcoming tasks",
        icon = Icons.Default.CalendarMonth,
        emptyIcon = Icons.Default.CalendarMonth
    ),
    COMPLETED(
        title = "Completed",
        emptyMessage = "No completed tasks yet",
        icon = Icons.Default.CheckCircle,
        emptyIcon = Icons.Default.CheckCircle
    )
}

@Composable
fun CrasApp(
    viewModel: InboxViewModel = run {
        val context = LocalContext.current
        viewModel {
            val config = getPublicSupabaseConfig()
            val prefs = context.getSharedPreferences("cras_session_prefs", android.content.Context.MODE_PRIVATE)
            val sessionStore = SharedPreferencesSessionStore(prefs)
            val authService = SupabaseAuthService(config, sessionStore)
            val taskService = SupabaseTaskService(config)
            InboxViewModel(authService, taskService)
        }
    },
    onGoogleSignInRequested: (() -> Unit)? = null
) {
    val authState by viewModel.authState.collectAsState()
    val inboxState by viewModel.inboxState.collectAsState()
    val completedState by viewModel.completedState.collectAsState()
    val selectedTask by viewModel.selectedTask.collectAsState()
    val isCreatingTask by viewModel.isCreatingTask.collectAsState()
    val createTaskError by viewModel.createTaskError.collectAsState()

    var currentView by remember { mutableStateOf(AppView.INBOX) }

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
                                isCreatingTask = isCreatingTask,
                                createTaskError = createTaskError,
                                onCreateTask = { title, description, priority ->
                                    viewModel.createTask(title, description, priority)
                                },
                                onCompleteTask = { taskId ->
                                    viewModel.completeTask(taskId)
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onRefresh = { viewModel.loadTasks() },
                                onSignOut = { viewModel.signOut() }
                            )
                        }

                        AppView.COMPLETED -> {
                            CompletedScreen(
                                session = state.session,
                                completedState = completedState,
                                onUncompleteTask = { taskId ->
                                    viewModel.uncompleteTask(taskId)
                                },
                                onSelectTask = { task ->
                                    viewModel.selectTask(task)
                                },
                                onRefresh = { viewModel.loadTasks() },
                                onSignOut = { viewModel.signOut() }
                            )
                        }

                        else -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = currentView.emptyIcon,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = currentView.emptyMessage,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Your task space is clear.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    if (selectedTask != null) {
                        TaskDetailDialog(
                            task = selectedTask,
                            onDismiss = { viewModel.selectTask(null) },
                            onSave = { params, onSuccess, onError ->
                                viewModel.updateTask(params, onSuccess, onError)
                            },
                            onComplete = { taskId, completedAt, onSuccess, onError ->
                                viewModel.completeTask(taskId, completedAt, onSuccess, onError)
                            },
                            onUncomplete = { taskId, onSuccess, onError ->
                                viewModel.uncompleteTask(taskId, onSuccess, onError)
                            }
                        )
                    }
                }
            }
        }
    }
}
