package com.cras.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Shared structural assertions for the WAV contract of voice capture. */
internal object WavAssertions {

    const val HEADER_LENGTH = 44

    /**
     * Asserts a standard 44-byte RIFF/WAVE header for mono 16 kHz PCM16 whose
     * data section holds [expectedDataBytes] bytes.
     */
    fun assertRiffWavePcm16Header(wav: ByteArray, expectedDataBytes: Int) {
        assertTrue(
            "Expected nonzero payload beyond the 44-byte header",
            expectedDataBytes > 0,
        )
        assertTrue("WAV shorter than its 44-byte header", wav.size >= HEADER_LENGTH)
        val header = ByteBuffer.wrap(wav, 0, HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", ascii(header))
        assertEquals(wav.size - 8, header.int)
        assertEquals("WAVE", ascii(header))
        assertEquals("fmt ", ascii(header))
        assertEquals(16, header.int) // PCM subchunk1 size
        assertEquals(1, header.short.toInt()) // AudioFormat = PCM
        assertEquals(1, header.short.toInt()) // mono
        assertEquals(TARGET_SAMPLE_RATE, header.int) // 16 kHz framing
        assertEquals(TARGET_SAMPLE_RATE * 2, header.int) // byte rate
        assertEquals(2, header.short.toInt()) // block align
        assertEquals(16, header.short.toInt()) // bits per sample
        assertEquals("data", ascii(header))
        assertEquals(expectedDataBytes, header.int)
        assertEquals(
            "Data section must hold whole PCM16 frames",
            0,
            expectedDataBytes % 2,
        )
    }

    private fun ascii(header: ByteBuffer): String {
        val bytes = ByteArray(4)
        header.get(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}
