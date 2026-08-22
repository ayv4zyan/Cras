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
): ByteArray {
    val buffer = ByteBuffer.allocate(WAV_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

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

    return buffer.array()
}

/**
 * Encodes an array of float audio samples (-1.0 to 1.0) into a 16-bit PCM WAV file.
 * Negative samples scale by 0x8000, non-negative by 0x7fff, exactly like the web encoder.
 */
fun encodePcmWav(samples: FloatArray, sampleRate: Int = TARGET_SAMPLE_RATE): ByteArray {
    val pcmData = ByteArray(samples.size * 2)

    for (i in samples.indices) {
        val s = samples[i].coerceIn(-1f, 1f)
        val scaled = if (s < 0) s * 0x8000 else s * 0x7fff
        val truncated = floor(scaled).toInt()
        pcmData[i * 2] = (truncated and 0xFF).toByte()
        pcmData[i * 2 + 1] = ((truncated shr 8) and 0xFF).toByte()
    }

    val header = createWavHeader(pcmData.size, sampleRate, 1, 16)
    return header + pcmData
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
 */
class RecordingBuffer(private val inputSampleRate: Int) {

    private val chunks = mutableListOf<FloatArray>()
    private val maxInputSamplesForSizeBound: Long =
        (((MAX_AUDIO_SIZE_BYTES - WAV_HEADER_LENGTH - SIZE_BOUND_SAFETY_BYTES) / 2).toDouble() *
            inputSampleRate / TARGET_SAMPLE_RATE).toLong().coerceAtLeast(0L)

    var totalInputSamples: Long = 0L
        private set

    val isEmpty: Boolean get() = totalInputSamples == 0L

    val durationMs: Long
        get() = totalInputSamples * 1000L / inputSampleRate

    val shouldAutoStop: Boolean
        get() = durationMs >= MAX_RECORDING_DURATION_MS || wouldExceedSizeBound

    /** Whether the capture has reached the shared 4 MB encoded-size bound. */
    val wouldExceedSizeBound: Boolean
        get() = totalInputSamples >= maxInputSamplesForSizeBound

    fun append(chunk: FloatArray) {
        if (shouldAutoStop || chunk.isEmpty()) return

        val capacity = (maxInputSamplesForSizeBound - totalInputSamples).coerceAtLeast(0L)
        if (capacity == 0L) return

        val accepted = min(chunk.size.toLong(), capacity).toInt()
        chunks.add(if (accepted == chunk.size) chunk.copyOf() else chunk.copyOf(accepted))
        totalInputSamples += accepted
    }

    /**
     * Merges, resamples to 16 kHz and encodes the accumulated audio.
     * Throws like the web recorder when nothing was recorded.
     */
    fun build(): AudioRecordingResult =
        buildOrNull() ?: throw IllegalStateException("No active recording to stop.")

    fun buildOrNull(): AudioRecordingResult? {
        if (isEmpty) return null

        val merged = FloatArray(totalInputSamples.toInt())
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }

        val resampled = downsampleTo16kHz(merged, inputSampleRate)
        val wav = encodePcmWav(resampled, TARGET_SAMPLE_RATE)
        val durationSeconds = resampled.size.toDouble() / TARGET_SAMPLE_RATE

        return AudioRecordingResult(
            wav = wav,
            durationSeconds = durationSeconds.times(100).roundToInt() / 100.0,
        )
    }

    fun cancel() {
        chunks.clear()
        totalInputSamples = 0L
    }

    companion object {
        // Small pad absorbing resample-length rounding so build() never crosses the bound.
        private const val SIZE_BOUND_SAFETY_BYTES = 8L
    }
}
