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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class MainActivity : ComponentActivity() {

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

        inboxViewModel = InboxViewModel(
            authService = authService,
            taskService = taskService,
            labelService = labelService,
            commentService = commentService,
            settingsService = settingsService,
            realtimeService = realtimeService,
            outboxStore = outboxStore,
            installationSync = sync
        )

        // Voice gets its own timeouts: a 4 MB upload plus server transcription
        // exceeds the shared client's ~10 s defaults.
        val voiceCaptureApi = SupabaseVoiceCaptureApi(
            config,
            SupabaseVoiceCaptureApi.voiceHttpClient(httpClient),
        )
        val voiceRecordingStore = DirectoryVoiceRecordingStore(
            File(applicationContext.filesDir, "voice-recordings")
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
        // moment an Operator signs out so a later Operator cannot replay
        // earlier audio through Voice retry.
        lifecycleScope.launch {
            var previousState: AuthUiState? = null
            inboxViewModel.authState.collect { state ->
                if (previousState is AuthUiState.Authenticated &&
                    state is AuthUiState.Unauthenticated
                ) {
                    voiceViewModel.deleteAllRetainedRecordings()
                }
                previousState = state
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
                    onGoogleSignInRequested = {
                        lifecycleScope.launch {
                            when (val result = googleAuthManager.getGoogleIdToken()) {
                                is GoogleSignInResult.Success -> {
                                    inboxViewModel.signInWithGoogleIdToken(result.idToken, result.nonce)
                                }
                                is GoogleSignInResult.Error -> {
                                    inboxViewModel.signInWithGoogleIdToken("invalid_error_token")
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
     * - Deep-link cras://complete/task/{id} completes the Task via the
     *   canonical Outbox path (offline-safe).
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        // Notification tap routing (pre-existing)
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
        if (taskId != null) {
            inboxViewModel.focusRoutedTask(taskId)
            return
        }

        val data = intent.data ?: return

        // Complete-task deep-link from Today Glance widget
        if (data.scheme == "cras" && data.host == "complete" &&
            data.pathSegments.firstOrNull() == "task"
        ) {
            val id = data.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return
            inboxViewModel.completeTask(taskId = id)
            return
        }

        // Navigation deep-link from Launchpad / Shortcuts / Today Glance header
        val action = parseDeepLinkAction(intent) ?: return
        pendingDeepLinkAction = action
    }

    override fun onResume() {
        super.onResume()
        // Reconcile endpoint and permission state whenever the app resumes.
        inboxViewModel.reconcileInstallation()
        maybeRequestNotificationPermissionOnce()
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
