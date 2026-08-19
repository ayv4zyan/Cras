package com.cras.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cras.app.auth.GoogleAuthManager
import com.cras.app.auth.GoogleSignInResult
import com.cras.app.auth.SharedPreferencesSessionStore
import com.cras.app.auth.SupabaseAuthService
import com.cras.app.config.getPublicSupabaseConfig
import com.cras.app.data.SupabaseCommentService
import com.cras.app.data.SupabaseLabelService
import com.cras.app.data.SupabaseTaskService
import com.cras.app.ui.CrasApp
import com.cras.app.ui.inbox.InboxViewModel
import com.cras.app.ui.theme.CrasTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var inboxViewModel: InboxViewModel

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
        val authService = SupabaseAuthService(config, sessionStore)
        val taskService = SupabaseTaskService(config)
        val labelService = SupabaseLabelService(config)
        val commentService = SupabaseCommentService(config)

        inboxViewModel = InboxViewModel(authService, taskService, labelService, commentService)
        googleAuthManager = GoogleAuthManager(this)

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
}
