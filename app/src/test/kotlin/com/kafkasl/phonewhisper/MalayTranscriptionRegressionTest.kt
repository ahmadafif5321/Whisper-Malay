package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MalayTranscriptionRegressionTest {

    @Test fun `built in malay audio fixture is valid wav`() {
        val wav = MalayRegressionFixtures.audioFixtureWav
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(16000, readInt(wav, 24))
        assertEquals(MalayRegressionFixtures.audioFixturePcmBytes, readInt(wav, 40))
    }

    @Test fun `wer is zero for exact bahasa melayu transcript`() {
        val sample = MalayRegressionFixtures.samples.first()
        assertEquals(0.0, wordErrorRate(sample.expected, sample.expected), 0.0)
    }

    @Test fun `wer tolerates punctuation and casing cleanup`() {
        val sample = MalayRegressionFixtures.samples.first {
            it.expected == "Boleh tak you hantar kat saya esok?"
        }
        assertEquals(0.0, wordErrorRate(sample.expected, "boleh tak you hantar kat saya esok"), 0.0)
    }

    @Test fun `malay and manglish samples stay under regression threshold`() {
        for (sample in MalayRegressionFixtures.samples) {
            val wer = wordErrorRate(sample.expected, sample.candidate)
            assertTrue("${sample.id} WER $wer > ${sample.maxWer}", wer <= sample.maxWer)
        }
    }

    @Test fun `wer detects dropped malay particles`() {
        val reference = "Dia cakap okay je kan, tak payah risau lah."
        val dropped = "Dia cakap okay tak payah risau."
        assertTrue(wordErrorRate(reference, dropped) > 0.20)
    }

    private fun readInt(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)
}

data class MalayRegressionSample(
    val id: String,
    val expected: String,
    val candidate: String,
    val maxWer: Double,
)

object MalayRegressionFixtures {
    val samples = listOf(
        MalayRegressionSample(
            id = "bahasa-melayu-meeting",
            expected = "Saya akan hantar laporan itu petang ini.",
            candidate = "saya akan hantar laporan itu petang ini",
            maxWer = 0.0,
        ),
        MalayRegressionSample(
            id = "manglish-request",
            expected = "Boleh tak you hantar kat saya esok?",
            candidate = "boleh tak you hantar kat saya esok",
            maxWer = 0.0,
        ),
        MalayRegressionSample(
            id = "particles",
            expected = "Dia cakap okay je kan, tak payah risau lah.",
            candidate = "dia cakap okay je kan tak payah risau lah",
            maxWer = 0.0,
        ),
        MalayRegressionSample(
            id = "minor-substitution",
            expected = "Nanti saya call balik selepas lunch.",
            candidate = "nanti saya call balik lepas lunch",
            maxWer = 0.20,
        ),
    )

    const val audioFixtureSampleRate = 16000
    const val audioFixtureDurationMs = 350
    val audioFixturePcmBytes = audioFixtureSampleRate * audioFixtureDurationMs / 1000 * 2
    val audioFixtureWav: ByteArray = WavWriter.encode(sinePcm16(audioFixtureSampleRate, audioFixtureDurationMs))

    private fun sinePcm16(sampleRate: Int, durationMs: Int): ByteArray {
        val samples = sampleRate * durationMs / 1000
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val tone = sin(2.0 * PI * 440.0 * i / sampleRate)
            val value = (tone * 32767.0 * 0.20).toInt()
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }
}

fun wordErrorRate(reference: String, hypothesis: String): Double {
    val ref = tokenizeForWer(reference)
    val hyp = tokenizeForWer(hypothesis)
    if (ref.isEmpty()) return if (hyp.isEmpty()) 0.0 else 1.0

    val prev = IntArray(hyp.size + 1) { it }
    val cur = IntArray(hyp.size + 1)
    for (i in 1..ref.size) {
        cur[0] = i
        for (j in 1..hyp.size) {
            val substitution = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
            val insertion = cur[j - 1] + 1
            val deletion = prev[j] + 1
            cur[j] = minOf(substitution, insertion, deletion)
        }
        for (j in cur.indices) prev[j] = cur[j]
    }
    return prev[hyp.size].toDouble() / ref.size
}

private fun tokenizeForWer(text: String): List<String> =
    text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}']+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
