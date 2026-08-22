package com.cras.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cras.app.voice.WavAssertions.assertRiffWavePcm16Header
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lifecycle smoke tests for [MicAudioRecorder] running against the real
 * microphone of the device/emulator. Verifies the documented WAV contract end
 * to end: a 44-byte RIFF/WAVE header with mono 16 kHz PCM16 framing and a
 * non-empty data section.
 */
@RunWith(AndroidJUnit4::class)
class MicAudioRecorderLifecycleInstrumentedTest {

    private lateinit var recorder: MicAudioRecorder

    @Before
    fun setUp() {
        MicAccess.ensureGranted()
        recorder = MicAudioRecorder()
    }

    @After
    fun tearDown() {
        recorder.cancel()
        MicAccess.restore()
    }

    @Test
    fun startStopCapturesRealSilenceIntoValidWav() {
        recorder.start()
        assertTrue(recorder.isRecording)
        assertNotNull(recorder.startedAt)

        // Capture roughly half a second of real (virtual-mic) audio.
        Thread.sleep(CAPTURE_MILLIS)

        val result = recorder.stop()

        assertFalse(recorder.isRecording)
        assertEquals(null, recorder.startedAt)
        assertRiffWavePcm16Header(result.wav, result.sizeBytes - WavAssertions.HEADER_LENGTH)
    }

    @Test
    fun stopBeforeStartThrowsDocumentedGuard() {
        val error = runCatching { recorder.stop() }.exceptionOrNull()

        assertTrue("Expected IllegalStateException, got $error", error is IllegalStateException)
        assertEquals("No active recording to stop.", error?.message)
    }

    @Test
    fun cancelDiscardsTakeSoStopRefusesCleanly() {
        recorder.start()
        Thread.sleep(CAPTURE_MILLIS / 4)
        recorder.cancel()

        val error = runCatching { recorder.stop() }.exceptionOrNull()

        assertTrue("Expected IllegalStateException, got $error", error is IllegalStateException)
        assertEquals("No active recording to stop.", error?.message)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun recorderIsReusableAcrossConsecutiveTakes() {
        repeat(REUSE_TAKES) { take ->
            recorder.start()
            assertTrue(recorder.isRecording)
            Thread.sleep(CAPTURE_MILLIS / 2)
            val result = recorder.stop()

            assertTrue(
                "Take $take produced an empty WAV",
                result.sizeBytes > WavAssertions.HEADER_LENGTH,
            )
            assertRiffWavePcm16Header(result.wav, result.sizeBytes - WavAssertions.HEADER_LENGTH)
        }
    }

    private companion object {
        const val CAPTURE_MILLIS = 500L
        const val REUSE_TAKES = 3
    }
}
