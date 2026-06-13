package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx, or via ONNX Runtime directly for
 * RTranslator-style Whisper exports. Models are loaded from the app's files dir.
 */
class LocalTranscriber private constructor(
    private val recognizer: OfflineRecognizer?,
    private val onnxWhisper: OnnxWhisperTranscriber?,
) {
    private var released = false

    /**
     * Transcribe raw PCM float samples. Blocking — call from background thread.
     * Synchronized against release() so a model swap can't free native sessions mid-decode.
     */
    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        if (released) return ""
        onnxWhisper?.let { return it.transcribe(samples, sampleRate) }
        val rec = recognizer ?: return ""
        val stream = rec.createStream()
        stream.acceptWaveform(samples, sampleRate)
        rec.decode(stream)
        val result = rec.getResult(stream)
        stream.release()
        return result.text.trim()
    }

    /** Free native resources. The transcriber returns empty results afterwards. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        onnxWhisper?.release()
        recognizer?.release()
    }

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()
                ?.filter { ModelDownloader.isUsableModelDir(it) }
                ?.map { it.name }
                ?: emptyList()
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String, language: String = DEFAULT_LANGUAGE): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            // RTranslator-style Whisper exports run on ONNX Runtime directly
            if (OnnxWhisperTranscriber.isOnnxWhisperDir(modelDir)) {
                val transcriber = OnnxWhisperTranscriber.create(modelDir, language) ?: return null
                Log.i(TAG, "Loaded model: $modelName (onnx whisper)")
                return LocalTranscriber(null, transcriber)
            }

            val config = detectModelConfig(modelDir, language) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            val mc = config.modelConfig
            Log.i(
                TAG,
                "create '$modelName': numThreads=${mc.numThreads} modelType='${mc.modelType}' " +
                    "moonshine=[${mc.moonshine.preprocessor},${mc.moonshine.encoder},${mc.moonshine.uncachedDecoder},${mc.moonshine.cachedDecoder}] " +
                    "whisper=[${mc.whisper.encoder},${mc.whisper.decoder}] " +
                    "transducer=[${mc.transducer.encoder},${mc.transducer.decoder},${mc.transducer.joiner}] " +
                    "nemo=[${mc.nemo.model}]"
            )

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer, null)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load model: ${t.message}", t)
                null
            }
        }

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File, language: String): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            // Whisper archives name it e.g. "base.en-tokens.txt", others plain "tokens.txt"
            val tokens = dir.listFiles()?.firstOrNull { it.name.endsWith("tokens.txt") }
                ?.absolutePath ?: return null

            // Moonshine (has preprocess.onnx); its files are unprefixed, so match by exact prefix —
            // a contains-match would confuse cached_decode with uncached_decode
            if (File("$p/preprocess.onnx").exists()) {
                fun byPrefix(prefix: String): String? {
                    val files = dir.listFiles() ?: return null
                    return (files.firstOrNull { it.name.startsWith(prefix) && it.name.contains("int8") }
                        ?: files.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
                        )?.absolutePath
                }
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = "$p/preprocess.onnx",
                            encoder = byPrefix("encode") ?: return null,
                            uncachedDecoder = byPrefix("uncached_decode") ?: return null,
                            cachedDecoder = byPrefix("cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            val whisperEncoder = findFile(p, "encoder")
            val whisperDecoder = findFile(p, "decoder")
            if (whisperEncoder != null && whisperDecoder != null && findFile(p, "joiner") == null) {
                // ".en" archives are English-only; only multilingual Whisper accepts a language hint
                val multilingual = !isEnglishOnlyWhisperName(dir.name)
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                            language = if (multilingual) language else "en",
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = 1,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC (single model.onnx / model.int8.onnx)
            val ctcModel = findFile(p, "model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            return null
        }

        /**
         * Find the model file for a marker (prefer int8 quantized).
         * The marker must sit on a token boundary because Whisper archives prefix
         * files with the model name, e.g. "base.en-encoder.int8.onnx". Files are
         * sorted so selection is deterministic regardless of filesystem order.
         */
        private fun findFile(dir: String, marker: String): String? {
            val files = File(dir).listFiles()?.sortedBy { it.name } ?: return null
            val boundary = Regex("(^|[._-])${Regex.escape(marker)}([._-]|$)")
            fun matches(name: String) = boundary.containsMatchIn(name)
            val match = files.firstOrNull { matches(it.name) && it.name.contains("int8") }
                ?: files.firstOrNull {
                    matches(it.name) && (it.name.endsWith(".onnx") || it.name.endsWith(".ort"))
                }
            return match?.absolutePath
        }
    }
}
