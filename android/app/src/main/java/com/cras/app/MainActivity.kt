package com.cras.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cras.app.auth.GoogleAuthManager
import com.cras.app.auth.GoogleSignInResult
import com.cras.app.auth.SharedPreferencesSessionStore
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.getPublicSupabaseConfig
import com.cras.app.data.SharedPreferencesOutboxStore
import com.cras.app.data.SupabaseCommentService
import com.cras.app.data.SupabaseInstallationService
import com.cras.app.data.SupabaseLabelService
import com.cras.app.data.SupabaseRealtimeService
import com.cras.app.data.SupabaseSettingsService
import com.cras.app.data.SupabaseTaskService
import com.cras.app.notification.CrasNotifications
import com.cras.app.notification.EXTRA_TASK_ID
import com.cras.app.notification.FcmTokenBus
import com.cras.app.notification.FirebaseFcmTokenProvider
import com.cras.app.notification.NotificationInstallationSync
import com.cras.app.notification.PlatformPermissionState
import com.cras.app.notification.SharedPreferencesNotificationPreferenceStore
import com.cras.app.quickaccess.DeepLinkAction
import com.cras.app.quickaccess.parseDeepLinkAction
import com.cras.app.ui.CrasApp
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.theme.CrasTheme
import com.cras.app.voice.DirectoryVoiceRecordingStore
import com.cras.app.voice.MicAudioRecorderFactory
import com.cras.app.voice.SupabaseVoiceCaptureApi
import com.cras.app.ui.voice.VoiceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

class MainActivity : ComponentActivity() {

    private val authOperationMutex = Mutex()
    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var inboxViewModel: InboxViewModel
    private lateinit var voiceViewModel: VoiceViewModel
    private var installationSync: NotificationInstallationSync? = null
    private lateinit var notificationPrefs: android.content.SharedPreferences

    /** One-shot pending deep-link action delivered as Compose state to [CrasApp]. */
    private var pendingDeepLinkAction by mutableStateOf<DeepLinkAction?>(null)

    companion object {
        private const val KEY_PERMISSION_ASKED = "cras_notifications_permission_asked"
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Both outcomes reconcile: a denial must reach the server as
            // permission_state = "denied", not stay parked as "prompt".
            inboxViewModel.reconcileInstallation()
        }

    private val requestMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The Voice dialog surfaces the outcome; the Operator retries capture.
        }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val config = getPublicSupabaseConfig(
            mapOf(
                "SUPABASE_URL" to BuildConfig.SUPABASE_URL,
                "SUPABASE_ANON_KEY" to BuildConfig.SUPABASE_ANON_KEY
            )
        )
        val prefs = getSharedPreferences("cras_session_prefs", MODE_PRIVATE)
        val sessionStore = SharedPreferencesSessionStore(prefs)
        val outboxStore = SharedPreferencesOutboxStore(prefs)
        notificationPrefs = getSharedPreferences("cras_notification_prefs", MODE_PRIVATE)
        val httpClient = OkHttpClient()
        val authService = SupabaseAuthService(config, sessionStore, httpClient)
        val taskService = SupabaseTaskService(config, httpClient)
        val labelService = SupabaseLabelService(config, httpClient)
        val commentService = SupabaseCommentService(config, httpClient)
        val settingsService = SupabaseSettingsService(config, httpClient)

        val sync = NotificationInstallationSync(
            installationService = SupabaseInstallationService(config, httpClient),
            preferences = SharedPreferencesNotificationPreferenceStore(notificationPrefs),
            permissionProvider = { currentPlatformPermissionState() },
            fcmTokenProvider = FirebaseFcmTokenProvider(applicationContext).resolve
        )
        installationSync = sync

        val realtimeHttpClient = httpClient.newBuilder()
            .pingInterval(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ZERO)
            .build()
        val realtimeService = SupabaseRealtimeService(config, realtimeHttpClient)

        val accountService = com.cras.app.data.SupabaseAccountService(config, httpClient)
        val voiceRecordingStore = DirectoryVoiceRecordingStore(
            File(applicationContext.filesDir, "voice-recordings")
        )

        inboxViewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            realtimeService = realtimeService,
            accountService = accountService,
            outboxStore = outboxStore,
            voiceRecordingStore = voiceRecordingStore,
            installationSync = sync
        )

        // Voice gets its own timeouts: a 4 MB upload plus server transcription
        // exceeds the shared client's ~10 s defaults.
        val voiceCaptureApi = SupabaseVoiceCaptureApi(
            config,
            SupabaseVoiceCaptureApi.voiceHttpClient(httpClient),
        )
        voiceViewModel = VoiceViewModel(
            authService = authService,
            voiceCaptureApi = voiceCaptureApi,
            recordingStore = voiceRecordingStore,
            micRecorderProvider = { MicAudioRecorderFactory.create() },
            effectiveDefaultTimedPlanTypeProvider = {
                inboxViewModel.effectiveTimedPlanType.value
            },
            micPermissionProvider = { hasMicrophonePermission() }
        )
        googleAuthManager = GoogleAuthManager(this)

        lifecycleScope.launch {
            FcmTokenBus.latestToken.collect { token ->
                val authState = inboxViewModel.authState.value
                val session = (authState as? AuthUiState.Authenticated)?.session
                runCatching { sync.onFcmTokenRotated(token, session) }
            }
        }

        // Retained recordings live in an install-wide directory: drop them the
        // moment an Operator signs out or switches so a different Operator
        // cannot replay earlier audio through Voice retry.
        lifecycleScope.launch {
            var lastAuthenticatedOperatorId: String? = null
            inboxViewModel.authState.collect { state ->
                when (state) {
                    is AuthUiState.Authenticated -> {
                        val currentOperatorId = state.session.operatorId
                        if (lastAuthenticatedOperatorId != null && lastAuthenticatedOperatorId != currentOperatorId) {
                            voiceViewModel.deleteAllRetainedRecordings()
                        }
                        lastAuthenticatedOperatorId = currentOperatorId
                    }
                    is AuthUiState.Unauthenticated -> {
                        if (lastAuthenticatedOperatorId != null) {
                            voiceViewModel.deleteAllRetainedRecordings()
                            lastAuthenticatedOperatorId = null
                        }
                    }
                    is AuthUiState.Loading -> {
                        // Maintain lastAuthenticatedOperatorId across transient loading
                        // states to ensure operator transitions are detected accurately.
                    }
                }
            }
        }

        // Push updated today rows to any home-screen Today Glance widgets whenever
        // the task list changes while the app is running. No-op when no widget
        // instances are pinned.
        lifecycleScope.launch {
            inboxViewModel.allTasks.collect { tasks ->
                runCatching {
                    val rows = com.cras.app.quickaccess.buildTodayGlanceRows(tasks)
                    com.cras.app.quickaccess.updateTodayWidgets(applicationContext, rows)
                }
            }
        }

        // Periodically refresh Today glance rows at the local-date boundary
        // from canonical task state, ensuring tasks planned for the new day
        // appear without requiring a task mutation.
        lifecycleScope.launch {
            while (true) {
                val now = java.time.ZonedDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                val millisUntilMidnight = java.time.Duration.between(now, nextMidnight).toMillis() + 50L
                kotlinx.coroutines.delay(millisUntilMidnight.coerceAtLeast(1000L))
                runCatching {
                    val tasks = inboxViewModel.allTasks.value
                    val rows = com.cras.app.quickaccess.buildTodayGlanceRows(tasks)
                    com.cras.app.quickaccess.updateTodayWidgets(applicationContext, rows)
                }
            }
        }

        setContent {
            CrasTheme {
                CrasApp(
                    viewModel = inboxViewModel,
                    voiceViewModel = voiceViewModel,
                    pendingDeepLinkAction = pendingDeepLinkAction,
                    onDeepLinkConsumed = { pendingDeepLinkAction = null },
                    onRequestMicPermission = {
                        requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onExportDataReady = { exportJson, callback ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val exportDir = File(cacheDir, "exports")
                                if (!exportDir.exists()) {
                                    exportDir.mkdirs()
                                }
                                val exportFile = File(exportDir, "cras-export.json")
                                exportFile.writeText(exportJson)
                                withContext(Dispatchers.Main) {
                                    try {
                                        val uri = FileProvider.getUriForFile(
                                            this@MainActivity,
                                            "${applicationContext.packageName}.fileprovider",
                                            exportFile
                                        )
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_TITLE, "cras-export.json")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        startActivity(Intent.createChooser(sendIntent, "Export Cras Operator Data"))
                                        callback(true, null)
                                    } catch (e: Exception) {
                                        callback(false, e.message ?: "Failed to share export data")
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    callback(false, e.message ?: "Failed to write export file")
                                }
                            }
                        }
                    },
                    onGoogleReauthRequested = { callback ->
                        lifecycleScope.launch {
                            val capturedAuth = inboxViewModel.authState.value
                            val capturedSession = (capturedAuth as? AuthUiState.Authenticated)?.session
                            if (capturedSession == null) {
                                callback(false, "Operator is not authenticated")
                                return@launch
                            }

                            when (val result = googleAuthManager.getGoogleIdToken()) {
                                is GoogleSignInResult.Success -> {
                                    authOperationMutex.withLock {
                                        val activeAuth = inboxViewModel.authState.value
                                        val activeSession = (activeAuth as? AuthUiState.Authenticated)?.session
                                        val authServiceSession = authService.currentSession.value

                                        if (activeSession == null ||
                                            activeSession != capturedSession ||
                                            authServiceSession != capturedSession
                                        ) {
                                            callback(false, "Authentication state changed during reauthentication")
                                            return@withLock
                                        }

                                        try {
                                            val newSession = authService.signInWithGoogleIdToken(result.idToken, result.nonce)
                                            val currentEmail = activeSession.email
                                            val currentOperatorId = activeSession.operatorId

                                            if (newSession.operatorId == currentOperatorId ||
                                                (currentEmail != null && newSession.email == currentEmail)
                                            ) {
                                                callback(true, null)
                                            } else {
                                                if (authService.currentSession.value == newSession) {
                                                    authService.restoreSession(activeSession)
                                                }
                                                callback(false, "Signed in with a different Google account. Please use the matching account.")
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            if (authService.currentSession.value != activeSession &&
                                                authService.currentSession.value != null
                                            ) {
                                                runCatching { authService.restoreSession(activeSession) }
                                            }
                                            callback(false, e.message ?: "Reauthentication failed")
                                        }
                                    }
                                }
                                is GoogleSignInResult.Error -> {
                                    callback(false, result.message)
                                }
                                is GoogleSignInResult.Cancelled -> {
                                    callback(false, null)
                                }
                            }
                        }
                    },
                    onGoogleSignInRequested = {
                        lifecycleScope.launch {
                            when (val result = googleAuthManager.getGoogleIdToken()) {
                                is GoogleSignInResult.Success -> {
                                    authOperationMutex.withLock {
                                        inboxViewModel.signInWithGoogleIdToken(result.idToken, result.nonce)
                                    }
                                }
                                is GoogleSignInResult.Error -> {
                                    authOperationMutex.withLock {
                                        inboxViewModel.signInWithGoogleIdToken("invalid_error_token")
                                    }
                                }
                                is GoogleSignInResult.Cancelled -> {
                                    // User cancelled selection
                                }
                            }
                        }
                    }
                )
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Dispatches an incoming [Intent] to the correct handler:
     * - Notification tap: routes to the Task identified by [EXTRA_TASK_ID].
     * - Deep-link cras://open/&#42; sets a pending [DeepLinkAction] for [CrasApp].
     * - Deep-link cras://complete/task/{id} completes the Task via [InboxViewModel.completeRoutedTask],
     *   retaining the request until authenticated and tasks are loaded.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        // Notification tap routing (pre-existing)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId != null) {
            inboxViewModel.focusRoutedTask(taskId)
            return
        }

        val action = parseDeepLinkAction(intent) ?: return
        when (action) {
            is DeepLinkAction.CompleteTask -> {
                inboxViewModel.completeRoutedTask(action.taskId)
            }
            else -> {
                pendingDeepLinkAction = action
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reconcile endpoint and permission state whenever the app resumes.
        inboxViewModel.reconcileInstallation()
        maybeRequestNotificationPermissionOnce()

        // Re-evaluate today glance rows against current device date on resume.
        val tasks = inboxViewModel.allTasks.value
        if (tasks.isNotEmpty()) {
            lifecycleScope.launch {
                runCatching {
                    val rows = com.cras.app.quickaccess.buildTodayGlanceRows(tasks)
                    com.cras.app.quickaccess.updateTodayWidgets(applicationContext, rows)
                }
            }
        }
    }

    private fun currentPlatformPermissionState(): PlatformPermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PlatformPermissionState.GRANTED
        }
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return when {
            granted -> PlatformPermissionState.GRANTED
            notificationPrefs.getBoolean(KEY_PERMISSION_ASKED, false) ->
                PlatformPermissionState.DENIED
            else -> PlatformPermissionState.PROMPT
        }
    }

    /**
     * Cras does not repeatedly prompt for system permission; a single ask is
     * made per installation while the Operator is signed in. Later retries
     * belong to the Settings surface.
     */
    private fun maybeRequestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (inboxViewModel.authState.value !is AuthUiState.Authenticated) return
        if (currentPlatformPermissionState() != PlatformPermissionState.PROMPT) return
        val asked = notificationPrefs.getBoolean(KEY_PERMISSION_ASKED, false)
        if (asked) return
        notificationPrefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
