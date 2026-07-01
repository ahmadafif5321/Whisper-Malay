# Whisper Malay Voice Keyboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bank-safe voice-only keyboard (IME) that dictates text via `InputConnection.commitText()`, while keeping the existing accessibility floating button as an optional, off-by-default power mode.

**Architecture:** Extract the recording + transcription core out of `WhisperAccessibilityService` into a UI-free `DictationEngine` that both front-ends share. Build a new `WhisperImeService` (voice-only keyboard) plus a transparent `MicPermissionActivity` to obtain `RECORD_AUDIO`. Demote the accessibility service to an opt-in "power mode" with a banking warning. No accessibility service and no overlay are active on a fresh install, so banking apps stop blocking.

**Tech Stack:** Kotlin, Android (minSdk 30 / targetSdk 34, arm64-v8a), `InputMethodService`, `AudioRecord`, sherpa-onnx + ONNX Runtime 1.17.1 (local), Gemini (`TranscriberClient`), JUnit 4 unit tests via `testDebugUnitTest`.

## Global Constraints

- Kotlin only; minSdk **30**, targetSdk **34**; ABI **arm64-v8a** only.
- ONNX Runtime pinned at **1.17.1**, onnxruntime-extensions **0.10.0** (do not bump).
- Shared prefs file name is **`"phonewhisper"`** (`Context.MODE_PRIVATE`). Existing keys: `model_name`, `language`, `use_local` (default `true`), `api_key`, `use_post_processing` (default `false`), `post_processing_prompt`.
- Default language constant is `DEFAULT_LANGUAGE = "ms"`.
- Sample rate is **16000** Hz, mono, `ENCODING_PCM_16BIT`.
- UI is built programmatically in Kotlin (this repo has no layout XML for the overlay/settings); follow that pattern.
- Unit tests must run under `./gradlew testDebugUnitTest`; `testOptions.unitTests.isReturnDefaultValues = true` is already set, so `android.util.Log` returns defaults.
- Never add code that hides, spoofs, or evades a bank's fraud detection. The design cooperates (no accessibility/overlay by default); it does not evade.
- Build command: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon <tasks>`.

---

## File Structure

**New files:**
- `app/src/main/kotlin/com/kafkasl/phonewhisper/PcmConverter.kt` — pure 16-bit PCM → float sample conversion.
- `app/src/main/kotlin/com/kafkasl/phonewhisper/TranscriptionRouter.kt` — pure decision: local vs API vs error.
- `app/src/main/kotlin/com/kafkasl/phonewhisper/DictationEngine.kt` — UI-free recording + state machine + transcription orchestration, shared by both front-ends.
- `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperImeService.kt` — voice-only input method.
- `app/src/main/kotlin/com/kafkasl/phonewhisper/MicPermissionActivity.kt` — transparent trampoline that requests `RECORD_AUDIO`.
- `app/src/main/res/xml/ime_config.xml` — input-method metadata.
- `app/src/test/kotlin/com/kafkasl/phonewhisper/PcmConverterTest.kt`
- `app/src/test/kotlin/com/kafkasl/phonewhisper/TranscriptionRouterTest.kt`

**Modified files:**
- `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt` — delegate recording/transcription to `DictationEngine`; keep only overlay UI + text injection.
- `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt` — add keyboard setup card; demote accessibility to "Advanced (may block banking apps)".
- `app/src/main/AndroidManifest.xml` — register the IME service + permission activity.
- `app/src/main/res/values/strings.xml` — new strings (keyboard label, warnings, IME subtype).

---

## Task 1: `PcmConverter` — pure PCM→float extraction

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/PcmConverter.kt`
- Test: `app/src/test/kotlin/com/kafkasl/phonewhisper/PcmConverterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object PcmConverter { fun toFloatSamples(pcm: ByteArray): FloatArray }` — little-endian 16-bit signed PCM to float in [-1, 1), length `pcm.size / 2` (a trailing odd byte is dropped).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class PcmConverterTest {
    @Test fun `converts little-endian 16-bit samples to normalized floats`() {
        // 0x0000 = 0.0 ; 0x0100 = 256/32768 ; 0xFF7F = 32767 (max) ; 0x0080 = -32768 (min)
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon testDebugUnitTest --tests "*PcmConverterTest*"`
Expected: FAIL — `PcmConverter` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.kafkasl.phonewhisper

/** Converts little-endian signed 16-bit PCM bytes to float samples in [-1, 1). */
object PcmConverter {
    fun toFloatSamples(pcm: ByteArray): FloatArray {
        val samples = FloatArray(pcm.size / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
        }
        return samples
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon testDebugUnitTest --tests "*PcmConverterTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/PcmConverter.kt app/src/test/kotlin/com/kafkasl/phonewhisper/PcmConverterTest.kt
git commit -m "feat: extract pure PcmConverter with tests"
```

---

## Task 2: `TranscriptionRouter` — pure route decision

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/TranscriptionRouter.kt`
- Test: `app/src/test/kotlin/com/kafkasl/phonewhisper/TranscriptionRouterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  object TranscriptionRouter {
      enum class Route { LOCAL, API, ERROR_NO_API }
      fun decide(useLocal: Boolean, hasLocalTranscriber: Boolean, apiKeyBlank: Boolean): Route
  }
  ```
  Rules (mirror current `stopAndTranscribe`): prefer LOCAL when `useLocal && hasLocalTranscriber`; otherwise API when `!apiKeyBlank`; otherwise `ERROR_NO_API`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test
import com.kafkasl.phonewhisper.TranscriptionRouter.Route

class TranscriptionRouterTest {
    @Test fun `uses local when enabled and available`() {
        assertEquals(Route.LOCAL, TranscriptionRouter.decide(useLocal = true, hasLocalTranscriber = true, apiKeyBlank = true))
    }
    @Test fun `falls back to api when local unavailable and key present`() {
        assertEquals(Route.API, TranscriptionRouter.decide(useLocal = true, hasLocalTranscriber = false, apiKeyBlank = false))
    }
    @Test fun `uses api when local disabled and key present`() {
        assertEquals(Route.API, TranscriptionRouter.decide(useLocal = false, hasLocalTranscriber = true, apiKeyBlank = false))
    }
    @Test fun `errors when no local and no api key`() {
        assertEquals(Route.ERROR_NO_API, TranscriptionRouter.decide(useLocal = true, hasLocalTranscriber = false, apiKeyBlank = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon testDebugUnitTest --tests "*TranscriptionRouterTest*"`
Expected: FAIL — unresolved `TranscriptionRouter`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.kafkasl.phonewhisper

/** Pure decision for how a captured utterance should be transcribed. */
object TranscriptionRouter {
    enum class Route { LOCAL, API, ERROR_NO_API }

    fun decide(useLocal: Boolean, hasLocalTranscriber: Boolean, apiKeyBlank: Boolean): Route = when {
        useLocal && hasLocalTranscriber -> Route.LOCAL
        !apiKeyBlank -> Route.API
        else -> Route.ERROR_NO_API
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon testDebugUnitTest --tests "*TranscriptionRouterTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/TranscriptionRouter.kt app/src/test/kotlin/com/kafkasl/phonewhisper/TranscriptionRouterTest.kt
git commit -m "feat: add pure TranscriptionRouter with tests"
```

---

## Task 3: `DictationEngine` — shared recording + transcription core

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/DictationEngine.kt`

**Interfaces:**
- Consumes: `PcmConverter.toFloatSamples`, `TranscriptionRouter.decide`, existing `LocalTranscriber` (`availableModels`, `create`, `transcribe`, `release`), `WavWriter.encode`, `TranscriberClient.transcribe`, `PostProcessor.process`/`promptForLanguage`/`DEFAULT_PROMPT`, `localModelCandidates`, `MODEL_CATALOG`, `archiveSupportsLanguage`, `AppDiagnostics`, `DEFAULT_LANGUAGE`.
- Produces:
  ```kotlin
  class DictationEngine(private val context: Context) {
      enum class State { IDLE, RECORDING, TRANSCRIBING }
      interface Listener {
          fun onState(state: State)
          fun onResult(text: String)
          fun onInfo(message: String)   // non-fatal, e.g. "Using <model>"
          fun onError(message: String)
      }
      val state: State
      fun loadModel()                          // was initLocalModel(); safe to call on a background thread
      fun startRecording(listener: Listener): Boolean   // false if RECORD_AUDIO not granted
      fun stopAndTranscribe(listener: Listener)
      fun cancel()
      fun release()
  }
  ```
  Callbacks are invoked on a background thread; the caller marshals to its own UI thread.

This task has no pure unit test (it drives `AudioRecord` and native transcription). It is verified by compilation plus the behavior-parity check in Task 4. Move logic verbatim from `WhisperAccessibilityService` — do not redesign it here.

- [ ] **Step 1: Create the engine (move logic out of the service verbatim)**

```kotlin
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
        AppDiagnostics.addTranscript(context, text)
        val usePost = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (usePost && apiKey.isNotBlank()) {
            val language = prefs().getString("language", DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
            val raw = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            PostProcessor.process(text, PostProcessor.promptForLanguage(raw, language), apiKey) { r ->
                val out = if (!r.text.isNullOrBlank()) r.text else text
                setState(listener, State.IDLE); listener.onResult(out)
            }
        } else {
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
```

- [ ] **Step 2: Verify it compiles**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/DictationEngine.kt
git commit -m "feat: add shared DictationEngine core (not yet wired)"
```

---

## Task 4: Wire the accessibility service onto `DictationEngine` (no behavior change)

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt`

**Interfaces:**
- Consumes: `DictationEngine` (Task 3).
- Produces: no new public API; the service keeps `fun reloadModel()` for `MainActivity`.

Replace the service's private recording/model/transcription internals (fields
`audioRecord`, `pcmStream`, `localTranscriber`, `modelLoadLock`; methods
`initLocalModel`, `reloadModel`'s body, `startRecording`, `stopAndTranscribe`,
`transcribeLocal`, `transcribeApi`, `handleTranscriptionResult`) with calls into a
`DictationEngine` instance. Keep all overlay UI, the state colors, and `injectText`
(the accessibility path still injects rather than commits).

- [ ] **Step 1: Add the engine + a listener that drives the existing overlay UI**

In `WhisperAccessibilityService`, add:

```kotlin
private lateinit var engine: DictationEngine

private val engineListener = object : DictationEngine.Listener {
    override fun onState(state: DictationEngine.State) = handler.post {
        when (state) {
            DictationEngine.State.RECORDING -> { setBusy(false); setAppearance(COLOR_RECORDING); startPulse() }
            DictationEngine.State.TRANSCRIBING -> { stopPulse(); setAppearance(COLOR_BUSY); setBusy(true) }
            DictationEngine.State.IDLE -> { stopPulse(); setBusy(false); setAppearance(COLOR_IDLE) }
        }
    }
    override fun onResult(text: String) = handler.post { injectText(text) }
    override fun onInfo(message: String) = toast(message)
    override fun onError(message: String) = toast(message)
}
```

In `onServiceConnected()` replace the `initLocalModel()` thread with:

```kotlin
engine = DictationEngine(this)
showOverlay()
thread { try { engine.loadModel { toast(it) } } catch (t: Throwable) { toast("Model load failed: ${t.message}") } }
```

- [ ] **Step 2: Point the tap handler and lifecycle at the engine**

Replace `onTap()` bodies and `reloadModel()`/`onDestroy()`:

```kotlin
private fun onTap() {
    when (engine.state) {
        DictationEngine.State.IDLE -> {
            if (!engine.startRecording(engineListener)) toast("Grant audio permission in Whisper Malay app")
        }
        DictationEngine.State.RECORDING -> engine.stopAndTranscribe(engineListener)
        DictationEngine.State.TRANSCRIBING -> {}
    }
}

fun reloadModel() { thread { try { engine.loadModel { toast(it) } } catch (t: Throwable) { toast("Model load failed: ${t.message}") } } }
```

In `onDestroy()` replace the `localTranscriber` release block with `engine.release()`.

Then delete the now-unused fields/methods listed above (`audioRecord`, `pcmStream`,
`localTranscriber`, `modelLoadLock`, `initLocalModel`, `startRecording`,
`stopAndTranscribe`, `transcribeLocal`, `transcribeApi`, `handleTranscriptionResult`,
`reset`). Keep `injectText`, node-finding, overlay, and helper UI methods.

- [ ] **Step 3: Verify build + existing tests still pass**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL; all existing tests green (no test references the deleted methods).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt
git commit -m "refactor: drive accessibility service through DictationEngine (no behavior change)"
```

---

## Task 5: `MicPermissionActivity` — RECORD_AUDIO trampoline

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/MicPermissionActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: an exported-false, transparent, `noHistory` activity that requests
  `RECORD_AUDIO` and finishes. Launched with `FLAG_ACTIVITY_NEW_TASK`.

- [ ] **Step 1: Create the activity**

```kotlin
package com.kafkasl.phonewhisper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Transparent trampoline so the IME (which cannot prompt directly) can obtain RECORD_AUDIO. */
class MicPermissionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish(); return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        finish()
    }
}
```

- [ ] **Step 2: Register it in the manifest (add inside `<application>`)**

```xml
<activity
    android:name=".MicPermissionActivity"
    android:exported="false"
    android:noHistory="true"
    android:excludeFromRecents="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

- [ ] **Step 3: Verify build**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/MicPermissionActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add RECORD_AUDIO permission trampoline for the keyboard"
```

---

## Task 6: `WhisperImeService` — the voice-only keyboard

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperImeService.kt`
- Create: `app/src/main/res/xml/ime_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `DictationEngine` (Task 3), `MicPermissionActivity` (Task 5).
- Produces: the input method the user selects to dictate.

- [ ] **Step 1: Add strings**

In `strings.xml` add:

```xml
<string name="ime_label">Whisper Malay (voice)</string>
<string name="ime_mic_idle">Tap to speak</string>
<string name="ime_mic_recording">Recording… tap to stop</string>
<string name="ime_mic_busy">Transcribing…</string>
<string name="ime_grant_mic">Grant microphone permission</string>
```

- [ ] **Step 2: Create the IME config XML**

`app/src/main/res/xml/ime_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android">
    <subtype
        android:label="@string/ime_label"
        android:imeSubtypeLocale="ms_MY"
        android:imeSubtypeMode="keyboard" />
</input-method>
```

- [ ] **Step 3: Create the input method service (programmatic voice panel)**

```kotlin
package com.kafkasl.phonewhisper

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Voice-only keyboard: a mic button plus backspace / space / enter and a
 * switch-keyboard key. Types transcribed text via commitText — no accessibility,
 * no overlay, so banking apps do not block it.
 */
class WhisperImeService : InputMethodService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var engine: DictationEngine
    private var status: TextView? = null
    private var mic: Button? = null

    private val listener = object : DictationEngine.Listener {
        override fun onState(state: DictationEngine.State) = handler.post {
            when (state) {
                DictationEngine.State.RECORDING -> setStatus(getString(R.string.ime_mic_recording))
                DictationEngine.State.TRANSCRIBING -> setStatus(getString(R.string.ime_mic_busy))
                DictationEngine.State.IDLE -> setStatus(getString(R.string.ime_mic_idle))
            }
        }
        override fun onResult(text: String) = handler.post {
            currentInputConnection?.commitText(text, 1)
        }
        override fun onInfo(message: String) = handler.post { setStatus(message) }
        override fun onError(message: String) = handler.post { setStatus(message) }
    }

    override fun onCreate() {
        super.onCreate()
        engine = DictationEngine(this)
        Thread { runCatching { engine.loadModel() } }.start()
    }

    override fun onCreateInputView(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(12), px(12), px(12))
        }
        status = TextView(this).apply {
            text = getString(R.string.ime_mic_idle)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, px(8))
        }
        mic = Button(this).apply {
            text = "🎤"
            textSize = 28f
            setOnClickListener { onMicTap() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(Button(this@WhisperImeService).apply { text = "⌫"; setOnClickListener { backspace() } }, lp)
            addView(Button(this@WhisperImeService).apply { text = "espasi"; setOnClickListener { commit(" ") } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(Button(this@WhisperImeService).apply { text = "⏎"; setOnClickListener { commit("\n") } }, lp)
            addView(Button(this@WhisperImeService).apply { text = "🌐"; setOnClickListener { switchKeyboard() } }, lp)
        }
        root.addView(status)
        root.addView(mic, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(64)))
        root.addView(row)
        return root
    }

    private fun onMicTap() {
        when (engine.state) {
            DictationEngine.State.IDLE -> {
                if (!engine.startRecording(listener)) {
                    setStatus(getString(R.string.ime_grant_mic))
                    startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            DictationEngine.State.RECORDING -> engine.stopAndTranscribe(listener)
            DictationEngine.State.TRANSCRIBING -> {}
        }
    }

    private fun commit(s: String) { currentInputConnection?.commitText(s, 1) }
    private fun backspace() { currentInputConnection?.deleteSurroundingText(1, 0) }
    private fun switchKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }
    private fun setStatus(text: String) { status?.text = text }

    override fun onDestroy() { engine.release(); super.onDestroy() }
}
```

- [ ] **Step 4: Register the IME in the manifest (add inside `<application>`)**

```xml
<service
    android:name=".WhisperImeService"
    android:exported="true"
    android:permission="android.permission.BIND_INPUT_METHOD">
    <intent-filter>
        <action android:name="android.view.InputMethod" />
    </intent-filter>
    <meta-data
        android:name="android.view.im"
        android:resource="@xml/ime_config" />
</service>
```

- [ ] **Step 5: Verify build**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon assembleDebug`
Expected: BUILD SUCCESSFUL. The APK now contains an input method the user can enable.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperImeService.kt app/src/main/res/xml/ime_config.xml app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "feat: add Whisper Malay voice keyboard (IME)"
```

---

## Task 7: Setup UX — keyboard-first, accessibility demoted

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `WhisperImeService`, Android input-method settings intents.
- Produces: no new public API.

Add a primary "Whisper keyboard" setup card near the top of the settings screen and
move the accessibility/floating-button section under an "Advanced" header with a
banking warning. Reuse the existing `settingsRow`/`sectionHeader` helpers already in
`MainActivity` (see its Diagnostics section, ~line 118–138).

- [ ] **Step 1: Add strings**

```xml
<string name="setup_keyboard_title">Whisper keyboard (recommended)</string>
<string name="setup_keyboard_sub">Bank-safe dictation. Enable it, then pick it with the 🌐 key.</string>
<string name="advanced_header">Advanced</string>
<string name="power_mode_title">Floating button (power mode)</string>
<string name="power_mode_warning">Uses an accessibility service. Banking apps may refuse to open while it is enabled. Use the Whisper keyboard for bank-safe dictation.</string>
```

- [ ] **Step 2: Add the keyboard setup card (near the top of the settings list)**

Insert where the settings rows are built:

```kotlin
root.addView(sectionHeader(getString(R.string.setup_keyboard_title)))
root.addView(settingsRow(getString(R.string.setup_keyboard_sub), "Enable & select") {
    startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
})
root.addView(settingsRow("Switch keyboards now", "Show picker") {
    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
})
```

- [ ] **Step 3: Demote accessibility under an Advanced header with the warning**

Wrap the existing accessibility/floating-button setup row(s):

```kotlin
root.addView(sectionHeader(getString(R.string.advanced_header)))
root.addView(settingsRow(getString(R.string.power_mode_title), getString(R.string.power_mode_warning)) {
    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
})
```

Ensure the app no longer nudges the user toward accessibility as the primary path
(remove or downrank any prior "enable accessibility to start" prompt).

- [ ] **Step 4: Verify build**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/kafkasl/phonewhisper/MainActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat: keyboard-first setup UX; demote accessibility to opt-in power mode"
```

---

## Task 8: On-device verification + docs + APK

**Files:**
- Modify: `phone-whisper/README.md`
- (Optional) replace distributed `phone-whisper/whisper-Malay.apk`.

- [ ] **Step 1: Build the release/debug APK**

Run: `ANDROID_HOME=/home/ahmadafif5321/android-sdk ./gradlew --no-daemon testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL; all unit tests green.

- [ ] **Step 2: On-device protocol (user runs on S24 Ultra)**

1. Install the APK. Open Whisper Malay → tap "Enable & select" → enable **Whisper Malay (voice)** in system keyboard settings.
2. Open Notes, tap a text field, press 🌐, choose **Whisper Malay (voice)**. Tap 🎤, speak Malay, tap again → text is typed into the field.
3. Confirm backspace / space / enter keys work.
4. With the accessibility service **disabled**, open the banking app → it should now launch normally.
5. (Power mode) Enable the accessibility floating button, confirm it still dictates, and confirm the bank blocks again while it's on (expected).
6. Capture screenshots of steps 2 and 4.

- [ ] **Step 3: Update README**

Document the keyboard as the primary, bank-safe dictation method; describe the setup (enable + select), and note the floating button is an optional power mode that banking apps will block.

- [ ] **Step 4: Commit**

```bash
git add phone-whisper/README.md
git commit -m "docs: document bank-safe voice keyboard as primary dictation path"
```

---

## Self-Review

**Spec coverage:**
- Voice-only IME → Tasks 6 (+3 engine, 5 permission). ✅
- `DictationEngine` extraction → Tasks 1–4. ✅
- `MicPermissionActivity` → Task 5. ✅
- Accessibility demoted to opt-in + warning → Tasks 4 (wiring) + 7 (UX/warning). ✅
- Setup UX / keyboard-first → Task 7. ✅
- Manifest + IME config → Tasks 5, 6. ✅
- Reliability (commitText, testable core) → Tasks 1–3 (pure tests), 6 (commitText). ✅
- Testing strategy → pure tests in Tasks 1–2; build gates each task; on-device in Task 8. ✅
- Non-goal (no evasion) → encoded in Global Constraints and Task 7 warning. ✅

**Placeholder scan:** No TBD/TODO; every code step shows complete code. On-device steps (Task 8) are inherently manual and are labelled as such (IME cannot be driven headless).

**Type consistency:** `DictationEngine.State`, `DictationEngine.Listener` (onState/onResult/onInfo/onError), `startRecording(listener): Boolean`, `stopAndTranscribe(listener)`, `loadModel(info)`, `release()`, `hasLocalTranscriber()`, and `TranscriptionRouter.Route`/`decide(...)`, `PcmConverter.toFloatSamples(...)` are used consistently across Tasks 3, 4, and 6.

**Known limitation:** `DictationEngine` (AudioRecord + native transcription + threads) has no pure unit test; it is guarded by compile checks, behavior-parity with the prior service, and the Task 8 on-device protocol. The pure, regression-prone logic (PCM conversion, route decision) is unit-tested.
