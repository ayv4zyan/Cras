package com.cras.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cras.app.voice.WavAssertions.assertRiffWavePcm16Header
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-thread semantics around the bounded capture-thread join: cancelling or
 * stopping from another thread while the capture loop runs must never corrupt
 * shared state nor leak raw failures; whatever the interleaving, a refusal is
 * expressed through the documented [IllegalStateException] guards and the
 * recorder stays reusable afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MicAudioRecorderConcurrencyInstrumentedTest {

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
    fun cancelFromAnotherThreadRefusesAbandonedTakeAndKeepsRecorderUsable() {
        recorder.start()
        Thread.sleep(CAPTURE_MILLIS / 3) // let the capture loop enter record.read()

        val canceller = Thread { recorder.cancel() }
        canceller.start()
        canceller.join(JOIN_BOUND_MILLIS)
        assertFalse("cancel() must not hang on the capture join", canceller.isAlive)
        assertFalse(recorder.isRecording)

        // The abandoned take is gone: either a clean refusal via the guard...
        val outcome = runCatching { recorder.stop() }
        outcome.exceptionOrNull()?.let { error ->
            assertTrue(
                "Expected the documented IllegalStateException guard, got $error",
                error is IllegalStateException,
            )
            assertTrue(
                "Unexpected guard message: ${error.message}",
                error.message == "No active recording to stop." ||
                    error.message == "Microphone capture did not finish; recording abandoned.",
            )
        } ?: assertTrue(outcome.getOrThrow().sizeBytes > WavAssertions.HEADER_LENGTH)

        // ...and the next take is unaffected by any stale writes.
        assertFreshTakeProducesValidWav()
    }

    @Test
    fun stopFromAnotherThreadDuringCaptureEitherCompletesOrRefusesCleanly() {
        recorder.start()
        Thread.sleep(CAPTURE_MILLIS / 2)

        val outcome = AtomicReference<Result<AudioRecordingResult>>()
        val stopper = Thread { outcome.set(runCatching { recorder.stop() }) }
        stopper.start()
        stopper.join(JOIN_BOUND_MILLIS)
        assertFalse("stop() must not hang on the capture join", stopper.isAlive)

        val result = outcome.get()?.getOrNull()
        if (result != null) {
            assertRiffWavePcm16Header(result.wav, result.sizeBytes - WavAssertions.HEADER_LENGTH)
        } else {
            val error = requireNotNull(outcome.get()?.exceptionOrNull()) { "No outcome recorded" }
            assertTrue(
                "Only documented guards may surface, got $error",
                error is IllegalStateException &&
                    (error.message == "No active recording to stop." ||
                        error.message == "Microphone capture did not finish; recording abandoned."),
            )
        }
        assertFalse(recorder.isRecording)

        assertFreshTakeProducesValidWav()
    }

    @Test
    fun repeatedCancelCyclesFromAlternatingThreadsNeverWedgeTheRecorder() {
        repeat(STRESS_CYCLES) { cycle ->
            recorder.start()
            val canceller = Thread { recorder.cancel() }
            canceller.start()
            canceller.join(JOIN_BOUND_MILLIS)
            assertFalse("Cycle $cycle: cancel() hung", canceller.isAlive)
            assertFalse("Cycle $cycle: still recording after cancel()", recorder.isRecording)

            // Interleave full takes so any cross-take contamination surfaces
            // as an invalid WAV rather than silently passing.
            if (cycle % 2 == 1) {
                assertFreshTakeProducesValidWav(captureMillis = CAPTURE_MILLIS / 4)
            }
        }
    }

    private fun assertFreshTakeProducesValidWav(captureMillis: Long = CAPTURE_MILLIS) {
        recorder.start()
        assertTrue(recorder.isRecording)
        Thread.sleep(captureMillis)
        val result = recorder.stop()

        assertTrue(
            "Follow-up take produced an empty WAV (${result.sizeBytes} bytes)",
            result.sizeBytes > WavAssertions.HEADER_LENGTH,
        )
        assertRiffWavePcm16Header(result.wav, result.sizeBytes - WavAssertions.HEADER_LENGTH)
    }

    private companion object {
        const val CAPTURE_MILLIS = 500L
        const val JOIN_BOUND_MILLIS = 5_000L
        const val STRESS_CYCLES = 12
    }
}
