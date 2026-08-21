package com.cras.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.cras.app.notification.FcmTokenBus
import com.cras.app.notification.FirebaseFcmTokenProvider
import com.cras.app.notification.NotificationInstallationSync
import com.cras.app.notification.PlatformPermissionState
import com.cras.app.notification.SharedPreferencesNotificationPreferenceStore
import com.cras.app.ui.CrasApp
import com.cras.app.ui.inbox.AuthUiState
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.theme.CrasTheme
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {

    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var inboxViewModel: InboxViewModel
    private var installationSync: NotificationInstallationSync? = null
    private lateinit var notificationPrefs: android.content.SharedPreferences

    companion object {
        private const val KEY_PERMISSION_ASKED = "cras_notifications_permission_asked"
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                inboxViewModel.reconcileInstallation()
            }
        }

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
        googleAuthManager = GoogleAuthManager(this)

        lifecycleScope.launch {
            FcmTokenBus.latestToken.collect { token ->
                val authState = inboxViewModel.authState.value
                val session = (authState as? AuthUiState.Authenticated)?.session
                runCatching { sync.onFcmTokenRotated(token, session) }
            }
        }

        setContent {
            CrasTheme {
                CrasApp(
                    viewModel = inboxViewModel,
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
        return if (granted) {
            PlatformPermissionState.GRANTED
        } else {
            PlatformPermissionState.PROMPT
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
