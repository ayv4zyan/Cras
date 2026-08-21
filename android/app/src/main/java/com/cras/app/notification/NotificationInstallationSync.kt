package com.cras.app.notification

import com.cras.app.auth.OperatorSession
import com.cras.app.data.InstallationService
import com.cras.app.data.RegisterInstallationParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * An FCM registration-token event observed before reconciliation could act on
 * it. [Token] parks an explicit registration token; [Loss] records that the
 * previous registration token was reported lost, so reconciliation must clear
 * the endpoint instead of consulting the provider again.
 */
sealed interface PendingFcmTokenEvent {
    data class Token(val value: String) : PendingFcmTokenEvent
    data object Loss : PendingFcmTokenEvent
}

interface NotificationPreferenceStore {
    fun getOrCreateInstallationId(): String
    fun clearInstallationId()
    fun isLocalNotificationsEnabled(): Boolean
    fun setLocalNotificationsEnabled(enabled: Boolean)
    fun getPendingFcmTokenEvent(): PendingFcmTokenEvent?
    fun setPendingFcmTokenEvent(event: PendingFcmTokenEvent?)
}

class InMemoryNotificationPreferenceStore(
    initialId: String? = null,
    initialLocalEnabled: Boolean = true,
    initialPendingFcmTokenEvent: PendingFcmTokenEvent? = null
) : NotificationPreferenceStore {

    private var installationId: String? = initialId
    private var localEnabled: Boolean = initialLocalEnabled
    private var pendingFcmTokenEvent: PendingFcmTokenEvent? = initialPendingFcmTokenEvent

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

    override fun getPendingFcmTokenEvent(): PendingFcmTokenEvent? = pendingFcmTokenEvent

    override fun setPendingFcmTokenEvent(event: PendingFcmTokenEvent?) {
        pendingFcmTokenEvent = event
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

    // Authentication, resume, and token rotation can all trigger
    // reconciliation at once; serializing them keeps the final registration
    // on the most recently observed FCM token. Every sign-out increments
    // [signOutGeneration] under the same mutex, so an attempt captured before
    // it abandons registration instead of binding a fresh installation to a
    // signed-out Operator.
    private val reconcileMutex = Mutex()

    private val signOutGeneration = AtomicInteger(0)

    /**
     * Reconciles local enablement, platform permission, FCM endpoint, and the
     * observed Installation timezone against the server. Returns the resulting
     * status surface.
     */
    suspend fun reconcile(session: OperatorSession): AndroidNotificationStatus {
        val observedGeneration = signOutGeneration.get()
        return reconcileMutex.withLock {
            reconcileUnderMutex(session, observedGeneration)
        }
    }

    /**
     * Called when Firebase issues a (possibly rotated) registration token, or
     * reports the previous one lost with a null token. The event is parked
     * under [reconcileMutex] so a sign-out completing first discards it along
     * with the installation identity; otherwise it reconciles immediately.
     * Without an active session the event stays parked for the next
     * authentication or resume.
     */
    suspend fun onFcmTokenRotated(token: String?, session: OperatorSession?) {
        val observedGeneration = signOutGeneration.get()
        val event =
            if (token != null) PendingFcmTokenEvent.Token(token) else PendingFcmTokenEvent.Loss
        reconcileMutex.withLock {
            if (signOutGeneration.get() == observedGeneration) {
                preferences.setPendingFcmTokenEvent(event)
                if (session != null) {
                    reconcileUnderMutex(session, observedGeneration)
                }
            }
        }
    }

    suspend fun setLocalEnabled(
        enabled: Boolean,
        session: OperatorSession?
    ): AndroidNotificationStatus {
        val observedGeneration = signOutGeneration.get()
        preferences.setLocalNotificationsEnabled(enabled)
        if (session != null) {
            return reconcileMutex.withLock {
                reconcileUnderMutex(session, observedGeneration)
            }
        }
        val status = deriveAndroidInstallationStatus(
            enabled,
            permissionProvider(),
            hasEndpoint = when (preferences.getPendingFcmTokenEvent()) {
                is PendingFcmTokenEvent.Token -> true
                null, PendingFcmTokenEvent.Loss -> fcmTokenProvider() != null
            }
        )
        _status.value = status
        return status
    }

    /**
     * Runs one reconciliation pass. Callers must hold [reconcileMutex] and
     * pass the sign-out generation observed when the attempt started.
     */
    private suspend fun reconcileUnderMutex(
        session: OperatorSession,
        observedGeneration: Int
    ): AndroidNotificationStatus {
        if (signOutGeneration.get() != observedGeneration) {
            // Sign-out completed while this attempt waited on the mutex;
            // registering now would bind a fresh installation to the
            // signed-out Operator instead of waiting for the next
            // authentication.
            _status.value = AndroidNotificationStatus.EndpointUnavailable
            return AndroidNotificationStatus.EndpointUnavailable
        }

        val installationId = preferences.getOrCreateInstallationId()
        val localEnabled = preferences.isLocalNotificationsEnabled()
        val permission = permissionProvider()
        // The most recently observed FCM token wins. An explicit token-loss
        // event must reach the server as a cleared endpoint, so the provider
        // is consulted only while no token event is parked.
        val token = when (val parked = preferences.getPendingFcmTokenEvent()) {
            is PendingFcmTokenEvent.Token -> parked.value
            PendingFcmTokenEvent.Loss -> null
            null -> fcmTokenProvider()?.also { observed ->
                preferences.setPendingFcmTokenEvent(PendingFcmTokenEvent.Token(observed))
            }
        }
        val timezone = timezoneProvider()

        // Registering is the only suspension below; deactivation shares this
        // mutex and only it advances the generation, so the check above holds
        // until the call returns.
        val record = try {
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
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }

        if (record == null) {
            // Server confirmation failed, so activation cannot be claimed;
            // report what local observations still prove rather than
            // replaying a possibly stale Enabled surface.
            val unverifiedStatus =
                deriveAndroidInstallationStatus(localEnabled, permission, hasEndpoint = false)
            _status.value = unverifiedStatus
            return unverifiedStatus
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
     * Sign-out immediately disables the Operator-bound installation and resets
     * the stored identity so another Operator on this device binds separately.
     * It shares [reconcileMutex] with registrations and bumps
     * [signOutGeneration], so attempts captured earlier cannot re-activate the
     * installation after the deactivation. A failed deactivation keeps the
     * identity and parked token event so sign-out can be retried instead of
     * stranding an active registration server-side.
     */
    suspend fun deactivateForSignOut(session: OperatorSession): Unit =
        reconcileMutex.withLock {
            signOutGeneration.incrementAndGet()
            val installationId = preferences.getOrCreateInstallationId()
            val deactivated = try {
                installationService.deactivate(session, installationId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            _status.value = AndroidNotificationStatus.EndpointUnavailable
            // Only an explicit server confirmation counts as success; both a
            // thrown error and a false response leave an active row behind,
            // so the identity and parked token event survive for a retried
            // sign-out.
            if (deactivated != true) {
                return@withLock
            }
            preferences.clearInstallationId()
            preferences.setPendingFcmTokenEvent(null)
        }
}
