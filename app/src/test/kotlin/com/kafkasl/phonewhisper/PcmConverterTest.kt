package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class PcmConverterTest {
    @Test fun `converts little-endian 16-bit samples to normalized floats`() {
        val pcm = byteArrayOf(0x00, 0x00,  0x00, 0x01,  0xFF.toByte(), 0x7F,  0x00, 0x80.toByte())
        val out = PcmConverter.toFloatSamples(pcm)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-7f)
        assertEquals(256f / 32768f, out[1], 1e-7f)
        assertEquals(32767f / 32768f, out[2], 1e-7f)
        assertEquals(-1f, out[3], 1e-7f)
    }

    @Test fun `drops a trailing odd byte`() {
        assertEquals(1, PcmConverter.toFloatSamples(byteArrayOf(0x00, 0x00, 0x7F)).size)
    }

    @Test fun `empty input yields empty output`() {
        assertEquals(0, PcmConverter.toFloatSamples(ByteArray(0)).size)
    }
}
