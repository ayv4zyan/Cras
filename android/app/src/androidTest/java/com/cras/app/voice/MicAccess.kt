package com.cras.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit

/**
 * Runtime control over microphone access for instrumented tests.
 *
 * Every fixture starts and ends with RECORD_AUDIO granted via UiAutomation so
 * classes never leak a denied state into each other.
 */
internal object MicAccess {

    private val appContext: Context = ApplicationProvider.getApplicationContext()

    fun ensureGranted() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            automation.grantRuntimePermission(appContext.packageName, Manifest.permission.RECORD_AUDIO)
        }
        awaitMicPermissionState(granted = true)
    }

    fun restore() {
        ensureGranted()
    }

    private fun awaitMicPermissionState(granted: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROPAGATION_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val state = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            if ((state == PackageManager.PERMISSION_GRANTED) == granted) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "RECORD_AUDIO did not become ${if (granted) "granted" else "revoked"} in time",
        )
    }

    private const val PROPAGATION_TIMEOUT_SECONDS = 10L
    private const val POLL_INTERVAL_MILLIS = 100L
}
