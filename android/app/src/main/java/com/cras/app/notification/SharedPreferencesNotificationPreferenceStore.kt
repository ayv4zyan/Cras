package com.cras.app.notification

import android.content.SharedPreferences
import java.util.UUID

private const val KEY_INSTALLATION_ID = "cras_notification_installation_id"
private const val KEY_LOCAL_ENABLED = "cras_notifications_local_enabled"
private const val KEY_PENDING_ENDPOINT = "cras_notifications_pending_endpoint"

// FCM registration tokens are opaque provider-generated strings; they can
// never equal this reserved marker, which records an explicit token loss.
private const val PENDING_ENDPOINT_LOSS_MARKER = "<<cras-endpoint-lost>>"

class SharedPreferencesNotificationPreferenceStore(
    private val preferences: SharedPreferences
) : NotificationPreferenceStore {

    override fun getOrCreateInstallationId(): String {
        preferences.getString(KEY_INSTALLATION_ID, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_INSTALLATION_ID, created).apply()
        return created
    }

    override fun clearInstallationId() {
        preferences.edit().remove(KEY_INSTALLATION_ID).apply()
    }

    override fun isLocalNotificationsEnabled(): Boolean =
        preferences.getBoolean(KEY_LOCAL_ENABLED, true)

    override fun setLocalNotificationsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LOCAL_ENABLED, enabled).apply()
    }

    override fun getPendingFcmTokenEvent(): PendingFcmTokenEvent? =
        when (val stored = preferences.getString(KEY_PENDING_ENDPOINT, null)) {
            null -> null
            PENDING_ENDPOINT_LOSS_MARKER -> PendingFcmTokenEvent.Loss
            else -> PendingFcmTokenEvent.Token(stored)
        }

    override fun setPendingFcmTokenEvent(event: PendingFcmTokenEvent?) {
        preferences.edit()
            .apply {
                when (event) {
                    null -> remove(KEY_PENDING_ENDPOINT)
                    PendingFcmTokenEvent.Loss ->
                        putString(KEY_PENDING_ENDPOINT, PENDING_ENDPOINT_LOSS_MARKER)
                    is PendingFcmTokenEvent.Token ->
                        putString(KEY_PENDING_ENDPOINT, event.value)
                }
            }
            .apply()
    }
}
