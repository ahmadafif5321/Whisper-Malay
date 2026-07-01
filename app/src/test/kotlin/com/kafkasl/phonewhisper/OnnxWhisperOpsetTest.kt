package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for OnnxWhisperTranscriber.normalizeMlOpset — the repair for the spurious
 * `ai.onnx.ml` opset-5 stamp on DocWolle/whisperOnnx's Whisper_initializer.onnx that
 * ONNX Runtime 1.17.x rejects at load time.
 *
 * The buffers below mimic the relevant protobuf slice: an OperatorSetIdProto's
 * domain string ("ai.onnx.ml") followed by field-2 (version) tag 0x10 and a
 * single-byte varint version.
 */
class OnnxWhisperOpsetTest {

    private val domain = "ai.onnx.ml".toByteArray(Charsets.US_ASCII)

    /** prefix + "ai.onnx.ml" + 0x10 + <version> + suffix */
    private fun mlImport(version: Int, prefix: ByteArray = byteArrayOf(0x0a, 0x0a)): ByteArray =
        prefix + domain + byteArrayOf(0x10, version.toByte()) + byteArrayOf(0x12, 0x00)

    @Test fun `lowers ai_onnx_ml opset 5 to 4`() {
        withTempFile(mlImport(5)) { f ->
            assertTrue(OnnxWhisperTranscriber.normalizeMlOpset(f))
            val v = f.readBytes()[2 + domain.size + 1].toInt() and 0xFF
            assertEquals(4, v)
        }
    }

    @Test fun `leaves opset 4 untouched and reports no change`() {
        val original = mlImport(4)
        withTempFile(original) { f ->
            assertFalse(OnnxWhisperTranscriber.normalizeMlOpset(f))
            assertArrayEquals(original, f.readBytes())
        }
    }

    @Test fun `is idempotent`() {
        withTempFile(mlImport(5)) { f ->
            assertTrue(OnnxWhisperTranscriber.normalizeMlOpset(f))   // 5 -> 4
            assertFalse(OnnxWhisperTranscriber.normalizeMlOpset(f))  // already 4
        }
    }

    @Test fun `lowers higher opsets like 6 as well`() {
        withTempFile(mlImport(6)) { f ->
            assertTrue(OnnxWhisperTranscriber.normalizeMlOpset(f))
            val v = f.readBytes()[2 + domain.size + 1].toInt() and 0xFF
            assertEquals(4, v)
        }
    }

    @Test fun `does not touch the plain ai_onnx domain version`() {
        // "ai.onnx" (no .ml) at opset 17 must be preserved
        val buf = byteArrayOf(0x0a, 0x07) + "ai.onnx".toByteArray() + byteArrayOf(0x10, 17)
        withTempFile(buf) { f ->
            assertFalse(OnnxWhisperTranscriber.normalizeMlOpset(f))
            assertEquals(17, f.readBytes().last().toInt())
        }
    }

    @Test fun `patches multiple ml imports in one file`() {
        withTempFile(mlImport(5) + mlImport(5)) { f ->
            assertTrue(OnnxWhisperTranscriber.normalizeMlOpset(f))
            val bytes = f.readBytes()
            // both version bytes (immediately after each "ai.onnx.ml" + 0x10) must be 4
            var idx = indexOfSub(bytes, domain, 0)
            var count = 0
            while (idx >= 0) {
                assertEquals(0x10, bytes[idx + domain.size].toInt())
                assertEquals(4, bytes[idx + domain.size + 1].toInt())
                count++
                idx = indexOfSub(bytes, domain, idx + domain.size)
            }
            assertEquals(2, count)
        }
    }

    @Test fun `missing file returns false`() {
        assertFalse(OnnxWhisperTranscriber.normalizeMlOpset(File("/no/such/file.onnx")))
    }

    // -- helpers --

    private fun indexOfSub(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (s in from..haystack.size - needle.size) {
            for (k in needle.indices) if (haystack[s + k] != needle[k]) continue@outer
            return s
        }
        return -1
    }

    private fun withTempFile(content: ByteArray, block: (File) -> Unit) {
        val tmp = Files.createTempFile("opset-test", ".onnx").toFile()
        try { tmp.writeBytes(content); block(tmp) } finally { tmp.delete() }
    }
}
