package com.cras.app.voice

import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cras.app.voice.WavAssertions.assertRiffWavePcm16Header
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the capture-denied path against the real platform: a denied start
 * must surface as [MicPermissionDeniedException] (the signal
 * [com.cras.app.ui.voice.VoiceViewModel] relies on), leave no AudioRecord or
 * capture thread behind, and keep recording usable once access is available.
 *
 * Framework limitations on emulator-5554 (API 35), verified empirically:
 * - `UiAutomation.revokeRuntimePermission(RECORD_AUDIO)` makes ActivityManager
 *   kill the app under test itself ("Killing ...: permissions revoked"), which
 *   takes down the whole instrumentation run.
 * - `cmd appops set <pkg> RECORD_AUDIO deny|ignore` does not stick: the op is
 *   backed by the runtime grant and is force-reset to allow.
 * A deterministic denial that survives therefore uses an audio source this
 * app can never capture: [MediaRecorder.AudioSource.VOICE_CALL] requires the
 * signature-level CAPTURE_AUDIO_OUTPUT permission and fails exactly where a
 * revoked RECORD_AUDIO grant fails — at AudioRecord initialization.
 */
@RunWith(AndroidJUnit4::class)
class MicAudioRecorderPermissionInstrumentedTest {

    @Before
    fun grantMicrophoneAccess() {
        MicAccess.ensureGranted()
    }

    @After
    fun restoreMicrophoneAccess() {
        MicAccess.restore()
    }

    @Test
    fun deniedCaptureMakesStartThrowMicPermissionDeniedWithoutLeakedCapture() {
        val deniedRecorder = MicAudioRecorder(MediaRecorder.AudioSource.VOICE_CALL)

        try {
            deniedRecorder.start()
            throw AssertionError(
                "Expected MicPermissionDeniedException but start() succeeded.",
            )
        } catch (expected: MicPermissionDeniedException) {
            // Documented contract for capture this app is not permitted to do.
        }

        assertFalse(deniedRecorder.isRecording)
        assertEquals(null, deniedRecorder.startedAt)
        assertNull("AudioRecord must be released after denied start", field(deniedRecorder, "audioRecord"))
        assertNull("No capture thread may survive a denied start", field(deniedRecorder, "captureThread"))

        // Recovery: with a permitted source the recorder captures normally.
        val recorder = MicAudioRecorder()
        recorder.start()
        assertTrue(recorder.isRecording)
        Thread.sleep(CAPTURE_MILLIS)
        val result = recorder.stop()

        assertTrue(result.sizeBytes > WavAssertions.HEADER_LENGTH)
        assertRiffWavePcm16Header(result.wav, result.sizeBytes - WavAssertions.HEADER_LENGTH)
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private companion object {
        const val CAPTURE_MILLIS = 500L
    }
}
