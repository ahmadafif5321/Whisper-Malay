# Design: Diagnose & fix the Whisper Small Multilingual crash + war-story blog

**Date:** 2026-07-01
**Repo:** `phone-whisper/` (Whisper Malay fork of kafkasl/phone-whisper)
**Device:** Samsung Galaxy S24 Ultra, OneUI 8.5, arm64-v8a
**Status:** Approved — Phase 0 done, Phase 1 protocol handed to user

## Problem

The app crashes when the user selects **Whisper Small Multilingual** for local
transcription. This is the *only* catalog model that runs through
`OnnxWhisperTranscriber` (ONNX Runtime + onnxruntime-extensions custom ops);
every other model uses sherpa-onnx and is unaffected. So the fault is isolated
to the ONNX-Whisper code path.

## Constraints & environment

- Build host has **no Android SDK / emulator / adb** — only JDK 21. The app is
  `arm64-v8a` only, so an x86 emulator could not load its native libs anyway.
  On-device verification (logcat + screenshots) is therefore done by the user.
- Fix must not regress the sherpa-onnx models (tiny/base multilingual, Parakeet,
  Moonshine, Malay small/medium).
- Match the repo's existing error philosophy: `catch(Throwable)` + visible toast,
  never a silent process death.

## Root-cause hypotheses (ranked, Phase 0)

Evidence: `OnnxWhisperTranscriber.create()` (line ~219) and
`WhisperAccessibilityService.transcribeLocal()` (line ~452) both already
`catch(Throwable)`. A clean Java `OrtException` would surface as a toast, not a
crash. An actual process crash therefore implies a **native abort that bypasses
the JVM** — which narrows the field:

- **H1 (lead) — extensions custom-op ABI mismatch.** `app/build.gradle.kts`
  bundles sherpa-onnx's `jniLibs/arm64-v8a/libonnxruntime.so` *and* depends on
  Maven `onnxruntime-android:1.17.1` + `onnxruntime-extensions-android:0.10.0`.
  `packaging { jniLibs { pickFirsts += "lib/arm64-v8a/libonnxruntime.so" } }`
  silently keeps one of the two colliding `libonnxruntime.so` files. The
  extensions custom-op lib (`OrtxPackage.getLibraryPath()` →
  `libortextensions.so`) is only exercised by the mel-initializer and
  BPE-detokenizer sessions — i.e. only the ONNX-Whisper path. If the resolved
  `libonnxruntime.so` is ABI-incompatible with `libortextensions.so`,
  `registerCustomOpLibrary(...)` or `initializer.run` / `detokenizer.run`
  aborts natively.
- **H2 — native OOM / ORT arena pressure.** Five ONNX sessions loaded with
  `NO_OPT` for the small model; a native `bad_alloc`/SIGABRT bypasses Java catch.
- **H3 — archive/format or language-token path.** `whisper_small_int8.zip`
  contents vs `REQUIRED_FILES`, or the `ms` → language token 50282 path.

The device logcat backtrace disambiguates: it names the faulting `.so`
(`libortextensions.so` / `libonnxruntime.so`) and the signal, or shows a clean
`OrtException` (which would instead point at H3 / a catchable path).

## Plan

- **Phase 0 — static diagnosis (done).** Ranked hypotheses above with file:line
  evidence.
- **Phase 1 — capture evidence (user).** `adb logcat` full capture + repro
  sequence; user returns the crash log + screenshots.
- **Phase 2 — confirm & fix.** Map logcat to a hypothesis; minimal fix (correct
  packaging / extensions↔ORT wiring, and/or a guarded fallback with a visible
  error). Add host-runnable JUnit tests for JVM-testable logic (language-token
  map, prompt tokens, `detokenize` markup regex, `prepare()`/`argmax`, model
  detection); state honestly what cannot be unit-tested on host. User rebuilds
  and re-runs the protocol to confirm.
- **Phase 3 — blog.** `docs/blog/2026-07-01-whisper-small-multilingual-crash.md`,
  human war-story voice, embedding the real logcat snippets, screenshots, and
  source diffs.

## Verification / "right tool"

The right tool for an Android runtime crash is `adb logcat` (Phase 1). Its output
plus the user's screenshots are the tested evidence the blog is built on.
Host-side JUnit covers only the pure logic.

## Out of scope

New features, UI redesign, changes to the sherpa-onnx models, or supporting
other ABIs.
