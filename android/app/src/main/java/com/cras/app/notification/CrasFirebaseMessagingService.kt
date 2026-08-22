package com.cras.app.notification

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM events for Cras. Background notification messages are rendered
 * by the system; foreground deliveries are displayed here with the same tag so
 * provider retries replace the existing display. Registration token rotation
 * is published for reconciliation.
 */
class CrasFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FcmTokenBus.publish(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        val title = notification.title ?: return
        val taskId = message.data["taskId"] ?: return
        val occurrenceKey = message.data["occurrenceKey"] ?: message.messageId ?: return

        // The displayed content is the Task title only, tap-to-open only.
        CrasNotifications.showTaskNotification(applicationContext, title, taskId, occurrenceKey)
    }
}
