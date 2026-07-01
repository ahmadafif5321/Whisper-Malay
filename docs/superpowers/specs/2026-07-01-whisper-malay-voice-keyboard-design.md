# Design: Whisper Malay voice keyboard (bank-safe dictation) + optional power mode

**Date:** 2026-07-01
**Repo:** `phone-whisper/` (Whisper Malay fork of kafkasl/phone-whisper)
**Device target:** Samsung Galaxy S24 Ultra, OneUI 8.5, arm64-v8a
**Status:** Approved (design) — pending spec review → implementation plan

## Problem

Whisper Malay's dictation works through an **AccessibilityService** (with
`canRetrieveWindowContent="true"`) plus a persistent **screen overlay**
(`TYPE_ACCESSIBILITY_OVERLAY` floating button). Malaysian banking apps, following
Bank Negara anti-scam directives, enumerate enabled accessibility services and
**refuse to launch** while one that can read the screen is active. The user's bank
shows an explicit "accessibility service" warning and blocks until the service is
disabled.

Because the bank checks at **its own** launch, reacting after the fact (hiding the
overlay, pausing on foreground change) does **not** help — the enabled service is
the trigger. The only real fix changes the input mechanism.

Secondary goal: increase local-model reliability and general robustness.

## Goal

1. Provide a dictation path that does **not** trip banking anti-fraud: a voice-only
   **input method (IME)** that types transcribed text via `InputConnection` — no
   accessibility service, no overlay.
2. Keep the existing accessibility floating button as an **optional, off-by-default
   "power mode"** with an explicit banking warning (Hybrid).
3. Improve reliability: more robust text insertion and a testable, extracted
   dictation core.

## Non-goals

- Full QWERTY keyboard, autocorrect, swipe/glide typing, multi-layout support.
- New transcription models or changes to the model catalog.
- Bypassing, spoofing, or hiding from any bank's fraud detection. The design makes
  the app *cooperate* (no accessibility/overlay by default), never evade.

## Architecture

Front ends change; the transcription pipeline is reused unchanged.

### New: `DictationEngine` (shared, UI-free core)
Extract from `WhisperAccessibilityService` (currently ~700 lines) the:
- `AudioRecord` capture (16 kHz mono PCM into a buffer),
- state machine `IDLE → RECORDING → TRANSCRIBING → IDLE`,
- calls to `LocalTranscriber` / `TranscriberClient` (Gemini) and `PostProcessor`,
- error handling and result delivery.

Interface (sketch):
```
class DictationEngine(context, prefs) {
    val state: StateFlow<DictationState>
    fun startRecording()
    fun stopAndTranscribe(onResult: (Result<String>) -> Unit)
    fun cancel()
    fun release()
}
```
No UI, no injection. The caller decides what to do with the text (IME commits it;
the accessibility service injects it). This shrinks the service, removes
duplication, and makes the core unit-testable.

### New: `WhisperImeService` (`InputMethodService`) — the voice-only keyboard
- Input view: large **mic** button (tap-start / tap-stop), **backspace**, **space**,
  **enter/newline**, **switch-keyboard (globe)** key, and a status line showing
  Recording… / Transcribing… and the active model + language.
- Flow: mic tap → `DictationEngine.startRecording()`; tap again →
  `stopAndTranscribe { text -> currentInputConnection.commitText(text, 1) }`.
- Editing keys: backspace via `deleteSurroundingText(1, 0)`; enter via
  `commitText("\n")` or an editor-action when the field requests one; space via
  `commitText(" ")`.
- Reads the same shared prefs (model, language, API key) the app already stores.
- No-permission state: shows "Grant microphone permission" → launches
  `MicPermissionActivity`.

### New: `MicPermissionActivity` (transparent trampoline)
An IME cannot request a runtime permission directly. A tiny, transparent, no-history
activity requests `RECORD_AUDIO`, then returns. The keyboard shows a prompt that
launches it when the permission is missing. (Standard Gboard-style pattern.)

### Changed: accessibility service → optional, off by default
- No change to Android's default (services are off until enabled), but the app must
  **stop treating accessibility as the primary path**. The floating-button setup is
  demoted to an "Advanced / power mode" card with a clear warning:
  *"This uses an accessibility service. Banking apps may refuse to open while it is
  on. For bank-safe dictation, use the Whisper keyboard."*
- Refactor `WhisperAccessibilityService` to consume `DictationEngine` (no functional
  change to the floating-button behavior itself).

### Changed: `MainActivity` setup UX
- New primary card: **"Set up the Whisper keyboard"** — deep-links to system input
  settings to (1) enable the IME and (2) select it, with a test text field and a
  readiness indicator.
- Model download / language / Gemini API key sections: unchanged, shared by both
  front ends.
- Accessibility/floating-button section moved under "Advanced (may block banking
  apps)".

### Manifest additions
- `<service WhisperImeService>` with `BIND_INPUT_METHOD` permission and
  `android.view.InputMethod` intent-filter + `method` meta-data (input-method config
  XML describing the subtype/locale `ms`).
- `<activity MicPermissionActivity>` transparent, `noHistory`, not exported.
- `RECORD_AUDIO` already present; keep.

## Data flow (bank-safe path)

```
Whisper keyboard mic tap
  → DictationEngine.startRecording()  (AudioRecord)
  → tap again → stopAndTranscribe()
      → LocalTranscriber (sherpa/ONNX)  OR  TranscriberClient (Gemini)
      → PostProcessor (Malay/Manglish cleanup)
  → currentInputConnection.commitText(text, 1)
```
No overlay, no accessibility, no clipboard.

## Bank-safety guarantees

- Fresh install: no accessibility service enabled, no overlay drawn → banks unaffected.
- IME text entry is indistinguishable from any keyboard → not flagged.
- Power mode is opt-in and clearly warned; the user owns the trade-off.

## Reliability improvements

- `commitText` is markedly more robust than the current
  `ACTION_SET_TEXT → ACTION_PASTE → clipboard` fallback chain across OEM/keyboard
  quirks.
- Extracting `DictationEngine` enables JVM unit tests of the state machine and error
  paths.
- Carries over the ONNX opset auto-repair and the loud load-failure toasts already
  added.

## Testing strategy

- **JVM unit tests**: `DictationEngine` state transitions and error handling;
  text-commit formatting against a mocked `InputConnection`/editing helper.
- Keep the existing suite green.
- **On-device (user, S24)**: enable + select the Whisper keyboard; dictate into Notes
  and a chat app; confirm text appears. Then, with the accessibility service disabled,
  confirm the banking app now launches normally. Re-enable power mode and confirm the
  floating button still works (and that the bank blocks again — proving the mechanism).
- IME behavior can't be fully driven headless; document manual steps.

## Phases

- **P1** — Extract `DictationEngine` from `WhisperAccessibilityService`; no behavior
  change; add engine unit tests. Build + suite green.
- **P2** — `WhisperImeService` (voice-only) + `MicPermissionActivity` + manifest +
  input-method config XML + keyboard setup UX in `MainActivity`.
- **P3** — Demote accessibility to opt-in/off-by-default with the banking warning;
  wire it to `DictationEngine`.
- **P4** — On-device verification, docs (README + setup), rebuilt APK.

## Risks / unknowns

- IME microphone-permission UX via the trampoline activity — standard but needs care
  on OneUI.
- Secure bank PIN pads intentionally ignore third-party IMEs — expected and correct.
- Friction of switching keyboards to dictate — mitigated by setup guidance and the
  globe key on the panel.
- Refactor risk when extracting `DictationEngine` — mitigated by doing P1 as a pure,
  test-covered move with no behavior change.
