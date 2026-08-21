package com.cras.app.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cras.app.BuildConfig
import com.cras.app.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

const val NOTIFICATION_CHANNEL_ID = "cras_task_notifications"

/** Opaque routing data carried by a displayed Task notification's tap intent. */
const val EXTRA_TASK_ID = "cras.extra.TASK_ID"
const val EXTRA_OCCURRENCE_KEY = "cras.extra.OCCURRENCE_KEY"

/**
 * Process-wide relay for FCM registration tokens issued by
 * [CrasFirebaseMessagingService]. The latest token is parked until the app can
 * reconcile it against the Operator's installation.
 */
object FcmTokenBus {
    private val _latestToken = MutableSharedFlow<String?>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val latestToken: SharedFlow<String?> = _latestToken.asSharedFlow()

    fun publish(token: String?) {
        _latestToken.tryEmit(token)
    }
}

object CrasNotifications {

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Manually initializes the Firebase app from public client configuration.
     * Returns false when no configuration is present (local development).
     * Server-side FCM credentials never reach the client.
     */
    fun initializeFirebase(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true

        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        val applicationId = BuildConfig.FIREBASE_APPLICATION_ID
        val apiKey = BuildConfig.FIREBASE_API_KEY
        if (projectId.isBlank() || applicationId.isBlank() || apiKey.isBlank()) {
            return false
        }

        return try {
            val options = FirebaseOptions.Builder()
                .setProjectId(projectId)
                .setApplicationId(applicationId)
                .setApiKey(apiKey)
                .setGcmSenderId(BuildConfig.FIREBASE_GCM_SENDER_ID)
                .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
                .build()
            FirebaseApp.initializeApp(context, options) != null
        } catch (_: IllegalStateException) {
            false
        }
    }

    /**
     * Displays a system notification carrying only the Task title plus opaque
     * routing data. The stable occurrence identity is used as the tag so a
     * retried send replaces the existing display instead of duplicating it.
     * Tap-to-open only.
     */
    fun showTaskNotification(context: Context, title: String, taskId: String, occurrenceKey: String) {
        val launchIntent = (
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent()
            ).apply {
            // A unique data URI gives each PendingIntent its own identity so
            // occurrences cannot overwrite one another's routing extras.
            data = Uri.Builder()
                .scheme("cras")
                .authority("notification")
                .appendPath(occurrenceKey)
                .build()
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_OCCURRENCE_KEY, occurrenceKey)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            occurrenceKey.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // The occurrence identity tags the display so provider retries are idempotent.
        manager.notify(occurrenceKey, taskId.hashCode(), notification)
    }
}

/** Resolves the current FCM registration token, or null when unavailable. */
class FirebaseFcmTokenProvider(private val appContext: Context) {

    val resolve: suspend () -> String? = { currentToken() }

    private suspend fun currentToken(): String? {
        if (!CrasNotifications.initializeFirebase(appContext)) return null
        return try {
            suspendCancellableCoroutine { continuation ->
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token -> continuation.resumeWith(Result.success(token)) }
                    .addOnFailureListener { continuation.resumeWith(Result.success(null)) }
                    .addOnCanceledListener { continuation.resumeWith(Result.success(null)) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
