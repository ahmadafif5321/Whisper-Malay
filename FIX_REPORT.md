# Phone Whisper — Fix Report
**Date:** 2026-06-12 | **Plan:** ralplan-whisper-s24-001 v3 | **Device target:** Samsung S24 Ultra (OneUI 8.5, arm64-v8a)

---

## 1. Root Causes

### Bug A — Parakeet 0.6B crash (and all local-model paths)

**Keystone (P-A1): The native sherpa-onnx JNI library was completely absent from the repo.**

`app/.gitignore:7` git-ignores `app/src/main/jniLibs/` entirely. There is no sherpa-onnx AAR, no Maven/Gradle coordinate, no `flatDir`, no `sourceSets` wiring — a clean checkout produces an APK with zero native `.so` files. The consequence: every attempt to use local inference triggers `System.loadLibrary("sherpa-onnx-jni")` inside a static initializer, which throws `UnsatisfiedLinkError` before any application code runs.

The exact crash chain:

1. `OfflineRecognizer.kt:212` and `OfflineStream.kt:29` each call `System.loadLibrary("sherpa-onnx-jni")` in **static initializers** — these fire the moment the JVM first touches either class.
2. `UnsatisfiedLinkError` is a subclass of `Error`, **not** `Exception`. The original `LocalTranscriber.create()` at `LocalTranscriber.kt:47-54` caught only `catch (e: Exception)` — the error propagated straight through.
3. The uncaught `Throwable` escaped the raw Kotlin `thread { initLocalModel() }` started at `WhisperAccessibilityService.kt:81` (initial load) and `:113` (the `reloadModel()` path). An uncaught throwable on a raw thread **kills the process** — no toast, no logcat message from the app, just a crash.

**Secondary layer (P-A2 / R4): ABI/version mismatch risk.** Even with a `.so` present, if it does not match the vendored `com.k2fsa.sherpa.onnx.*` JNI binding signatures (`OfflineRecognizer.kt:191-208`, `OfflineStream.kt:24-25`), the crash changes from `UnsatisfiedLinkError` to `NoSuchMethodError` at the first JNI call — a different error, same outcome. See the native-lib provenance section for how this was addressed.

**OOM risk layer (P-A1 secondary): Native arena pressure from the 0.6B transducer model.** The Parakeet 0.6B transducer (encoder + decoder + joiner, ~465 MB on disk) allocates ONNX Runtime arenas in **native memory, entirely outside the Java heap**. With `numThreads = 2` (the original value at `LocalTranscriber.kt:109`) ONNX doubles intra-op arena allocation. A native OOM fires `SIGABRT`/`SIGKILL` — **this cannot be caught in Kotlin at all**, and no `try-catch(Throwable)` boundary can intercept it. It is prevented, not caught, by supplying the correct `.so` and reducing thread count (see Changes section).

---

### Bug B — Dead mic button with Parakeet 110M selected

**Diagnosis: evidence-gated. Two competing hypotheses; the fix ships instrumentation to resolve them on your device.**

**P-B2 (primary fit): OneUI 8.5 touch interception on `TYPE_ACCESSIBILITY_OVERLAY` windows.** The floating dot renders but touch events (`ACTION_DOWN`) never reach the `OnTouchListener`. This is the better fit because:

- The touch listener attaches in `showOverlay()` at `WhisperAccessibilityService.kt:79` — *before* model init fires at `:81`. A load failure cannot un-wire it.
- `onTap()` / recording is load-independent: `stopAndTranscribe` falls through to the API path when `local == null` (`WhisperAccessibilityService.kt:361-367`). Even a fully failed local init should still let the button turn red and record audio.
- A *persistent* visible-but-dead button that survives taps and model switches fits P-B2 (touch blocked at the OS/window level) more cleanly than P-B1 (broken service thread).

**P-B1 (secondary): Half-dead service from a failed init thread.** Plausible as a contributing factor but weaker — the listener/recording path is independent of the local transcriber reference.

**The decider is the `ACTION_DOWN` log line** added at `WhisperAccessibilityService.kt:188` (inside the `setOnTouchListener` `ACTION_DOWN` branch). See the on-device test protocol, step 3.

- `ACTION_DOWN` fires → P-B1 branch (thread hygiene in this build is the fix).
- `ACTION_DOWN` absent → P-B2 branch. Contingency: audit window flags at `WhisperAccessibilityService.kt:142-146` — verify `FLAG_NOT_FOCUSABLE` is set, confirm `FLAG_NOT_TOUCHABLE` is absent from the button overlay (correctly absent; it is only on the feedback overlay at `:205`). Adjust flags so OneUI delivers touches. No window-type migration to `TYPE_APPLICATION_OVERLAY`/`SYSTEM_ALERT_WINDOW` without your explicit approval (new runtime permission required).

---

## 2. Native Library Provenance

**Release pinned: sherpa-onnx v1.12.28** — no deviation from the fingerprinted target.

| Field | Value |
|---|---|
| Tag | `v1.12.28` |
| Published | 2026-02-28 |
| Archive | `sherpa-onnx-v1.12.28-android.tar.bz2` |
| Download URL | https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.28/sherpa-onnx-v1.12.28-android.tar.bz2 |
| Archive size | 36,761,241 bytes (~35 MB) |

**Why v1.12.28 specifically:** the vendored Kotlin bindings were fingerprinted by their newest catalog entries — Moonshine v2 models dated 2026-02-27 (types 51-60), `OfflineOmnilingualAsrCtcModelConfig` (~Nov 2025), `OfflineMedAsrCtcModelConfig` (~Dec 2025), `OfflineFunAsrNanoModelConfig` (~Dec 2025), 3-field `QnnConfig`, and `OfflineMoonshineModelConfig.mergedDecoder`. v1.12.28, published 2026-02-28, is the first release after the newest catalog date — the closest possible match to the binding fingerprint.

**Installed files** (all to `app/src/main/jniLibs/arm64-v8a/`):

| File | Size | SHA-256 |
|---|---|---|
| `libsherpa-onnx-jni.so` | 4.7 MB | `1940da27e65a82708df6dce6514d4619da7a5962f2b1f40de7caccba29ae6a00` |
| `libonnxruntime.so` | 16 MB | `c7c0e6b0f9c364a4df4bfc9a3a3576b691899e96f4d9beb60f3b3a7ce4bf572c` |
| `libsherpa-onnx-c-api.so` | 4.4 MB | `c2349269c9c2bf9a5eb990cd6f858bf88b6f183811e3dd251e113c8a179c3359` |
| `libsherpa-onnx-cxx-api.so` | 402 KB | `87fc33324b68a62f83b88ec3cd3da042e96687dd522a58e775a50aa395350708` |

**Companion lib determination:** `libonnxruntime.so` is **not** statically linked into `libsherpa-onnx-jni.so` — it appears as a `NEEDED` entry in the JNI lib's ELF dynamic section and ships as a **separate file** in the v1.12.28 archive. It was installed alongside. Missing it would reproduce the exact `UnsatisfiedLinkError` symptom (the JNI lib would fail to load its own dependency at link time). All four `.so` files are required; all four are present.

**ELF verification:** All four files confirmed `ELF 64-bit LSB shared object, ARM aarch64`.

**16 KB page alignment (R9):** All LOAD segments in `libsherpa-onnx-jni.so` and `libonnxruntime.so` carry `Align = 0x4000` (16,384 bytes). This build is **16KB page-alignment compliant** and will load correctly on Android 15 / OneUI 8.5 devices enforcing 16KB pages.

**Deviation:** None. v1.12.28 is the exact fingerprinted target; no nearest-tag fallback was needed.

---

## 3. Change Log (mapped to plan principles P1–P4)

**3 files changed: +57 / -12 lines.**

### `app/src/main/jniLibs/arm64-v8a/` — 4 `.so` files (not in diff; git-ignored) · P1, D1, D2

The keystone. Sherpa-onnx v1.12.28 native libraries for arm64-v8a wired into the build via the existing `jniLibs` source set (Android Gradle picks this up automatically; no Gradle changes needed). Nothing downstream can work without these files.

### `AndroidManifest.xml:7` · P1 (support), P3

Added `android:largeHeap="true"` to the `<application>` element.

**Honest scope (D3):** this raises only the ART managed-heap ceiling — it helps the Java-side `ByteArrayOutputStream` PCM buffers (`WhisperAccessibilityService.kt:329, 355`) and any other Java allocations. It does **not** touch ONNX Runtime's native arenas, which live entirely off the Java heap. `largeHeap` is not the 0.6B crash fix; it is zero-risk Java-buffer help. The native fix is the correct `.so` + `numThreads = 1` below.

### `LocalTranscriber.kt` · P1, P2, P3, P4

**`LocalTranscriber.kt:47-54` — `catch (Exception)` → `catch (Throwable)`**

The original catch missed `UnsatisfiedLinkError` (an `Error`, not an `Exception`). Widened to `catch (t: Throwable)` so a class-load failure from a missing or mismatched `.so` returns `null` from `create()` instead of propagating. The full throwable is now logged with stack trace (`Log.e(TAG, "Failed to load model: ${t.message}", t)`).

**AC3 scope note:** `catch(Throwable)` intercepts catchable Java `Error` subclasses — `UnsatisfiedLinkError`, `NoSuchMethodError`, any `Error` thrown during JVM class loading. It **cannot** intercept a native `SIGABRT`/`SIGKILL` OOM from inside ONNX Runtime. That signal kills the process at the OS level before any JVM exception machinery runs. AC3 is met for the catchable `Error` class; native OOM is **prevented** (not caught) by the correct `.so` + `numThreads = 1`, as stated in plan Risk R6.

**`LocalTranscriber.kt:44-56` — diagnostic logging added**

Before constructing the `OfflineRecognizer`, logs the resolved config: `numThreads`, `modelType`, and the key path fields for each model variant (moonshine, whisper, transducer, nemo). This makes it possible to confirm from logcat exactly which config branch was selected and what paths were resolved.

**`LocalTranscriber.kt:116` — `numThreads = 2` → `numThreads = 1` on the transducer branch**

Scoped strictly to the NeMo-TDT/transducer branch (`LocalTranscriber.kt:96-113`) used by Parakeet 0.6B. Roughly halves ONNX intra-op native arena allocation for this model. The Moonshine, Whisper, and NeMo-CTC branches are unchanged at their existing thread counts (AC4 preserved).

**Label:** post-link hardening — this line has no effect unless the `.so` loads successfully. The crash fix is the `.so`; this reduces memory pressure once it runs.

### `WhisperAccessibilityService.kt` · P2, P3, P4

**`:81` and `:148` — raw `thread { }` bodies wrapped in `try { } catch (t: Throwable) { }`**

The original `thread { initLocalModel() }` at `:81` (initial connect) and `thread { initLocalModel() }` inside `reloadModel()` at `:148` had no exception handling. An uncaught `Throwable` on a raw Kotlin thread kills the process. Both are now wrapped: the throwable is logged and a toast ("Model load failed: ...") is shown on the main thread. This is the boundary that actually stops the process death — widening `create()`'s catch alone is insufficient because `System.loadLibrary` fires in a static initializer before `create()` is even called.

**`:100-148` — `initLocalModel()` restructured for loud load-failure path**

The original silent path: when a selected model failed to load, `localTranscriber` stayed `null` and the code logged only `"No local model found, will use API"` — indistinguishable from "no model downloaded." Now: if a model name was resolved (either from prefs or auto-detect) but `localTranscriber` is still `null` after `create()`, a toast "Model load failed: $resolvedModel" is shown and an error is logged. The silent "will use API" log is preserved only for the genuine no-model-selected case. Diagnostic logging added: resolved model name, resolved directory path, and a line at service connect ("overlay shown, starting model init").

**`:188` — `ACTION_DOWN` log added**

`Log.i(TAG, "overlay ACTION_DOWN, state=$state")` at the top of the `ACTION_DOWN` branch in the `setOnTouchListener`. This is the P-B2 decider: its presence or absence in logcat tells you which button hypothesis is correct (see step 3 of the on-device protocol).

**`:336` — `onTap()` entry log added**

`Log.i(TAG, "onTap, state=$state")` at the top of `onTap()`. Confirms the touch listener fired and the state machine was entered.

**`:418` — `catch (Exception)` → `catch (Throwable)` in `transcribeLocal`**

Widens the transcription error catch so a runtime crash inside the JNI transcription call (e.g., `NoSuchMethodError` from an ABI mismatch) surfaces as a toast and resets state to IDLE rather than escaping the coroutine.

---

## 4. Handoff Clause (M2) — MANDATORY

**This APK was built and unit-tested on a host machine with no Android device or emulator attached. A JNI class-load smoke test (loading `OfflineRecognizer`/`OfflineStream` in a running ART process) was not possible.**

Consequences:

- **AC2 is UNVERIFIED.** We know the `.so` files are present in the APK under `lib/arm64-v8a/` and that the ELF headers are correct. We do not know that `System.loadLibrary("sherpa-onnx-jni")` succeeds in ART on your S24 Ultra, or that the JNI entry points match the `external fun` declarations.
- **R4 (ABI/version mismatch) is LIVE.** If v1.12.28's JNI signatures diverge from the vendored bindings at any `external fun` site, you will see `NoSuchMethodError` (not `UnsatisfiedLinkError`) on the first JNI call. This is now caught by `catch(Throwable)` and will appear as a toast rather than a silent crash — but the model still will not load.

**Your first on-device logcat run is the actual AC2 verification.** Step 6 of the on-device protocol below is the test. If you see logcat lines like `Loaded model: parakeet-...` and the button behaves normally under 0.6B, AC2 is verified. If you see `NoSuchMethodError` or `UnsatisfiedLinkError` in the toast or logcat, report the exact error message — it will identify whether R4 has triggered and what adjustment is needed.

---

## 5. Architecture Summary

### Recording flow

Overlay tap (`MotionEvent.ACTION_UP` after a non-drag gesture, detected in `setOnTouchListener`) → `onTap()` → `startRecording()` → `AudioRecord` opened at 16 kHz, 16-bit mono PCM → audio chunks written into a `ByteArrayOutputStream` on a background thread → second tap → `stopAndTranscribe()` → PCM bytes passed to the transcription path.

### Model pipeline

`ModelDownloader` fetches and extracts a model archive from the catalog (URL resolved by model type/index) into `filesDir/models/<modelName>/`. `LocalTranscriber.availableModels()` scans that directory. `LocalTranscriber.create()` calls `detectModelConfig()`, which inspects the directory contents (encoder/decoder/joiner files, tokens, model type marker) and constructs the appropriate `OfflineRecognizerConfig` — one of: NeMo-TDT transducer (Parakeet 0.6B), NeMo-CTC (Parakeet 110M), Whisper, or Moonshine. That config is passed to `OfflineRecognizer(assetManager = null, config = config)`, which crosses the JNI boundary into `libsherpa-onnx-jni.so` and loads the ONNX model files. `transcribe()` constructs an `OfflineStream`, feeds the PCM samples, calls `decode()`, and returns the result string.

### Text injection

`injectText(text)` first copies the text to the clipboard, then calls `findInjectionCandidates()` to find focused or editable `AccessibilityNodeInfo` nodes in the active window. It tries `ACTION_SET_TEXT` first (direct, no clipboard dependency); if that fails or the node doesn't support it, it falls back to `ACTION_PASTE` (clipboard-based). This covers the widest range of input fields across Android versions and OEM keyboards.

---

## 6. On-Device Test Protocol (10 steps)

Run these in order on your Samsung S24 Ultra. Keep the logcat filter running throughout.

1. Uninstall any prior build of Phone Whisper. Sideload the new `app-debug.apk`.

2. Open Phone Whisper → grant **Microphone** permission → go to **Settings → Accessibility → Installed Apps** and enable the **Phone Whisper** Accessibility Service.

3. **(Evidence / ACTION_DOWN checkpoint)** On a PC connected via USB with ADB:
   ```
   adb logcat -c
   adb logcat -s PhoneWhisper:* LocalTranscriber:* AndroidRuntime:E libc:F DEBUG:*
   ```
   Keep this running for all steps below. **Tap the floating dot once and look for a line containing `ACTION_DOWN`.** This single line is the P-B1/P-B2 decider:
   - Line appears → the touch listener receives events (P-B1 branch; thread guards in this build are the fix).
   - Line absent → OS/OneUI is blocking touch delivery to the overlay (P-B2 branch; flag audit needed).
   Report which happened.

4. **Mic button — Parakeet 110M:** Select **Parakeet 110M** in settings, download if needed, wait for the "ready" indication. Open an app with a text field (Notes, Messages, etc.). Tap the floating dot once → it should turn **red** (RECORDING state). Speak a sentence. Tap again → spinner, then transcribed text should appear in the field. → AC6.

5. **Responsiveness:** Repeat step 4 three times consecutively without errors or requiring a re-toggle of the Accessibility Service. → AC7.

6. **Parakeet 0.6B — the main crash test:** Switch to **Parakeet 0.6B** in settings. Download the model if needed (~465 MB). Wait for "ready" — the app must **not** crash during model load. Tap the dot → speak → tap again → text appears, no crash. → AC2. *(This step is the actual AC2 verification — see the M2 clause above.)*

7. **Model-switch responsiveness:** With 110M, tap-record once. Switch to 0.6B, tap-record once. Button must remain responsive after the switch. → AC7.

8. **Loud error path:** If a selected model fails to load at any point, confirm a **visible toast with a reason** appears on screen (not a silent disappearance or a frozen button). → AC3.

9. **Regression — other models:** Quick tap-record with **Whisper Base** and **Moonshine Tiny**. Both should work normally. → AC4.

10. Send the captured logcat back, especially the section covering the 0.6B model load (step 6) and the very first 110M tap (step 4), including the `ACTION_DOWN` line from step 3.

> **Known limitation:** Android does **not** auto-restart an AccessibilityService after a crash. If any step above crashes the app or service, the floating button will appear frozen or invisible until you **manually re-toggle the Phone Whisper Accessibility Service off and on in Settings → Accessibility**. Note which step required a re-toggle — it confirms a crash occurred at that point and helps narrow the remaining issue.

---

## 7. Acceptance Criteria Status

| AC | Description | Status |
|---|---|---|
| AC1 | Root cause of 0.6B crash identified with code-path evidence | ✅ Evidenced (absent `.so` → static-init `UnsatisfiedLinkError` → uncaught on raw thread → process death; OOM secondary layer; all with file:line) |
| AC2 | 0.6B loads and transcribes without crashing on S24 Ultra | **UNVERIFIED** — no device/emulator on build host; R4 (ABI mismatch) is LIVE; your step-6 logcat is the actual gate |
| AC3 | Load failures surface visibly instead of crashing (catchable `Error` class only) | ✅ Code-level — `catch(Throwable)` at both init-thread boundaries (`:81`, `:148`) and `create()` (`LocalTranscriber.kt:51`); load-failure toast in `initLocalModel()`; scope: catchable Java `Error` only — native `SIGABRT`/`SIGKILL` OOM is prevented by `.so` + `numThreads=1`, not caught |
| AC4 | Crash fix does not regress 110M, Whisper Base, Moonshine Tiny | ✅ Build-level (only transducer branch gets `numThreads=1`; other branches unchanged); on-device confirmation via step 9 |
| AC5 | Root cause of dead mic button identified | Evidence-gated — `ACTION_DOWN` log shipped; P-B2 (OneUI touch interception) is the primary hypothesis; your step-3 logcat confirms the branch |
| AC6 | 110M button enters RECORDING, yields transcribed text | Pending on-device (step 4) |
| AC7 | Button stays responsive across repeated use and model switches | Pending on-device (steps 5, 7) |
| AC8 | `./gradlew assembleDebug` succeeds; APK handed to user | ✅ BUILD SUCCESSFUL (2m 13s); APK at `app/build/outputs/apk/debug/app-debug.apk` (41.7 MB); APK contains `lib/arm64-v8a/{libsherpa-onnx-jni.so, libonnxruntime.so, libsherpa-onnx-c-api.so, libsherpa-onnx-cxx-api.so}`; 19/19 unit tests green (PostProcessorTest 4, ModelDownloaderTest 3, WavWriterTest 8, TranscriberClientTest 4); no compile errors or new warnings |
| AC9 | Fix report includes architecture summary | ✅ This document |

---

## 8. Follow-Up Recommendations

These are outside the scope of this bugfix but worth noting:

1. **Commit the `.so` or declare a proper Gradle dependency.** The git-ignored `jniLibs/` is a footgun — any fresh clone silently produces a broken APK. Either commit the four checked `.so` files directly (they are small enough) or declare sherpa-onnx as a Maven/Gradle dependency so the build fetches and verifies them automatically.
2. **If native OOM recurs on 0.6B after a successful `.so` load:** try further reducing `numThreads` to 0 (ONNX auto-selects based on available cores), or source a newer `.so` from the same v1.12.x family. The managed heap (`largeHeap`) will not help here.
3. **If ACTION_DOWN is absent (P-B2 confirmed):** the flag audit at `WhisperAccessibilityService.kt:142-146` is the first fix to try. If adjusting flags within `TYPE_ACCESSIBILITY_OVERLAY` does not restore touch delivery on OneUI 8.5, migration to `TYPE_APPLICATION_OVERLAY` (requires `SYSTEM_ALERT_WINDOW` permission, which must be granted explicitly by the user) becomes the next option — but only with your approval.
