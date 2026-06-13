package com.kafkasl.phonewhisper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * Runs RTranslator-style Whisper ONNX exports (e.g. huggingface.co/DocWolle/whisperOnnx)
 * directly on ONNX Runtime — these are multi-graph exports that sherpa-onnx cannot load.
 *
 * Pipeline: audio → log-mel (initializer) → encoder → cross-attention KV cache
 * (cache initializer, computed once) → greedy autoregressive decoder → BPE detokenizer.
 */
class OnnxWhisperTranscriber private constructor(
    private val env: OrtEnvironment,
    private val initializer: OrtSession,
    private val encoder: OrtSession,
    private val cacheInitializer: OrtSession,
    private val decoder: OrtSession,
    private val detokenizer: OrtSession,
    private val languageToken: Int,
) {

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        if (samples.isEmpty()) return ""
        val audio = prepare(samples)
        val maxNewTokens = min(MAX_TOKENS, (audio.size / sampleRate + 1) * TOKENS_PER_SECOND)

        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())
        ).use { audioTensor ->
            initializer.run(mapOf("audio_pcm" to audioTensor)).use { melOut ->
                val mel = melOut.get(0) as OnnxTensor
                encoder.run(mapOf("input_features" to mel)).use { encOut ->
                    val hidden = encOut.get(0) as OnnxTensor
                    cacheInitializer.run(mapOf("encoder_hidden_states" to hidden)).use { kv ->
                        val tokens = decode(kv, maxNewTokens)
                        return detokenize(tokens)
                    }
                }
            }
        }
    }

    /**
     * Greedy decode: feed the 4-token prompt one token per step, then feed back the
     * argmax until EOS. The decoder takes the constant encoder KV plus its own
     * growing self-attention KV from the previous step's outputs.
     */
    private fun decode(kv: OrtSession.Result, maxNewTokens: Int): IntArray {
        val layers = (kv.size() / 2).toInt()
        val kvShape = (kv.get("present.0.encoder.key").get() as OnnxTensor).info.shape
        val heads = kvShape[1]
        val headDim = kvShape[3]

        val prompt = intArrayOf(SOT_TOKEN, languageToken, TRANSCRIBE_TOKEN, NO_TIMESTAMPS_TOKEN)
        val tokens = ArrayList<Int>()
        // Self-attention cache starts empty (sequence dim 0) on the first step
        val emptyPast = OnnxTensor.createTensor(
            env, FloatBuffer.allocate(0), longArrayOf(1, heads, 0, headDim)
        )
        var past: OrtSession.Result? = null
        try {
            var step = 0
            var next = -1
            while (true) {
                val inputId = if (step < prompt.size) prompt[step] else next
                val done = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(inputId.toLong())), longArrayOf(1, 1)
                ).use { ids ->
                    val inputs = HashMap<String, OnnxTensor>(4 * layers + 1)
                    inputs["input_ids"] = ids
                    val prev = past
                    for (i in 0 until layers) {
                        // First step has no self-attention history; later steps must
                        // find their KV in the previous result — fail loudly otherwise
                        inputs["past_key_values.$i.decoder.key"] =
                            if (prev == null) emptyPast
                            else prev.get("present.$i.decoder.key").get() as OnnxTensor
                        inputs["past_key_values.$i.decoder.value"] =
                            if (prev == null) emptyPast
                            else prev.get("present.$i.decoder.value").get() as OnnxTensor
                        inputs["past_key_values.$i.encoder.key"] =
                            kv.get("present.$i.encoder.key").get() as OnnxTensor
                        inputs["past_key_values.$i.encoder.value"] =
                            kv.get("present.$i.encoder.value").get() as OnnxTensor
                    }
                    val result = decoder.run(inputs)
                    past?.close()
                    past = result

                    // Predictions start with the logits of the last prompt step
                    if (step >= prompt.size - 1) {
                        @Suppress("UNCHECKED_CAST")
                        val logits =
                            ((result.get("logits").get() as OnnxTensor).value as Array<Array<FloatArray>>)[0][0]
                        next = argmax(logits)
                        tokens.add(next)
                        next == EOS_TOKEN || tokens.size >= maxNewTokens
                    } else false
                }
                if (done) return tokens.toIntArray()
                step++
            }
        } finally {
            past?.close()
            emptyPast.close()
        }
    }

    private fun detokenize(tokens: IntArray): String {
        if (tokens.isEmpty()) return ""
        OnnxTensor.createTensor(
            env, IntBuffer.wrap(tokens), longArrayOf(1, 1, tokens.size.toLong())
        ).use { seq ->
            detokenizer.run(mapOf("sequences" to seq)).use { out ->
                val rows = out.get(0).value as Array<*>
                val text = rows.joinToString("") { row ->
                    (row as Array<*>).joinToString("") { it as String }
                }
                return TOKEN_MARKUP_RE.replace(text, "").trim()
            }
        }
    }

    /** Truncate to 30 s and peak-normalize, matching the reference implementation. */
    private fun prepare(samples: FloatArray): FloatArray {
        val out = samples.copyOf(min(samples.size, MAX_SAMPLES))
        var peak = 0f
        for (s in out) { val a = abs(s); if (a > peak) peak = a }
        if (peak > 0f) for (i in out.indices) out[i] /= peak
        return out
    }

    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }

    fun release() {
        for (s in listOf(initializer, encoder, cacheInitializer, decoder, detokenizer)) {
            runCatching { s.close() }
        }
    }

    companion object {
        private const val TAG = "OnnxWhisperTranscriber"

        private const val SOT_TOKEN = 50258            // <|startoftranscript|>
        private const val EOS_TOKEN = 50257            // <|endoftext|>
        private const val TRANSCRIBE_TOKEN = 50359     // <|transcribe|>
        private const val NO_TIMESTAMPS_TOKEN = 50363  // <|notimestamps|>
        private const val MAX_TOKENS = 445
        private const val TOKENS_PER_SECOND = 30
        private const val MAX_SAMPLES = 16000 * 30
        private val TOKEN_MARKUP_RE = Regex("<\\|[^|>]*\\|>")

        // Whisper language token = 50258 + 1 + index in Whisper's language list
        private val LANGUAGE_TOKENS = mapOf("en" to 50259, "ms" to 50282)

        fun languageTokenFor(code: String): Int =
            LANGUAGE_TOKENS[code] ?: LANGUAGE_TOKENS.getValue(DEFAULT_LANGUAGE)

        val REQUIRED_FILES = listOf(
            "Whisper_initializer.onnx",
            "Whisper_encoder.onnx",
            "Whisper_cache_initializer.onnx",
            "Whisper_decoder.onnx",
            "Whisper_detokenizer.onnx",
        )

        fun isOnnxWhisperDir(dir: File) = File(dir, "Whisper_initializer.onnx").exists()

        fun missingRequiredFiles(dir: File): List<String> =
            REQUIRED_FILES.filter { !File(dir, it).exists() }

        /** Create a transcriber for an RTranslator-style model dir. Returns null on failure. */
        fun create(modelDir: File, language: String): OnnxWhisperTranscriber? {
            val missing = missingRequiredFiles(modelDir)
            if (missing.isNotEmpty()) {
                Log.e(TAG, "Model dir $modelDir missing: $missing")
                return null
            }
            return try {
                val env = OrtEnvironment.getEnvironment()
                // The mel initializer and BPE detokenizer use onnxruntime-extensions custom
                // ops. NO_OPT everywhere except the detokenizer mirrors the reference apps
                // (RTranslator/whisperIME) — the optimizer misbehaves on these quantized graphs.
                fun opts(noOpt: Boolean, batchOne: Boolean = false) =
                    OrtSession.SessionOptions().apply {
                        registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                        if (noOpt) setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                        if (batchOne) setSymbolicDimensionValue("batch_size", 1)
                    }
                fun session(file: String, options: OrtSession.SessionOptions) =
                    env.createSession(File(modelDir, file).absolutePath, options)

                val transcriber = OnnxWhisperTranscriber(
                    env = env,
                    initializer = session("Whisper_initializer.onnx", opts(noOpt = true)),
                    encoder = session("Whisper_encoder.onnx", opts(noOpt = true, batchOne = true)),
                    cacheInitializer = session("Whisper_cache_initializer.onnx", opts(noOpt = true)),
                    decoder = session("Whisper_decoder.onnx", opts(noOpt = true)),
                    detokenizer = session("Whisper_detokenizer.onnx", opts(noOpt = false)),
                    languageToken = languageTokenFor(language),
                )
                Log.i(TAG, "Loaded ONNX whisper model from $modelDir (language=$language)")
                transcriber
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load ONNX whisper model: ${t.message}", t)
                null
            }
        }
    }
}
