package com.cras.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

const val TARGET_SAMPLE_RATE = 16000
const val MAX_RECORDING_DURATION_MS = 120 * 1000L
const val MAX_AUDIO_SIZE_BYTES = 4 * 1024 * 1024

private const val WAV_HEADER_LENGTH = 44

/**
 * Creates a standard 44-byte WAV header for linear PCM data,
 * mirroring web/src/services/audioRecorder.ts createWavHeader.
 */
fun createWavHeader(
    dataByteLength: Int,
    sampleRate: Int = TARGET_SAMPLE_RATE,
    numChannels: Int = 1,
    bitsPerSample: Int = 16,
): ByteArray =
    ByteArray(WAV_HEADER_LENGTH).also {
        writeWavHeaderInto(it, dataByteLength, sampleRate, numChannels, bitsPerSample)
    }

private fun writeWavHeaderInto(
    target: ByteArray,
    dataByteLength: Int,
    sampleRate: Int,
    numChannels: Int,
    bitsPerSample: Int,
) {
    val buffer = ByteBuffer.wrap(target).order(ByteOrder.LITTLE_ENDIAN)

    // "RIFF" chunk descriptor
    buffer.put('R'.code.toByte())
    buffer.put('I'.code.toByte())
    buffer.put('F'.code.toByte())
    buffer.put('F'.code.toByte())
    // Total file size - 8
    buffer.putInt(36 + dataByteLength)

    // "WAVE" format
    buffer.put('W'.code.toByte())
    buffer.put('A'.code.toByte())
    buffer.put('V'.code.toByte())
    buffer.put('E'.code.toByte())

    // "fmt " sub-chunk
    buffer.put('f'.code.toByte())
    buffer.put('m'.code.toByte())
    buffer.put('t'.code.toByte())
    buffer.put(' '.code.toByte())
    // Subchunk1Size (16 for PCM)
    buffer.putInt(16)
    // AudioFormat (1 = PCM)
    buffer.putShort(1)
    // NumChannels
    buffer.putShort(numChannels.toShort())
    // SampleRate
    buffer.putInt(sampleRate)
    // ByteRate
    buffer.putInt(sampleRate * numChannels * (bitsPerSample / 8))
    // BlockAlign
    buffer.putShort((numChannels * (bitsPerSample / 8)).toShort())
    // BitsPerSample
    buffer.putShort(bitsPerSample.toShort())

    // "data" sub-chunk
    buffer.put('d'.code.toByte())
    buffer.put('a'.code.toByte())
    buffer.put('t'.code.toByte())
    buffer.put('a'.code.toByte())
    // Subchunk2Size
    buffer.putInt(dataByteLength)
}

/**
 * Scales one float sample (-1.0 to 1.0) into its PCM16 representation.
 * Negative samples scale by 0x8000, non-negative by 0x7fff, truncated toward
 * zero exactly like the JS Int16Array assignment in the web encoder.
 */
private fun quantizeSample(sample: Float): Int {
    val s = sample.coerceIn(-1f, 1f)
    val scaled = if (s < 0) s * 0x8000 else s * 0x7fff
    return scaled.toInt()
}

/**
 * Encodes an array of float audio samples (-1.0 to 1.0) into a 16-bit PCM WAV file.
 */
fun encodePcmWav(samples: FloatArray, sampleRate: Int = TARGET_SAMPLE_RATE): ByteArray {
    // Header and PCM land in a single allocation instead of a concatenated copy.
    val wav = ByteArray(WAV_HEADER_LENGTH + samples.size * 2)
    writeWavHeaderInto(wav, samples.size * 2, sampleRate, 1, 16)

    for (i in samples.indices) {
        val truncated = quantizeSample(samples[i])
        wav[WAV_HEADER_LENGTH + i * 2] = (truncated and 0xFF).toByte()
        wav[WAV_HEADER_LENGTH + i * 2 + 1] = ((truncated shr 8) and 0xFF).toByte()
    }

    return wav
}

/**
 * Resamples audio to 16 kHz mono with linear interpolation.
 * Returns the same array instance untouched when already at the target rate.
 */
fun downsampleTo16kHz(inputBuffer: FloatArray, inputSampleRate: Int): FloatArray {
    if (inputSampleRate == TARGET_SAMPLE_RATE) {
        return inputBuffer
    }

    val ratio = inputSampleRate.toDouble() / TARGET_SAMPLE_RATE
    val newLength = (inputBuffer.size / ratio).roundToInt()
    val result = FloatArray(newLength)

    for (i in 0 until newLength) {
        val originalIndex = i * ratio
        val indexLow = floor(originalIndex).toInt()
        val indexHigh = min(indexLow + 1, inputBuffer.size - 1)
        val fraction = originalIndex - indexLow

        result[i] = (inputBuffer[indexLow] * (1 - fraction) +
            inputBuffer[indexHigh] * fraction).toFloat()
    }

    return result
}

/**
 * A finished recording normalized to mono 16 kHz 16-bit PCM WAV.
 */
class AudioRecordingResult(
    val wav: ByteArray,
    val durationSeconds: Double,
) {
    val sizeBytes: Int get() = wav.size
}

/**
 * Accumulates raw microphone chunks at the capture sample rate and produces the
 * bounded, normalized WAV result. Pure JVM logic shared by mic capture and tests:
 * auto-stops at the shared 120 s limit and refuses samples that would push the
 * encoded WAV past the shared 4 MB limit.
 *
 * Memory profile: each chunk is converted and resampled to the 16 kHz PCM16
 * output the moment it arrives, so neither the capture-rate float history nor a
 * merged/resampled intermediate is ever held. Retained state is the pre-sized
 * output byte area (~4 MB worst case) plus one float and two counters; peak
 * live memory stays near the final WAV regardless of take length or rate.
 */
class RecordingBuffer(private val inputSampleRate: Int) {

    private val ratio: Double = inputSampleRate.toDouble() / TARGET_SAMPLE_RATE

    private val maxInputSamplesForSizeBound: Long =
        (((MAX_AUDIO_SIZE_BYTES - WAV_HEADER_LENGTH - SIZE_BOUND_SAFETY_BYTES) / 2).toDouble() *
            inputSampleRate / TARGET_SAMPLE_RATE).toLong().coerceAtLeast(0L)

    // Single pre-sized destination: header slot plus the largest data section
    // the shared 4 MB bound can ever produce at this capture rate. The header
    // is written over the front once the final length is known at build time.
    private val encoded = ByteArray(
        WAV_HEADER_LENGTH + ((maxInputSamplesForSizeBound / ratio).roundToInt() + 1) * 2
    )

    var totalInputSamples: Long = 0L
        private set

    // Newest accepted input sample. It resolves interpolation windows that
    // straddle an append boundary without retaining the previous chunk.
    private var lastSample: Float = 0f

    // Output samples already finalized into [encoded], in order.
    private var emittedSamples: Int = 0

    val isEmpty: Boolean get() = totalInputSamples == 0L

    val durationMs: Long
        get() = totalInputSamples * 1000L / inputSampleRate

    val shouldAutoStop: Boolean
        get() = durationMs >= MAX_RECORDING_DURATION_MS || wouldExceedSizeBound

    /** Whether the capture has reached the shared 4 MB encoded-size bound. */
    val wouldExceedSizeBound: Boolean
        get() = totalInputSamples >= maxInputSamplesForSizeBound

    /**
     * Converts and resamples one capture-rate chunk straight into the compact
     * PCM16 output. Every output sample whose two-point interpolation window is
     * fully covered by arrived data is finalized immediately; a window reaching
     * past the newest sample stays pending until the next append closes it, so
     * chunk boundaries never alter the arithmetic. The per-sample math matches
     * downsampleTo16kHz over a merged buffer byte-for-byte.
     */
    fun append(chunk: FloatArray) {
        if (shouldAutoStop || chunk.isEmpty()) return

        val capacity = (maxInputSamplesForSizeBound - totalInputSamples).coerceAtLeast(0L)
        if (capacity == 0L) return

        val accepted = min(chunk.size.toLong(), capacity).toInt()
        val newestAvailable = totalInputSamples + accepted - 1L
        val chunkStart = totalInputSamples

        fun sampleAt(index: Int): Float =
            if (index >= chunkStart) chunk[(index - chunkStart).toInt()] else lastSample

        while (true) {
            val originalIndex = emittedSamples * ratio
            val indexLowDouble = floor(originalIndex)
            val indexLow = indexLowDouble.toInt()
            // The high neighbour has not arrived yet: defer this output.
            if (indexLow + 1 > newestAvailable) break
            val fraction = originalIndex - indexLowDouble

            val value = (sampleAt(indexLow) * (1 - fraction) +
                sampleAt(indexLow + 1) * fraction).toFloat()

            writeSample(emittedSamples, quantizeSample(value))
            emittedSamples++
        }

        lastSample = chunk[accepted - 1]
        totalInputSamples += accepted
    }

    /**
     * Encodes the accumulated audio. Outputs whose window ran past the final
     * sample resolve here against the clamped high index of the reference
     * pipeline: both neighbours are the last sample, so the interpolation
     * collapses onto it with identical arithmetic. Throws like the web
     * recorder when nothing was recorded.
     */
    fun build(): AudioRecordingResult =
        buildOrNull() ?: throw IllegalStateException("No active recording to stop.")

    fun buildOrNull(): AudioRecordingResult? {
        if (isEmpty) return null

        val mergedSize = totalInputSamples.toInt()
        val newLength = (mergedSize / ratio).roundToInt()

        while (emittedSamples < newLength) {
            val originalIndex = emittedSamples * ratio
            val indexLow = floor(originalIndex)
            val fraction = originalIndex - indexLow

            val value = (lastSample * (1 - fraction) +
                lastSample * fraction).toFloat()

            writeSample(emittedSamples, quantizeSample(value))
            emittedSamples++
        }

        writeWavHeaderInto(encoded, newLength * 2, TARGET_SAMPLE_RATE, 1, 16)

        val durationSeconds = newLength.toDouble() / TARGET_SAMPLE_RATE

        return AudioRecordingResult(
            wav = encoded.copyOf(WAV_HEADER_LENGTH + newLength * 2),
            durationSeconds = durationSeconds.times(100).roundToInt() / 100.0,
        )
    }

    private fun writeSample(outputIndex: Int, truncated: Int) {
        encoded[WAV_HEADER_LENGTH + outputIndex * 2] = (truncated and 0xFF).toByte()
        encoded[WAV_HEADER_LENGTH + outputIndex * 2 + 1] = ((truncated shr 8) and 0xFF).toByte()
    }

    fun cancel() {
        totalInputSamples = 0L
        emittedSamples = 0
        lastSample = 0f
    }

    companion object {
        // Small pad absorbing resample-length rounding so build() never crosses the bound.
        private const val SIZE_BOUND_SAFETY_BYTES = 8L
    }
}
