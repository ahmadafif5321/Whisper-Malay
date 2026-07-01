package com.kafkasl.phonewhisper

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * UI-free dictation core: microphone capture, IDLE/RECORDING/TRANSCRIBING state,
 * local (sherpa/ONNX) or Gemini transcription, and optional Gemini cleanup.
 * Shared by the voice keyboard (WhisperImeService) and the optional accessibility
 * floating button. Callbacks fire on background threads.
 */
class DictationEngine(private val context: Context) {

    enum class State { IDLE, RECORDING, TRANSCRIBING }

    interface Listener {
        fun onState(state: State)
        fun onResult(text: String)
        fun onInfo(message: String)
        fun onError(message: String)
    }

    @Volatile var state: State = State.IDLE
        private set

    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null

    @Volatile private var localTranscriber: LocalTranscriber? = null
    private val modelLoadLock = Any()

    private fun prefs() = context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
    private fun setState(s: Listener, value: State) { state = value; s.onState(value) }

    /** Load/reload the local model. Mirrors the old initLocalModel(). Call off the UI thread. */
    fun loadModel(info: (String) -> Unit = {}) = synchronized(modelLoadLock) {
        val modelName = prefs().getString("model_name", "") ?: ""
        val language = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        val models = LocalTranscriber.availableModels(context)
        val candidates = localModelCandidates(models, modelName, language)

        val old = localTranscriber
        localTranscriber = null
        var loadedModel: String? = null
        for (candidate in candidates) {
            val loaded = LocalTranscriber.create(context, candidate, language)
            if (loaded != null) { localTranscriber = loaded; loadedModel = candidate; break }
            AppDiagnostics.addLog(context, "Model load failed: $candidate")
        }
        old?.release()

        if (loadedModel != null && loadedModel != modelName) {
            prefs().edit().putString("model_name", loadedModel).apply()
            val name = MODEL_CATALOG.firstOrNull { it.archive == loadedModel }?.name ?: loadedModel
            info("Using $name")
        }
        if (localTranscriber != null && loadedModel != null && !archiveSupportsLanguage(loadedModel, language)) {
            val name = MODEL_CATALOG.firstOrNull { it.archive == loadedModel }?.name ?: loadedModel
            info("Model $name is English-only — dictation will be English")
        } else if (localTranscriber == null && candidates.isNotEmpty()) {
            AppDiagnostics.addLog(context, "All local model candidates failed: ${candidates.joinToString(", ")}")
            info("Model load failed: ${candidates.first()}")
        }
    }

    fun hasLocalTranscriber(): Boolean = localTranscriber != null

    /** Begin capture. Returns false if RECORD_AUDIO is not granted. */
    fun startRecording(listener: Listener): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return false

        val bufSize = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { return false }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        setState(listener, State.RECORDING)

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) pcmStream?.write(buf, 0, n)
            }
        }
        return true
    }

    fun stopAndTranscribe(listener: Listener) {
        setState(listener, State.TRANSCRIBING)
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null
        if (pcm.isEmpty()) { setState(listener, State.IDLE); listener.onError("No audio captured"); return }

        val useLocal = prefs().getBoolean("use_local", true)
        val apiKey = prefs().getString("api_key", "") ?: ""
        when (TranscriptionRouter.decide(useLocal, localTranscriber != null, apiKey.isBlank())) {
            TranscriptionRouter.Route.LOCAL -> transcribeLocal(pcm, localTranscriber!!, listener)
            TranscriptionRouter.Route.API -> transcribeApi(pcm, apiKey, listener)
            TranscriptionRouter.Route.ERROR_NO_API -> { setState(listener, State.IDLE); listener.onError("Set API key in Whisper Malay app") }
        }
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber, listener: Listener) {
        thread {
            try {
                val text = transcriber.transcribe(PcmConverter.toFloatSamples(pcm), 16000)
                deliver(text, listener)
            } catch (t: Throwable) {
                AppDiagnostics.addLog(context, "Local transcription failed: ${t.message}")
                setState(listener, State.IDLE); listener.onError("Local error: ${t.message}")
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray, apiKey: String, listener: Listener) {
        val wav = WavWriter.encode(pcm)
        val language = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        thread {
            TranscriberClient.transcribe(wav, apiKey, language) { result ->
                if (!result.text.isNullOrBlank()) deliver(result.text, listener)
                else { setState(listener, State.IDLE); listener.onError("Error: ${result.error ?: "empty transcript"}") }
            }
        }
    }

    /** Optional Gemini cleanup, then deliver final text. */
    private fun deliver(text: String?, listener: Listener) {
        if (text.isNullOrBlank()) { setState(listener, State.IDLE); listener.onError("No speech detected"); return }
        val usePost = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (usePost && apiKey.isNotBlank()) {
            val language = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
            val raw = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            PostProcessor.process(text, PostProcessor.promptForLanguage(raw, language), apiKey) { r ->
                val out = if (!r.text.isNullOrBlank()) r.text else text
                if (r.text.isNullOrBlank()) listener.onInfo("Cleanup failed — used raw text")
                AppDiagnostics.addTranscript(context, out)
                setState(listener, State.IDLE); listener.onResult(out)
            }
        } else {
            if (usePost && apiKey.isBlank()) listener.onInfo("Post-processing needs API key. Using raw text.")
            AppDiagnostics.addTranscript(context, text)
            setState(listener, State.IDLE); listener.onResult(text)
        }
    }

    fun cancel() {
        audioRecord?.let { runCatching { it.stop() }; runCatching { it.release() } }
        audioRecord = null; pcmStream = null; state = State.IDLE
    }

    fun release() {
        cancel()
        synchronized(modelLoadLock) { localTranscriber?.release(); localTranscriber = null }
    }
}
