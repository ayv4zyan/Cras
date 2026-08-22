package com.cras.app.notification

import com.cras.app.auth.OperatorSession
import com.cras.app.data.InstallationRecord
import com.cras.app.data.InstallationService
import com.cras.app.data.RegisterInstallationParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class NotificationInstallationSyncTest {

    private val sessionA = OperatorSession(
        accessToken = "token-a",
        operatorId = "550e8400-e29b-41d4-a716-446655440001",
        email = "alice@cras.app"
    )
    private val sessionB = OperatorSession(
        accessToken = "token-b",
        operatorId = "550e8400-e29b-41d4-a716-446655440002",
        email = "bob@cras.app"
    )

    private lateinit var service: FakeInstallationService
    private lateinit var preferences: InMemoryNotificationPreferenceStore
    private var permissionState = PlatformPermissionState.GRANTED
    private var fcmToken: String? = "fcm-token-initial"
    private var deviceTimezone = "Europe/Berlin"

    @Before
    fun setUp() {
        service = FakeInstallationService()
        preferences = InMemoryNotificationPreferenceStore()
        permissionState = PlatformPermissionState.GRANTED
        fcmToken = "fcm-token-initial"
        deviceTimezone = "Europe/Berlin"
    }

    private fun createSync(): NotificationInstallationSync =
        NotificationInstallationSync(
            installationService = service,
            preferences = preferences,
            permissionProvider = { permissionState },
            fcmTokenProvider = { fcmToken },
            timezoneProvider = { deviceTimezone }
        )

    @Test
    fun `reconcile registers an eligible installation and reports Enabled`() = runTest {
        val sync = createSync()

        val status = sync.reconcile(sessionA)

        assertEquals(AndroidNotificationStatus.Enabled, status)
        val call = service.registerCalls.first()
        assertTrue(call.params.localEnabled)
        assertEquals("granted", call.params.permissionState)
        assertEquals("fcm-token-initial", call.params.endpoint)
        assertEquals("Europe/Berlin", call.params.installationTimezone)
        assertEquals(preferences.getOrCreateInstallationId(), call.params.id)
    }

    @Test
    fun `reconcile after a timezone change re-registers with the newly observed zone`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)

        deviceTimezone = "America/New_York"
        sync.reconcile(sessionA)

        assertEquals(2, service.registerCalls.size)
        assertEquals("Europe/Berlin", service.registerCalls[0].params.installationTimezone)
        assertEquals("America/New_York", service.registerCalls[1].params.installationTimezone)
    }

    @Test
    fun `denied system permission reports Blocked by system permission and cancels eligibility`() = runTest {
        permissionState = PlatformPermissionState.DENIED
        val sync = createSync()

        val status = sync.reconcile(sessionA)

        assertEquals(AndroidNotificationStatus.BlockedBySystemPermission, status)
        assertEquals("denied", service.registerCalls.first().params.permissionState)
    }

    @Test
    fun `missing FCM token reports Endpoint unavailable without a registration endpoint`() = runTest {
        fcmToken = null
        val sync = createSync()

        val status = sync.reconcile(sessionA)

        assertEquals(AndroidNotificationStatus.EndpointUnavailable, status)
        assertNull(service.registerCalls.first().params.endpoint)
    }

    @Test
    fun `local disablement updates the status and informs the server`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)

        val status = sync.setLocalEnabled(false, sessionA)

        assertEquals(AndroidNotificationStatus.DisabledLocally, status)
        assertFalse(service.registerCalls.last().params.localEnabled)
        assertEquals(
            AndroidNotificationStatus.DisabledLocally,
            sync.status.value
        )
    }

    @Test
    fun `failed disablement reports Disabled locally instead of a stale Enabled surface`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)
        assertEquals(AndroidNotificationStatus.Enabled, sync.status.value)

        // The local preference is stored, but the registration fails.
        service.registerError = IOException("offline")
        val status = sync.setLocalEnabled(false, sessionA)

        // The server could not confirm anything, but the local preference is
        // still provable; replaying Enabled would deny the Operator's toggle.
        assertEquals(AndroidNotificationStatus.DisabledLocally, status)
        assertEquals(AndroidNotificationStatus.DisabledLocally, sync.status.value)

        // A later successful reconciliation confirms the disabled surface.
        service.registerError = null
        assertEquals(AndroidNotificationStatus.DisabledLocally, sync.reconcile(sessionA))
    }

    @Test
    fun `reconciliation failure after a permission change reports blocked instead of Enabled`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)
        assertEquals(AndroidNotificationStatus.Enabled, sync.status.value)

        permissionState = PlatformPermissionState.DENIED
        service.registerError = IOException("offline")

        val status = sync.reconcile(sessionA)

        assertEquals(AndroidNotificationStatus.BlockedBySystemPermission, status)
    }

    @Test
    fun `FCM token rotation keeps one installation record under a new endpoint`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)
        val installationId = preferences.getOrCreateInstallationId()

        fcmToken = "fcm-token-rotated"
        sync.onFcmTokenRotated("fcm-token-rotated", sessionA)

        assertEquals(2, service.registerCalls.size)
        assertEquals(installationId, service.registerCalls[1].params.id)
        assertEquals("fcm-token-rotated", service.registerCalls[1].params.endpoint)
        assertNotEquals(service.registerCalls[0].params.endpoint, service.registerCalls[1].params.endpoint)
    }

    @Test
    fun `explicit token loss registers a cleared endpoint even when the provider still answers`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)

        // Firebase reports the registration token lost, but the provider
        // still serves the previous token from cache.
        sync.onFcmTokenRotated(null, sessionA)

        // The explicit loss must reach the server as a cleared endpoint, not
        // be resurrected from the provider as a stale endpoint; confirming
        // that registration consumes the parked loss.
        assertEquals(2, service.registerCalls.size)
        assertNull(service.registerCalls[1].params.endpoint)
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, sync.status.value)
        assertNull(preferences.getPendingFcmTokenEvent())
    }

    @Test
    fun `a confirmed reconciliation lifts a parked loss so the provider answers again`() = runTest {
        val sync = createSync()

        // The loss arrives before any session exists, so it parks without
        // reconciling.
        sync.onFcmTokenRotated(null, session = null)
        assertEquals(PendingFcmTokenEvent.Loss, preferences.getPendingFcmTokenEvent())

        // The first reconciliation delivers the parked loss as a cleared
        // endpoint...
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, sync.reconcile(sessionA))
        assertNull(service.registerCalls.single().params.endpoint)

        // ...and its confirmation consumes the parked loss, so the next pass
        // consults the provider again; whatever it serves — even a cache of
        // the lost token — is authoritative and gets registered.
        fcmToken = "fcm-token-provider"
        assertEquals(AndroidNotificationStatus.Enabled, sync.reconcile(sessionA))

        assertEquals(2, service.registerCalls.size)
        assertEquals("fcm-token-provider", service.registerCalls[1].params.endpoint)
    }

    @Test
    fun `a parked loss reports no endpoint while unauthenticated even when the provider answers`() = runTest {
        val sync = createSync()

        // The loss arrives before any session exists and stays parked.
        sync.onFcmTokenRotated(null, session = null)
        fcmToken = "fcm-token-still-cached"

        // Without a session the toggle derives status locally; the parked
        // loss counts as no usable endpoint regardless of the cached
        // provider token.
        assertEquals(
            AndroidNotificationStatus.DisabledLocally,
            sync.setLocalEnabled(false, session = null)
        )
        assertEquals(
            AndroidNotificationStatus.EndpointUnavailable,
            sync.setLocalEnabled(true, session = null)
        )
    }

    @Test
    fun `sign-out disables the installation and resets it so a new Operator binds separately`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)
        val firstOperatorInstallationId = preferences.getOrCreateInstallationId()

        sync.deactivateForSignOut(sessionA)

        assertEquals(firstOperatorInstallationId, service.deactivatedIds.single())

        // A different Operator on the same device receives a separate installation record.
        val statusForB = sync.reconcile(sessionB)

        assertEquals(AndroidNotificationStatus.Enabled, statusForB)
        val registrationForB = service.registerCalls.last()
        assertNotEquals(firstOperatorInstallationId, registrationForB.params.id)
        assertEquals("fcm-token-initial", registrationForB.params.endpoint)
    }

    @Test
    fun `failed sign-out deactivation keeps the installation identity and endpoint for retry`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)
        val installationId = preferences.getOrCreateInstallationId()
        service.deactivateError = IOException("offline")

        sync.deactivateForSignOut(sessionA)

        // The server may still hold an active endpoint for this Operator, so
        // the identity needed to retry deactivation must survive; the earlier
        // confirmed reconciliation already consumed any parked token event.
        assertEquals(installationId, preferences.getOrCreateInstallationId())
        assertNull(preferences.getPendingFcmTokenEvent())
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, sync.status.value)

        // A later retry completes sign-out and resets the installation.
        service.deactivateError = null
        sync.deactivateForSignOut(sessionA)

        assertEquals(listOf(installationId), service.deactivatedIds)
        assertNotEquals(installationId, preferences.getOrCreateInstallationId())
        assertNull(preferences.getPendingFcmTokenEvent())
    }

    @Test
    fun `sign-out deactivation returning false keeps the installation identity and endpoint`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)
        val installationId = preferences.getOrCreateInstallationId()
        service.deactivateResult = false

        sync.deactivateForSignOut(sessionA)

        // Without a confirmed removal of the active row, the retry state
        // must survive exactly as it does for a thrown error; the earlier
        // confirmed reconciliation already consumed any parked token event.
        assertEquals(installationId, preferences.getOrCreateInstallationId())
        assertNull(preferences.getPendingFcmTokenEvent())
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, sync.status.value)

        // A later confirmed deactivation completes sign-out and resets the installation.
        service.deactivateResult = true
        sync.deactivateForSignOut(sessionA)

        assertNotEquals(installationId, preferences.getOrCreateInstallationId())
        assertNull(preferences.getPendingFcmTokenEvent())
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `concurrent reconciliation is serialized so registrations cannot interleave`() = runTest {
        val releaseFirstCall = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val slowService = object : InstallationService {
            var calls = 0

            override suspend fun registerOrUpdate(
                session: OperatorSession,
                params: RegisterInstallationParams
            ): InstallationRecord {
                val call = ++calls
                events.add("start-$call:${params.endpoint}")
                if (call == 1) {
                    releaseFirstCall.await()
                }
                events.add("end-$call")
                return InstallationRecord(
                    id = params.id,
                    platform = "android",
                    localEnabled = params.localEnabled,
                    permissionState = params.permissionState,
                    endpoint = params.endpoint,
                    isActive = true
                )
            }

            override suspend fun deactivate(session: OperatorSession, installationId: String): Boolean =
                true
        }
        val sync = NotificationInstallationSync(
            installationService = slowService,
            preferences = preferences,
            permissionProvider = { permissionState },
            fcmTokenProvider = { fcmToken },
            timezoneProvider = { deviceTimezone }
        )

        var firstStatus: AndroidNotificationStatus? = null
        var secondStatus: AndroidNotificationStatus? = null
        launch { firstStatus = sync.reconcile(sessionA) }
        launch { secondStatus = sync.reconcile(sessionB) }
        runCurrent()

        // The first call holds the lock while suspended; the second caller
        // must not reach the service until it finishes.
        assertEquals(listOf("start-1:fcm-token-initial"), events.toList())

        releaseFirstCall.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "start-1:fcm-token-initial",
                "end-1",
                "start-2:fcm-token-initial",
                "end-2"
            ),
            events.toList()
        )
        assertEquals(AndroidNotificationStatus.Enabled, firstStatus)
        assertEquals(AndroidNotificationStatus.Enabled, secondStatus)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `sign-out deactivation waits for in-flight reconciliation before running`() = runTest {
        val releaseReconcile = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val suspendedService = object : InstallationService {
            override suspend fun registerOrUpdate(
                session: OperatorSession,
                params: RegisterInstallationParams
            ): InstallationRecord {
                events.add("register")
                releaseReconcile.await()
                return InstallationRecord(
                    id = params.id,
                    platform = "android",
                    localEnabled = params.localEnabled,
                    permissionState = params.permissionState,
                    endpoint = params.endpoint,
                    isActive = true
                )
            }

            override suspend fun deactivate(session: OperatorSession, installationId: String): Boolean {
                events.add("deactivate")
                return true
            }
        }
        val sync = NotificationInstallationSync(
            installationService = suspendedService,
            preferences = preferences,
            permissionProvider = { permissionState },
            fcmTokenProvider = { fcmToken },
            timezoneProvider = { deviceTimezone }
        )

        val reconciliation = launch { sync.reconcile(sessionA) }
        runCurrent()
        // Reconciliation is suspended inside its registration while holding the lock.
        assertEquals(listOf("register"), events.toList())

        val installationId = preferences.getOrCreateInstallationId()
        val signOut = launch { sync.deactivateForSignOut(sessionA) }
        runCurrent()

        // Deactivation must not run while the registration holds the lock.
        assertEquals(listOf("register"), events.toList())

        releaseReconcile.complete(Unit)
        advanceUntilIdle()
        reconciliation.join()
        signOut.join()

        // Deactivation ran only after the in-flight registration completed;
        // it must never interleave and re-activate the signed-out installation.
        assertEquals(listOf("register", "deactivate"), events.toList())
        assertNotEquals(installationId, preferences.getOrCreateInstallationId())
        assertNull(preferences.getPendingFcmTokenEvent())
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `token callback resumed after sign-out does not register a new installation`() = runTest {
        val releaseInitialRegistration = CompletableDeferred<Unit>()
        val registeredEndpoints = mutableListOf<String?>()
        val deactivatedIds = mutableListOf<String>()
        val gatingService = object : InstallationService {
            override suspend fun registerOrUpdate(
                session: OperatorSession,
                params: RegisterInstallationParams
            ): InstallationRecord {
                registeredEndpoints.add(params.endpoint)
                releaseInitialRegistration.await()
                return InstallationRecord(
                    id = params.id,
                    platform = "android",
                    localEnabled = params.localEnabled,
                    permissionState = params.permissionState,
                    endpoint = params.endpoint,
                    isActive = true
                )
            }

            override suspend fun deactivate(
                session: OperatorSession,
                installationId: String
            ): Boolean {
                deactivatedIds.add(installationId)
                return true
            }
        }
        val sync = NotificationInstallationSync(
            installationService = gatingService,
            preferences = preferences,
            permissionProvider = { permissionState },
            fcmTokenProvider = { fcmToken },
            timezoneProvider = { deviceTimezone }
        )

        val installationId = preferences.getOrCreateInstallationId()
        val initial = launch { sync.reconcile(sessionA) }
        runCurrent()
        // The initial reconciliation parks inside its registration, holding the mutex.
        assertEquals(listOf<String?>(fcmToken), registeredEndpoints)

        val signOut = launch { sync.deactivateForSignOut(sessionA) }
        val lateToken = launch { sync.onFcmTokenRotated("fcm-token-late", sessionA) }
        runCurrent()

        // Sign-out queued ahead of the token callback, which captured its
        // generation before parking and is suspended on the mutex.
        assertEquals(1, registeredEndpoints.size)

        releaseInitialRegistration.complete(Unit)
        advanceUntilIdle()
        initial.join()
        signOut.join()
        lateToken.join()

        // Sign-out won: the resumed callback abandoned both its parked token
        // and its registration instead of activating a fresh installation for
        // a signed-out Operator.
        assertEquals(listOf(installationId), deactivatedIds)
        assertEquals(listOf<String?>(fcmToken), registeredEndpoints)
        assertNull(preferences.getPendingFcmTokenEvent())
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, sync.status.value)
    }

    @Test
    fun `registration and deactivation failures surface cancellation instead of swallowing it`() = runTest {
        val sync = createSync()

        service.registerError = CancellationException("caller cancelled")
        try {
            sync.reconcile(sessionA)
            fail("CancellationException from registration must propagate")
        } catch (_: CancellationException) {
            // expected
        }

        service.registerError = null
        service.deactivateError = CancellationException("caller cancelled")
        try {
            sync.deactivateForSignOut(sessionA)
            fail("CancellationException from deactivation must propagate")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `server-side provider rejection surfaces as Endpoint unavailable on reconcile`() = runTest {
        // Permanent FCM rejection disabled the endpoint and its jobs server-side;
        // the device must not claim an enabled state it cannot prove.
        service.nextRecordOverride = InstallationRecord(
            id = preferences.getOrCreateInstallationId(),
            platform = "android",
            localEnabled = true,
            permissionState = "granted",
            endpoint = null,
            isActive = false
        )
        val sync = createSync()

        val status = sync.reconcile(sessionA)

        assertEquals(AndroidNotificationStatus.EndpointUnavailable, status)
    }

    @Test
    fun `pending token observed while unauthenticated is used at sign-in reconciliation`() = runTest {
        val sync = createSync()

        // Service delivers a token refresh before any session exists.
        sync.onFcmTokenRotated("fcm-token-rotated", session = null)
        assertEquals(
            PendingFcmTokenEvent.Token("fcm-token-rotated"),
            preferences.getPendingFcmTokenEvent()
        )
        assertTrue(service.registerCalls.isEmpty())

        val status = sync.reconcile(sessionA)

        assertEquals(AndroidNotificationStatus.Enabled, status)
        assertEquals("fcm-token-rotated", service.registerCalls.single().params.endpoint)
    }

    @Test
    fun `resume reconciliation reuses the persisted installation identity`() = runTest {
        val sync = createSync()
        sync.reconcile(sessionA)
        val persistedId = preferences.getOrCreateInstallationId()

        // App process restarts against the same SharedPreferences-backed store.
        val resumedPreferences = InMemoryNotificationPreferenceStore(initialId = persistedId)
        val resumedSync = NotificationInstallationSync(
            installationService = service,
            preferences = resumedPreferences,
            permissionProvider = { permissionState },
            fcmTokenProvider = { fcmToken },
            timezoneProvider = { deviceTimezone }
        )
        resumedSync.reconcile(sessionA)

        assertEquals(persistedId, service.registerCalls.last().params.id)
    }
}

private class FakeInstallationService : InstallationService {
    data class Call(val session: OperatorSession, val params: RegisterInstallationParams)

    val registerCalls = mutableListOf<Call>()
    val deactivatedIds = mutableListOf<String>()
    var nextRecordOverride: InstallationRecord? = null
    var registerError: Exception? = null
    var deactivateError: Exception? = null
    var deactivateResult: Boolean = true

    override suspend fun registerOrUpdate(
        session: OperatorSession,
        params: RegisterInstallationParams
    ): InstallationRecord? {
        registerCalls.add(Call(session, params))
        registerError?.let { throw it }
        return nextRecordOverride ?: InstallationRecord(
            id = params.id,
            platform = "android",
            localEnabled = params.localEnabled,
            permissionState = params.permissionState,
            endpoint = params.endpoint,
            isActive = true
        )
    }

    override suspend fun deactivate(session: OperatorSession, installationId: String): Boolean {
        deactivateError?.let { throw it }
        deactivatedIds.add(installationId)
        return deactivateResult
    }
}
