package com.cras.app.notification

import com.cras.app.auth.OperatorSession
import com.cras.app.data.InstallationService
import com.cras.app.data.RegisterInstallationParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The current installation's Notification state surface, mirroring the shared
 * vocabulary: Enabled, Disabled locally, Blocked by system permission, and
 * Endpoint unavailable.
 */
enum class AndroidNotificationStatus {
    Enabled,
    DisabledLocally,
    BlockedBySystemPermission,
    EndpointUnavailable
}

enum class PlatformPermissionState {
    GRANTED,
    DENIED,
    PROMPT;

    fun toWireValue(): String = when (this) {
        GRANTED -> "granted"
        DENIED -> "denied"
        PROMPT -> "prompt"
    }
}

interface NotificationPreferenceStore {
    fun getOrCreateInstallationId(): String
    fun clearInstallationId()
    fun isLocalNotificationsEnabled(): Boolean
    fun setLocalNotificationsEnabled(enabled: Boolean)
    fun getPendingEndpoint(): String?
    fun setPendingEndpoint(endpoint: String?)
}

class InMemoryNotificationPreferenceStore(
    initialId: String? = null,
    initialLocalEnabled: Boolean = true,
    initialPendingEndpoint: String? = null
) : NotificationPreferenceStore {

    private var installationId: String? = initialId
    private var localEnabled: Boolean = initialLocalEnabled
    private var pendingEndpoint: String? = initialPendingEndpoint

    override fun getOrCreateInstallationId(): String {
        val existing = installationId
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        installationId = created
        return created
    }

    override fun clearInstallationId() {
        installationId = null
    }

    override fun isLocalNotificationsEnabled(): Boolean = localEnabled

    override fun setLocalNotificationsEnabled(enabled: Boolean) {
        localEnabled = enabled
    }

    override fun getPendingEndpoint(): String? = pendingEndpoint

    override fun setPendingEndpoint(endpoint: String?) {
        pendingEndpoint = endpoint
    }
}

fun deriveAndroidInstallationStatus(
    localEnabled: Boolean,
    permission: PlatformPermissionState,
    hasEndpoint: Boolean
): AndroidNotificationStatus = when {
    !localEnabled -> AndroidNotificationStatus.DisabledLocally
    permission == PlatformPermissionState.DENIED ->
        AndroidNotificationStatus.BlockedBySystemPermission
    permission == PlatformPermissionState.GRANTED && hasEndpoint ->
        AndroidNotificationStatus.Enabled
    else -> AndroidNotificationStatus.EndpointUnavailable
}

/**
 * Keeps one Android installation eligible for the server-authoritative
 * Notification scheduler through an Operator-bound FCM registration token.
 *
 * Reconciliation runs when the Operator authenticates or the app resumes;
 * token rotation re-registers under the same installation identity; sign-out
 * disables the installation so a different Operator on this device binds a
 * separate record. Server credentials never reach the client.
 */
class NotificationInstallationSync(
    private val installationService: InstallationService,
    private val preferences: NotificationPreferenceStore,
    private val permissionProvider: () -> PlatformPermissionState,
    private val fcmTokenProvider: suspend () -> String?,
    private val timezoneProvider: () -> String = { java.time.ZoneId.systemDefault().id }
) {

    private val _status = MutableStateFlow(AndroidNotificationStatus.EndpointUnavailable)
    val status: StateFlow<AndroidNotificationStatus> = _status.asStateFlow()

    /**
     * Reconciles local enablement, platform permission, FCM endpoint, and the
     * observed Installation timezone against the server. Returns the resulting
     * status surface.
     */
    suspend fun reconcile(session: OperatorSession): AndroidNotificationStatus {
        val installationId = preferences.getOrCreateInstallationId()
        val localEnabled = preferences.isLocalNotificationsEnabled()
        val permission = permissionProvider()
        // The most recently observed FCM token wins; the provider is consulted
        // only before any explicit token event has been parked.
        val token = preferences.getPendingEndpoint() ?: fcmTokenProvider()
        if (token != null) {
            preferences.setPendingEndpoint(token)
        }
        val timezone = timezoneProvider()

        val record = runCatching {
            installationService.registerOrUpdate(
                session,
                RegisterInstallationParams(
                    id = installationId,
                    localEnabled = localEnabled,
                    permissionState = permission.toWireValue(),
                    endpoint = token,
                    installationTimezone = timezone
                )
            )
        }.getOrNull()

        if (record == null) {
            // Reconciliation failed; retain the current surface rather than
            // claiming a state that could not be verified.
            return _status.value
        }

        // A permanent provider rejection deactivates the installation server-side;
        // reflect that instead of trusting purely local observations.
        val hasLiveEndpoint =
            token != null && record.isActive != false && record.endpoint != null

        val newStatus =
            deriveAndroidInstallationStatus(localEnabled, permission, hasLiveEndpoint)
        _status.value = newStatus
        return newStatus
    }

    /**
     * Called when Firebase issues a (possibly rotated) registration token.
     * Without an active session the token is parked locally and reconciled at
     * the next authentication or resume.
     */
    suspend fun onFcmTokenRotated(token: String?, session: OperatorSession?) {
        preferences.setPendingEndpoint(token)
        if (session != null) {
            reconcile(session)
        }
    }

    suspend fun setLocalEnabled(
        enabled: Boolean,
        session: OperatorSession?
    ): AndroidNotificationStatus {
        preferences.setLocalNotificationsEnabled(enabled)
        if (session != null) {
            return reconcile(session)
        }
        val status = deriveAndroidInstallationStatus(
            enabled,
            permissionProvider(),
            hasEndpoint = preferences.getPendingEndpoint() != null || fcmTokenProvider() != null
        )
        _status.value = status
        return status
    }

    /**
     * Sign-out immediately disables the Operator-bound installation and resets
     * the stored identity so another Operator on this device binds separately.
     */
    suspend fun deactivateForSignOut(session: OperatorSession) {
        val installationId = preferences.getOrCreateInstallationId()
        runCatching {
            installationService.deactivate(session, installationId)
        }
        preferences.clearInstallationId()
        preferences.setPendingEndpoint(null)
        _status.value = AndroidNotificationStatus.EndpointUnavailable
    }
}
