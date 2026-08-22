package com.cras.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WavCodecTest {

    private fun readU16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    @Test
    fun `createWavHeader generates a valid 44-byte header with correct format and sample rate`() {
        val dataByteLength = 32000 // 1 second of 16kHz 16-bit mono
        val header = createWavHeader(dataByteLength, 16000, 1, 16)

        assertEquals(44, header.size)

        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals((36 + dataByteLength).toLong(), readU32LE(header, 4))
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        // Subchunk1Size (16 for PCM)
        assertEquals(16L, readU32LE(header, 16))
        // AudioFormat = 1 (PCM)
        assertEquals(1, readU16LE(header, 20))
        // NumChannels = 1 (mono)
        assertEquals(1, readU16LE(header, 22))
        // SampleRate = 16000
        assertEquals(16000L, readU32LE(header, 24))
        // ByteRate = 16000 * 1 * 2 = 32000
        assertEquals(32000L, readU32LE(header, 28))
        // BlockAlign = 2
        assertEquals(2, readU16LE(header, 32))
        // BitsPerSample = 16
        assertEquals(16, readU16LE(header, 34))
        assertEquals("data", ascii(header, 36, 4))
        // Subchunk2Size
        assertEquals(dataByteLength.toLong(), readU32LE(header, 40))
    }

    @Test
    fun `encodePcmWav encodes float samples into 16-bit signed integer WAV`() {
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
        val wav = encodePcmWav(samples, 16000)

        assertEquals(44 + samples.size * 2, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))

        val data = wav.copyOfRange(44, wav.size)
        assertEquals(0, leShort(data, 0))
        // Positive scale uses 32767 (truncated toward zero like the web encoder)
        assertEquals(Math.floor(0.5 * 32767.0).toInt(), leShort(data, 2))
        // Negative scale uses 32768
        assertEquals(Math.floor(-0.5 * 32768.0).toInt(), leShort(data, 4))
        assertEquals(32767, leShort(data, 6))
        assertEquals(-32768, leShort(data, 8))
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt()
        return (high shl 8) or low
    }

    @Test
    fun `encodePcmWav clamps out-of-range samples to -1 and 1`() {
        val samples = floatArrayOf(2.0f, -2.0f)
        val wav = encodePcmWav(samples, 16000)

        val data = wav.copyOfRange(44, wav.size)
        assertEquals(32767, leShort(data, 0))
        assertEquals(-32768, leShort(data, 2))
    }

    @Test
    fun `encodePcmWav truncates fractional samples toward zero like the web encoder`() {
        // -0.7 * 0x8000 = -22937.6: JS Int16Array assignment truncates toward zero
        // (-22937), it does not floor (-22938). Positive 0.3 * 0x7fff = 9830.1.
        val samples = floatArrayOf(-0.7f, 0.3f)
        val wav = encodePcmWav(samples, 16000)

        val data = wav.copyOfRange(44, wav.size)
        assertEquals(-22937, leShort(data, 0))
        assertEquals(9830, leShort(data, 2))
    }

    @Test
    fun `downsampleTo16kHz downsamples a 48 kHz buffer to 16 kHz mono correctly`() {
        val input48k = FloatArray(48000) { i ->
            Math.sin((i / 48000.0) * 2.0 * Math.PI * 440.0).toFloat()
        }

        val output16k = downsampleTo16kHz(input48k, 48000)

        assertEquals(16000, output16k.size)
    }

    @Test
    fun `downsampleTo16kHz leaves a 16 kHz buffer unchanged`() {
        val input16k = FloatArray(16000)
        input16k[0] = 0.42f

        val output = downsampleTo16kHz(input16k, 16000)

        assertSame(input16k, output)
    }

    @Test
    fun `downsampleTo16kHz linearly interpolates between neighbouring samples`() {
        // 2 samples at 32 kHz -> ratio 2 -> single output sample at original index 0
        val input = floatArrayOf(0.0f, 1.0f)
        val output = downsampleTo16kHz(input, 32000)
        assertEquals(1, output.size)
        assertEquals(0.0f, output[0], 1e-6f)
    }

    @Test
    fun `downsampleTo16kHz rounds new length to nearest sample count`() {
        // ratio 1.5 -> length 3 -> round(3/1.5)=2 outputs; last index reads clamp at end
        val input = floatArrayOf(1.0f, 2.0f, 3.0f)
        val output = downsampleTo16kHz(input, 24000)
        assertEquals(2, output.size)
        assertEquals(1.0f, output[0], 1e-6f)
        // Interpolates midway between input samples 2 and 3.
        assertEquals(2.5f, output[1], 1e-6f)
    }

    @Test
    fun `RecordingBuffer tracks duration from appended chunks at the input sample rate`() {
        val buffer = RecordingBuffer(inputSampleRate = 48000)
        assertTrue(buffer.isEmpty)

        buffer.append(FloatArray(4800)) // 100 ms at 48 kHz
        buffer.append(FloatArray(2400)) // 50 ms at 48 kHz

        assertFalse(buffer.isEmpty)
        assertEquals(150L, buffer.durationMs)
        assertFalse(buffer.shouldAutoStop)
    }

    @Test
    fun `RecordingBuffer flags auto-stop when accumulated duration reaches the shared limit`() {
        val buffer = RecordingBuffer(inputSampleRate = 16000)
        assertTrue(buffer.isEmpty)

        val chunkMs = 1000L
        var appendedChunks = 0
        while (!buffer.shouldAutoStop) {
            buffer.append(FloatArray((chunkMs * 16000 / 1000L).toInt()))
            appendedChunks++
        }

        assertEquals(120, appendedChunks)
        assertTrue(buffer.durationMs >= MAX_RECORDING_DURATION_MS)
    }

    @Test
    fun `RecordingBuffer stops accepting samples once the 4 MB size bound would be exceeded`() {
        val buffer = RecordingBuffer(inputSampleRate = 8000)
        // Upsampled output grows: 8 kHz input maps to double the sample count at 16 kHz.
        // Feed far more than 4 MB worth of projected WAV bytes in one chunk.
        buffer.append(FloatArray(3_000_000))

        assertTrue(buffer.wouldExceedSizeBound)
        // Appending is refused once the bound is reached.
        val before = buffer.totalInputSamples
        buffer.append(FloatArray(1000))
        assertEquals(before, buffer.totalInputSamples)

        // The built WAV still honours the shared 4 MB limit.
        val result = buffer.build()
        assertTrue(result.sizeBytes <= MAX_AUDIO_SIZE_BYTES)
    }

    @Test
    fun `RecordingBuffer build produces bounded 16 kHz mono WAV within shared limits`() {
        val buffer = RecordingBuffer(inputSampleRate = 48000)
        buffer.append(FloatArray(48000)) // exactly one second

        val result = buffer.build()

        assertEquals(44 + 16000 * 2, result.sizeBytes)
        assertEquals("RIFF", ascii(result.wav, 0, 4))
        assertEquals(16000L, readU32LE(result.wav, 24))
        assertEquals(1.0, result.durationSeconds, 0.001)
        assertTrue(result.sizeBytes <= MAX_AUDIO_SIZE_BYTES)
        assertTrue(result.durationSeconds <= MAX_RECORDING_DURATION_MS / 1000.0 + 0.5)
    }

    @Test
    fun `RecordingBuffer build throws when nothing was recorded`() {
        val buffer = RecordingBuffer(inputSampleRate = 16000)
        val error = runCatching { buffer.build() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("No active recording to stop.", error?.message)
    }

    @Test
    fun `RecordingBuffer build returns null preview without consuming the recording`() {
        val buffer = RecordingBuffer(inputSampleRate = 16000)
        assertNull(buffer.buildOrNull())

        buffer.append(floatArrayOf(0.5f))
        val result = buffer.buildOrNull()
        assertTrue(result != null)
        // Buffer still holds its samples after a preview build.
        assertTrue(buffer.build().sizeBytes == result!!.sizeBytes)
    }
}
