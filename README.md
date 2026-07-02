# Whisper Malay

**Offline voice typing for Bahasa Melayu Malaysia and Manglish — now as an Android keyboard, with no Accessibility Service required.**

Whisper Malay is a maintained fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper), customized for Malaysian users who dictate in Bahasa Melayu, Manglish, and mixed Malay–English chat text — including particles like `lah`, `kan`, `je`, and `kot`. Transcription runs fully on-device with Whisper models via sherpa-onnx; an optional Gemini cloud mode is available with your own API key.

## 📥 Download

| Build | APK | Notes |
|---|---|---|
| **v0.4.0 — Voice Keyboard** (recommended) | [whisper-Malay-voicekb-test.apk](https://github.com/ahmadafif5321/Whisper-Malay/releases/download/v0.4.0-voicekb-test/whisper-Malay-voicekb-test.apk) | Bank-safe keyboard build — see [release notes](https://github.com/ahmadafif5321/Whisper-Malay/releases/tag/v0.4.0-voicekb-test) |
| Legacy — floating button | [whisper-Malay.apk](whisper-Malay.apk) | Original overlay version; requires Accessibility Service |

## 🏦 Why the keyboard version exists (the banking-app problem)

Earlier versions inserted dictated text through Android's **Accessibility Service** — the only way a floating overlay can type into other apps. The problem: **most Malaysian banking apps refuse to open while any accessibility service is enabled** (an anti-malware policy on their side). Users had to disable accessibility every time they wanted to check their bank, then re-enable it to dictate. In practice, many simply left it off.

**v0.4.0 solves this properly: dictation is embedded directly into an Android keyboard (IME).** Keyboards are a first-class Android text-input mechanism, so no accessibility service is needed at all:

- 🏦 Banking apps open normally — accessibility stays **OFF**
- ⌨️ Works in any app with a text field — switch to the Whisper keyboard with the 🌐 key, tap 🎤, speak, done
- 🔁 The floating-button mode still exists for users who prefer it, but it is no longer required

## ✨ Highlights

- **Bahasa Melayu Malaysia by default** — tuned model catalog with Malay-readiness shown in-app
- **100% offline transcription** with sherpa-onnx / Whisper models — audio never leaves the phone in local mode
- **Optional Gemini cloud mode** with your own API key, including a cleanup pass that preserves BM/Manglish particles (`lah`, `kan`, `je`, `kot`) instead of "correcting" them into formal text
- **Voice keyboard (IME)** shares the same `DictationEngine` as the floating button — one engine, two input surfaces
- Direct model downloads, including Hugging Face `/resolve/.../*.zip` archive links

## 🚀 Install (keyboard build)

1. **Uninstall any existing Whisper Malay first** — the test build is debug-signed, so Android blocks installs over a differently-signed APK.
2. Download [whisper-Malay-voicekb-test.apk](https://github.com/ahmadafif5321/Whisper-Malay/releases/download/v0.4.0-voicekb-test/whisper-Malay-voicekb-test.apk) → open it → allow **Install unknown apps** for your browser/file manager → Install. (Play Protect may warn because this isn't from the Play Store — that's expected for a GitHub-distributed APK.)
3. Open the app → grant **microphone** permission → **Enable & select** the *Whisper keyboard (recommended)*.
4. Download **Malay Whisper Small** (recommended local model, ~610 MB).
5. In any chat or notes field, switch keyboards with the 🌐 key, tap 🎤, speak Bahasa Melayu, tap again — the text types itself.
6. Optional sanity check: open your banking app and confirm it launches normally with accessibility **OFF**. That's the whole point. 🎉

## 🧠 Local models

Models are stored on-device. Malay-capable catalog:

| Model | Size | Notes |
|---|---:|---|
| Whisper Tiny Multilingual | 111 MB | Fast Malay-capable baseline |
| Whisper Base Multilingual | 198 MB | Balanced baseline |
| Whisper Small Multilingual | 232 MB | ONNX archive; experimental runtime path |
| **Malay Whisper Small** | 610 MB | **Recommended** local Malay model |
| Malay Whisper Medium | 1.8 GB | Best quality; slower, needs more RAM |

If `whisper_small_int8` fails to load on your device, use **Malay Whisper Small** — it uses the sherpa-onnx path and is the reliable default for Bahasa Melayu dictation.

## 🔬 Engineering notes

This fork is not just a reskin — it includes real debugging and hardening work, documented in [FIX_REPORT.md](FIX_REPORT.md):

- **Root-caused a hard crash on all local-model paths**: the native sherpa-onnx JNI library was absent from clean checkouts, and the resulting `UnsatisfiedLinkError` (an `Error`, not `Exception`) escaped a raw thread and killed the process silently. Fixed with pinned native libs (sherpa-onnx v1.12.28), proper `Throwable` boundaries, and thread hygiene.
- **ONNX opset fix** for the Whisper Small crash — the model refused to load because of a single wrong byte in its opset stamp. Full write-up: [My Whisper model refused to load — the fix was one byte](https://ahmadafif.com/blog/whisper-onnx-one-byte-bug/). Plus a **hot-mic fix** and a **mic-permission trampoline** so the keyboard can request the runtime permission correctly from IME context.
- **75 unit tests, 0 failures** — covering BM default-language checks, the Malay model catalog, Hugging Face archive URL handling, a built-in Malay WAV fixture, and WER regression tests for BM and Manglish phrases.

```bash
# Build and test
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

## 🔒 Privacy

Local mode keeps audio entirely on-device. Cloud mode sends audio directly from the phone to Gemini using an API key stored locally — there is no relay backend in this project. See [PRIVACY.md](PRIVACY.md).

## 🙏 Credits

Fork of [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper), maintained by [ahmadafif5321](https://ahmadafif.com) as a personal project for the Malaysian voice-typing use case.
