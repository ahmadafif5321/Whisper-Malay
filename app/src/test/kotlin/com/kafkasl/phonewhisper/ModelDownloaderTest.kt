package com.kafkasl.phonewhisper

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class ModelDownloaderTest {

    @Test fun `extracts tar bz2 with nested files`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.bz2")
            val outDir = File(tmp, "out")

            writeTarBz2(archive, mapOf(
                "mymodel/tokens.txt" to "hello\nworld",
                "mymodel/encoder.onnx" to "fake-onnx-data",
            ))

            ModelDownloader.extractTarBz2(archive, outDir)

            assertTrue(File(outDir, "mymodel").isDirectory)
            assertEquals("hello\nworld", File(outDir, "mymodel/tokens.txt").readText())
            assertEquals("fake-onnx-data", File(outDir, "mymodel/encoder.onnx").readText())
        }
    }

    @Test fun `rejects path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.tar.bz2")
            writeTarBz2(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractTarBz2(archive, File(tmp, "out"))
            }
        }
    }

    @Test fun `catalog has expected structure`() {
        assertEquals(9, MODEL_CATALOG.size)
        assertTrue(MODEL_CATALOG.any { it.recommended })
        assertTrue(MODEL_CATALOG.filter { it.recommended }.all { DEFAULT_LANGUAGE in it.languages })
        // Models without a custom URL download from the sherpa-onnx releases
        assertTrue(MODEL_CATALOG.all { it.url != null || it.archive.startsWith("sherpa-onnx-") })
        assertFalse(MODEL_CATALOG.any { it.url?.contains("whisper-malay-models") == true })
        assertTrue(MODEL_CATALOG.all { it.sizeMb > 0 })
        assertTrue(MODEL_CATALOG.all { it.languages.isNotEmpty() })
    }

    @Test fun `whisper small onnx entry downloads from huggingface as flat zip`() {
        val model = MODEL_CATALOG.first { it.archive == "whisper_small_int8" }
        assertEquals(listOf("en", "ms"), model.languages)
        assertTrue(model.url!!.startsWith("https://huggingface.co/"))
        assertTrue(model.url!!.endsWith("whisper_small_int8.zip"))
        assertTrue(archiveSupportsLanguage("whisper_small_int8", "en"))
        assertTrue(archiveSupportsLanguage("whisper_small_int8", "ms"))
    }

    @Test fun `onnx whisper language tokens`() {
        assertEquals(50259, OnnxWhisperTranscriber.languageTokenFor("en"))
        assertEquals(50282, OnnxWhisperTranscriber.languageTokenFor("ms"))
        // Unknown codes fall back to the default language
        assertEquals(50282, OnnxWhisperTranscriber.languageTokenFor("xx"))
    }

    @Test fun `default language is bahasa melayu malaysia`() {
        assertEquals("ms", DEFAULT_LANGUAGE)
        val defaultLanguage = APP_LANGUAGES.first { it.code == DEFAULT_LANGUAGE }
        assertEquals("Bahasa Melayu (Malaysia)", defaultLanguage.label)
    }

    @Test fun `onnx whisper dir detection`() {
        withTempDir { tmp ->
            assertFalse(OnnxWhisperTranscriber.isOnnxWhisperDir(tmp))
            File(tmp, "Whisper_initializer.onnx").writeText("fake")
            assertTrue(OnnxWhisperTranscriber.isOnnxWhisperDir(tmp))
        }
    }

    @Test fun `catalog offers multilingual models covering english and malay`() {
        val multilingual = MODEL_CATALOG.filter { "ms" in it.languages }
        assertTrue(multilingual.isNotEmpty())
        assertTrue(multilingual.all { "en" in it.languages })
    }

    @Test fun `language support detection`() {
        // Catalog models use declared languages
        assertTrue(archiveSupportsLanguage("sherpa-onnx-whisper-base", "ms"))
        assertFalse(archiveSupportsLanguage("sherpa-onnx-whisper-base.en", "ms"))
        assertFalse(archiveSupportsLanguage("sherpa-onnx-moonshine-tiny-en-int8", "ms"))
        // Imported whisper models use the ".en" suffix heuristic
        assertTrue(archiveSupportsLanguage("sherpa-onnx-whisper-small", "ms"))
        assertFalse(archiveSupportsLanguage("sherpa-onnx-whisper-small.en", "ms"))
        // Unknown non-whisper imports are treated as English-only
        assertFalse(archiveSupportsLanguage("my-custom-model", "ms"))
        assertTrue(archiveSupportsLanguage("my-custom-model", "en"))
        // Imported whisper-named models without .en suffix count as multilingual
        assertTrue(archiveSupportsLanguage("my-whisper-model", "ms"))
    }

    @Test fun `extracts tar gz archives`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.tar.gz")
            val outDir = File(tmp, "out")

            writeTarGz(archive, mapOf("mymodel/tokens.txt" to "hello"))
            ModelDownloader.extractArchive(archive, outDir, archive.name)

            assertEquals("hello", File(outDir, "mymodel/tokens.txt").readText())
        }
    }

    @Test fun `extracts zip archives`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.zip")
            val outDir = File(tmp, "out")

            writeZip(archive, mapOf("mymodel/tokens.txt" to "hello"))
            ModelDownloader.extractArchive(archive, outDir, archive.name)

            assertEquals("hello", File(outDir, "mymodel/tokens.txt").readText())
        }
    }

    @Test fun `extracts multi-stream gzip archives fully`() {
        withTempDir { tmp ->
            // pigz/pbzip2 compress in independent concatenated streams; single-stream
            // decompression would truncate the tar after the first chunk
            val tarBytes = java.io.ByteArrayOutputStream().also { bos ->
                TarArchiveOutputStream(bos).use {
                    writeTarEntries(it, mapOf(
                        "mymodel/encoder.onnx" to "x".repeat(4096),
                        "mymodel/tokens.txt" to "hello",
                    ))
                }
            }.toByteArray()

            fun gz(chunk: ByteArray): ByteArray = java.io.ByteArrayOutputStream().also { bos ->
                org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(bos)
                    .use { it.write(chunk) }
            }.toByteArray()

            val mid = tarBytes.size / 2
            val archive = File(tmp, "test.tar.gz")
            FileOutputStream(archive).use {
                it.write(gz(tarBytes.copyOfRange(0, mid)))
                it.write(gz(tarBytes.copyOfRange(mid, tarBytes.size)))
            }

            val outDir = File(tmp, "out")
            ModelDownloader.extractArchive(archive, outDir, archive.name)

            assertEquals("hello", File(outDir, "mymodel/tokens.txt").readText())
        }
    }

    @Test fun `rejects unsupported archive type`() {
        withTempDir { tmp ->
            val archive = File(tmp, "test.rar").apply { writeText("junk") }
            assertThrows(java.io.IOException::class.java) {
                ModelDownloader.extractArchive(archive, File(tmp, "out"), archive.name)
            }
        }
    }

    @Test fun `rejects zip path traversal`() {
        withTempDir { tmp ->
            val archive = File(tmp, "evil.zip")
            writeZip(archive, mapOf("../evil.txt" to "gotcha"))

            assertThrows(IllegalArgumentException::class.java) {
                ModelDownloader.extractArchive(archive, File(tmp, "out"), archive.name)
            }
        }
    }

    @Test fun `bestModelForLanguage returns null when nothing installed`() {
        assertNull(bestModelForLanguage(emptyList(), "ms"))
    }

    @Test fun `bestModelForLanguage returns null when only parakeet installed and language is ms`() {
        val parakeet = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
        assertNull(bestModelForLanguage(listOf(parakeet), "ms"))
    }

    @Test fun `catalog contains malay whisper small with ms support`() {
        val model = MODEL_CATALOG.first { it.archive == "sherpa-onnx-whisper-small" }
        assertEquals("Malay Whisper Small", model.name)
        assertEquals(610, model.sizeMb)
        assertTrue(model.recommended)
        assertTrue("ms" in model.languages)
        assertTrue("en" in model.languages)
        assertNull(model.url)
        assertTrue(archiveSupportsLanguage("sherpa-onnx-whisper-small", "ms"))
    }

    @Test fun `catalog contains malay whisper medium with ms support`() {
        val model = MODEL_CATALOG.first { it.archive == "sherpa-onnx-whisper-medium" }
        assertEquals("Malay Whisper Medium", model.name)
        assertEquals(1842, model.sizeMb)
        assertTrue("ms" in model.languages)
        assertTrue("en" in model.languages)
        assertNull(model.url)
        assertTrue(archiveSupportsLanguage("sherpa-onnx-whisper-medium", "ms"))
    }

    @Test fun `bestModelForLanguage prefers whisper_small_int8 over tiny when both installed and language is ms`() {
        val tiny = "sherpa-onnx-whisper-tiny"
        val small = "whisper_small_int8"
        assertEquals(small, bestModelForLanguage(listOf(tiny, small), "ms"))
    }

    @Test fun `bestModelForLanguage prefers malay whisper small over whisper_small_int8 for ms`() {
        val malay = "sherpa-onnx-whisper-small"
        val small = "whisper_small_int8"
        assertEquals(malay, bestModelForLanguage(listOf(small, malay), "ms"))
    }

    @Test fun `bestModelForLanguage prefers malay whisper medium when both malay models installed for ms`() {
        val medium = "sherpa-onnx-whisper-medium"
        val small = "sherpa-onnx-whisper-small"
        assertEquals(medium, bestModelForLanguage(listOf(small, medium), "ms"))
    }

    @Test fun `bestModelForLanguage returns en-capable model for en when parakeet installed`() {
        val parakeet = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
        val result = bestModelForLanguage(listOf(parakeet), "en")
        assertNotNull(result)
        assertTrue(archiveSupportsLanguage(result!!, "en"))
    }

    @Test fun `bestModelForLanguage prefers malay-capable model for default language`() {
        val parakeet = "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8"
        val malay = "sherpa-onnx-whisper-small"
        assertEquals(malay, bestModelForLanguage(listOf(parakeet, malay), DEFAULT_LANGUAGE))
    }

    @Test fun `archive filename from hugging face resolve url keeps zip name`() {
        val url = "https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip?download=true"
        assertEquals("whisper_small_int8.zip", ModelDownloader.archiveFileNameFromUrl(url))
        assertTrue(ModelDownloader.isSupportedArchiveName(ModelDownloader.archiveFileNameFromUrl(url)))
    }

    @Test fun `archive filename support rejects unsupported model formats`() {
        assertFalse(ModelDownloader.isSupportedArchiveName("ggml-model.bin"))
        assertFalse(ModelDownloader.isSupportedArchiveName("model.safetensors"))
        assertTrue(ModelDownloader.isSupportedArchiveName("sherpa-onnx-whisper-small.tar.bz2"))
        assertTrue(ModelDownloader.isSupportedArchiveName("whisper_small_int8.zip"))
    }

    @Test fun `bestModelForLanguage counts imported whisper model as capable for ms`() {
        val imported = "my-whisper-model"
        assertEquals(imported, bestModelForLanguage(listOf(imported), "ms"))
    }

    @Test fun `bestModelForLanguage returns null for non-whisper import and ms`() {
        val imported = "my-custom-nemo-model"
        assertNull(bestModelForLanguage(listOf(imported), "ms"))
    }

    // -- helpers --

    private fun withTempDir(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("model-test").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }

    private fun writeTarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(FileOutputStream(file))).use { tar ->
            writeTarEntries(tar, entries)
        }
    }

    private fun writeTarGz(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(
            org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(FileOutputStream(file))
        ).use { tar -> writeTarEntries(tar, entries) }
    }

    private fun writeTarEntries(tar: TarArchiveOutputStream, entries: Map<String, String>) {
        for ((name, content) in entries) {
            val bytes = content.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        java.util.zip.ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
