package com.kafkasl.phonewhisper

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import java.net.URI
import java.util.concurrent.TimeUnit

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    val languages: List<String> = listOf("en"),
    /**
     * Custom download URL for models not hosted in the sherpa-onnx releases.
     * These archives are flat (no top-level dir) and extract into models/{archive}/.
     */
    val url: String? = null,
)

data class AppLanguage(val code: String, val label: String, val englishName: String, val hint: String)

const val DEFAULT_LANGUAGE = "ms"

val APP_LANGUAGES = listOf(
    AppLanguage("ms", "Bahasa Melayu (Malaysia)", "Malay", "Default"),
    AppLanguage("en", "English (US)", "English", "English"),
)

val MODEL_CATALOG = listOf(
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value"),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, "★★★"),
    Model("Whisper Tiny Multilingual", "sherpa-onnx-whisper-tiny",
        111, "★★☆ Fast", languages = listOf("en", "ms")),
    Model("Whisper Base Multilingual", "sherpa-onnx-whisper-base",
        198, "★★★", languages = listOf("en", "ms")),
    Model("Whisper Small Multilingual", "whisper_small_int8",
        232, "★★★★ Best HF ONNX", languages = listOf("en", "ms"),
        url = "https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip"),
    Model("Malay Whisper Small", "sherpa-onnx-whisper-small",
        610, "★★★★★ Recommended for Malay", recommended = true, languages = listOf("ms", "en")),
    Model("Malay Whisper Medium", "sherpa-onnx-whisper-medium",
        1842, "★★★★★ Best quality, needs more RAM", languages = listOf("ms", "en")),
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality"),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Fast"),
)

/** Whisper model dirs with a ".en" suffix (e.g. sherpa-onnx-whisper-base.en) are English-only. */
fun isEnglishOnlyWhisperName(name: String) = name.lowercase().endsWith(".en")

/**
 * Whether a model dir (catalog or imported) can transcribe the given language.
 * Catalog models use their declared language list. Imported Whisper archives use the
 * ".en" suffix heuristic. All other unknown archives are treated as English-only.
 */
fun archiveSupportsLanguage(archive: String, code: String): Boolean {
    MODEL_CATALOG.firstOrNull { it.archive == archive }?.let { return code in it.languages }
    if (code == "en") return true
    if (archive.lowercase().contains("whisper")) return !isEnglishOnlyWhisperName(archive)
    return false
}

private val MULTILINGUAL_PREFERENCE = listOf(
    "sherpa-onnx-whisper-medium",
    "sherpa-onnx-whisper-small",
    "whisper_small_int8",
    "sherpa-onnx-whisper-base",
    "sherpa-onnx-whisper-tiny",
)

/**
 * Returns the best installed model archive that supports [code], or null if none does.
 * Among catalog models that support the language, prefers by [MULTILINGUAL_PREFERENCE] order
 * then catalog order for quality. Imported/unknown models are included if
 * [archiveSupportsLanguage] returns true.
 */
fun bestModelForLanguage(installed: List<String>, code: String): String? {
    val capable = installed.filter { archiveSupportsLanguage(it, code) }
    if (capable.isEmpty()) return null

    val preferred = MULTILINGUAL_PREFERENCE.firstOrNull { it in capable }
    if (preferred != null) return preferred

    val byCatalog = MODEL_CATALOG.map { it.archive }.filter { it in capable }
    return byCatalog.firstOrNull() ?: capable.first()
}

/**
 * Load order for local dictation. The selected model gets first chance only when it
 * supports the active language; otherwise a better installed language-capable model
 * is tried first. If no capable model exists, the selected model is kept as a last
 * resort so English-only setups still work for English dictation.
 */
fun localModelCandidates(installed: List<String>, selected: String, code: String): List<String> {
    val ordered = linkedSetOf<String>()
    val installedSet = installed.toSet()

    if (selected.isNotBlank() && selected in installedSet && archiveSupportsLanguage(selected, code)) {
        ordered += selected
    }

    bestModelForLanguage(installed.filter { it != selected }, code)?.let { ordered += it }
    installed
        .filter { it != selected && archiveSupportsLanguage(it, code) }
        .forEach { ordered += it }

    if (selected.isNotBlank() && selected in installedSet) ordered += selected
    if (ordered.isEmpty()) installed.forEach { ordered += it }

    return ordered.toList()
}

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Imported(val modelName: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    fun isInstalled(ctx: Context, model: Model) =
        isUsableModelDir(modelDir(ctx, model))

    fun isUsableModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (OnnxWhisperTranscriber.isOnnxWhisperDir(dir)) {
            return OnnxWhisperTranscriber.missingRequiredFiles(dir).isEmpty()
        }

        val files = dir.listFiles()?.map { it.name } ?: return false
        val hasTokens = files.any { it.endsWith("tokens.txt") }
        if (!hasTokens) return false

        val hasWhisper = files.any { it.containsBoundary("encoder") } &&
            files.any { it.containsBoundary("decoder") } &&
            files.none { it.containsBoundary("joiner") }
        val hasTransducer = files.any { it.containsBoundary("encoder") } &&
            files.any { it.containsBoundary("decoder") } &&
            files.any { it.containsBoundary("joiner") }
        val hasCtc = files.any { it.containsBoundary("model") }
        val hasMoonshine = files.any { it == "preprocess.onnx" } &&
            files.any { it.startsWith("encode") } &&
            files.any { it.startsWith("uncached_decode") } &&
            files.any { it.startsWith("cached_decode") }

        return hasWhisper || hasTransducer || hasCtc || hasMoonshine
    }

    /**
     * Download and extract model. Callbacks fire on background thread.
     * Extracts into a staging dir and moves into files/models/ only once complete and
     * validated, so an interrupted download never leaves a partial dir that looks installed.
     */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = model.url ?: "$BASE_URL/${model.archive}.tar.bz2"
        val fileName = url.substringAfterLast('/')
        val tmpFile = File(ctx.cacheDir, "dl-${System.nanoTime()}-$fileName")
        val stagingDir = File(ctx.cacheDir, "dl-staging-${System.nanoTime()}")

        Thread {
            try {
                downloadFile(url, tmpFile, onState)
                onState(DownloadState.Extracting)
                extractArchive(tmpFile, stagingDir, fileName)
                installStaged(ctx, stagingDir, fileName)
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                tmpFile.delete()
                stagingDir.deleteRecursively()
            }
        }.start()
    }

    /**
     * Download a user-provided direct archive URL, including Hugging Face
     * /resolve/ links. The archive must still validate as a supported local ASR
     * model before it is installed.
     */
    fun downloadFromUrl(ctx: Context, url: String, onState: (DownloadState) -> Unit) {
        Thread {
            val cleanUrl = url.trim()
            val fileName = archiveFileNameFromUrl(cleanUrl)
            val tmpFile = File(ctx.cacheDir, "url-${System.nanoTime()}-$fileName")
            val stagingDir = File(ctx.cacheDir, "url-staging-${System.nanoTime()}")
            try {
                require(isSupportedArchiveName(fileName)) {
                    "URL must end with .tar.bz2, .tar.gz, .tar, or .zip"
                }
                downloadFile(cleanUrl, tmpFile, onState)
                onState(DownloadState.Extracting)
                extractArchive(tmpFile, stagingDir, fileName)
                val modelName = installStaged(ctx, stagingDir, fileName)
                onState(DownloadState.Imported(modelName))
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                tmpFile.delete()
                stagingDir.deleteRecursively()
            }
        }.start()
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    /**
     * Import a manually downloaded model archive picked via the system file picker.
     * Copies the content, extracts it, and installs it under files/models/.
     * Callbacks fire on a background thread.
     */
    fun importModel(ctx: Context, uri: Uri, displayName: String, onState: (DownloadState) -> Unit) {
        Thread {
            // Display name comes from an arbitrary content provider — keep only the basename
            val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            val tmpArchive = File(ctx.cacheDir, "import-$safeName")
            val stagingDir = File(ctx.cacheDir, "import-staging-${System.nanoTime()}")
            try {
                ctx.contentResolver.openInputStream(uri)?.use { src ->
                    FileOutputStream(tmpArchive).use { src.copyTo(it) }
                } ?: throw IOException("Cannot open selected file")
                onState(DownloadState.Extracting)
                extractArchive(tmpArchive, stagingDir, safeName)
                val modelName = installStaged(ctx, stagingDir, safeName)
                onState(DownloadState.Imported(modelName))
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                tmpArchive.delete()
                stagingDir.deleteRecursively()
            }
        }.start()
    }

    /** Move an extracted archive from staging into files/models/, returning the model dir name. */
    private fun installStaged(ctx: Context, stagingDir: File, displayName: String): String {
        val extracted = stagingDir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        val isSherpa = extracted.any { it.endsWith("tokens.txt") }
        val isOnnxWhisper = "Whisper_initializer.onnx" in extracted
        if (!isSherpa && !isOnnxWhisper) {
            val preview = extracted.take(5).joinToString(", ").ifEmpty { "no files" }
            throw IOException(
                "Unrecognized archive (found: $preview). Use a sherpa-onnx ASR archive " +
                    "from github.com/k2-fsa/sherpa-onnx releases or an RTranslator-style " +
                    "Whisper ONNX zip — whisper.cpp/ggml and HF transformers models " +
                    "are not supported"
            )
        }

        // Archives normally contain a single top-level dir; bare-file archives get the file's base name
        val single = stagingDir.listFiles()?.singleOrNull()?.takeIf { it.isDirectory }
        val baseName = displayName.replace(
            Regex("\\.(tar\\.bz2|tar\\.gz|tbz2?|tgz|tar|zip)$", RegexOption.IGNORE_CASE), ""
        )
        val (srcDir, name) = if (single != null) single to single.name else stagingDir to baseName

        if (OnnxWhisperTranscriber.isOnnxWhisperDir(srcDir)) {
            val missing = OnnxWhisperTranscriber.missingRequiredFiles(srcDir)
            if (missing.isNotEmpty()) {
                throw IOException("Incomplete Whisper ONNX archive; missing: ${missing.joinToString(", ")}")
            }
            // Repair spurious ai.onnx.ml opset stamps (ORT 1.17.x rejects opset > 4) so
            // the model loads instead of failing at the first session. See OnnxWhisperTranscriber.
            OnnxWhisperTranscriber.normalizeMlOpset(File(srcDir, "Whisper_initializer.onnx"))
        }
        if (!isUsableModelDir(srcDir)) {
            throw IOException("Incomplete or unsupported ASR model archive")
        }

        val dest = File(ctx.filesDir, "models/$name")
        dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        try {
            if (!srcDir.renameTo(dest)) {
                srcDir.copyRecursively(dest, overwrite = true)
            }
        } catch (e: Exception) {
            // Don't leave a partial model dir that would show up as selectable
            dest.deleteRecursively()
            throw e
        }
        return name
    }

    private fun downloadFile(
        url: String, dest: File, onState: (DownloadState) -> Unit
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { src ->
            FileOutputStream(dest).use { dst ->
                val buf = ByteArray(16384)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    downloaded += n
                    if (total > 0)
                        onState(DownloadState.Downloading(downloaded.toFloat() / total))
                }
            }
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) = extractArchive(archive, outDir, archive.name)

    fun archiveFileNameFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull()
            ?.substringAfterLast('/') ?: url.substringBefore('?').substringAfterLast('/')
        return path.substringAfterLast('\\').ifBlank { "model.zip" }
    }

    fun isSupportedArchiveName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".tar.bz2") || lower.endsWith(".tar.gz") ||
            lower.endsWith(".tbz2") || lower.endsWith(".tbz") ||
            lower.endsWith(".tgz") || lower.endsWith(".tar") ||
            lower.endsWith(".zip")
    }

    /** Extract a model archive (.tar.bz2/.tar.gz/.tar/.zip) to outDir. Validates paths to prevent traversal. */
    fun extractArchive(archive: File, outDir: File, fileName: String) {
        outDir.mkdirs()
        val lower = fileName.lowercase()
        if (lower.endsWith(".zip")) {
            // ZipFile reads via the central directory; streaming zip parsing can skip entries
            ZipFile.Builder().setFile(archive).get().use { zip ->
                for (entry in zip.entries) {
                    writeEntry(outDir, entry.name, entry.isDirectory) { out ->
                        zip.getInputStream(entry).use { it.copyTo(out) }
                    }
                }
            }
            return
        }

        val raw = BufferedInputStream(FileInputStream(archive))
        // decompressConcatenated=true: pbzip2/pigz produce multi-stream archives;
        // single-stream mode would silently truncate the tar after the first chunk
        val compressed = when {
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") ->
                BZip2CompressorInputStream(raw, true)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                GzipCompressorInputStream(raw, true)
            lower.endsWith(".tar") -> raw
            else -> {
                raw.close()
                throw IOException("Unsupported archive type: $fileName (use .tar.bz2, .tar.gz, .tar or .zip)")
            }
        }
        TarArchiveInputStream(compressed).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                writeEntry(outDir, entry.name, entry.isDirectory) { tar.copyTo(it) }
            }
        }
    }

    private fun writeEntry(outDir: File, name: String, isDirectory: Boolean, write: (OutputStream) -> Unit) {
        val dest = File(outDir, name)
        require(dest.canonicalPath.startsWith(outDir.canonicalPath)) {
            "Path traversal: $name"
        }
        if (isDirectory) dest.mkdirs()
        else {
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use(write)
        }
    }
}

private fun String.containsBoundary(marker: String): Boolean {
    val boundary = Regex("(^|[._-])${Regex.escape(marker)}([._-]|$)")
    return boundary.containsMatchIn(this)
}
