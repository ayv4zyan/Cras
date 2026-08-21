package com.cras.app.notification

import com.cras.app.auth.OperatorSession
import com.cras.app.data.InstallationRecord
import com.cras.app.data.InstallationService
import com.cras.app.data.RegisterInstallationParams
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
        // the identity needed to retry deactivation must survive.
        assertEquals(installationId, preferences.getOrCreateInstallationId())
        assertEquals("fcm-token-initial", preferences.getPendingEndpoint())
        assertEquals(AndroidNotificationStatus.EndpointUnavailable, sync.status.value)

        // A later retry completes sign-out and resets the installation.
        service.deactivateError = null
        sync.deactivateForSignOut(sessionA)

        assertEquals(listOf(installationId), service.deactivatedIds)
        assertNotEquals(installationId, preferences.getOrCreateInstallationId())
        assertNull(preferences.getPendingEndpoint())
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
        assertEquals("fcm-token-rotated", preferences.getPendingEndpoint())
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
    var deactivateError: Exception? = null

    override suspend fun registerOrUpdate(
        session: OperatorSession,
        params: RegisterInstallationParams
    ): InstallationRecord? {
        registerCalls.add(Call(session, params))
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
        return true
    }
}
