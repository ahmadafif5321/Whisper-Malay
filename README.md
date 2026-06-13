# whisper-malay

Refine by **ahmadafif5321**. Original project by **kafkasl** 

This project is based on the original Phone Whisper project by kafkasl:
https://github.com/kafkasl/phone-whisper


[Download APK: whisper-Malay.apk](whisper-Malay.apk)

whisper-malay v2 is an Android push-to-talk dictation app focused on Bahasa Melayu Malaysia and Manglish. It places a small floating button over your apps: tap once to record, tap again to transcribe, then the text is inserted into the focused field when Android exposes a standard text input.

## Highlights

- Bahasa Melayu Malaysia is the default language.
- Local transcription uses sherpa-onnx / Whisper-compatible models.
- Gemini cloud transcription is available with your own API key.
- Optional Gemini cleanup preserves Bahasa Melayu, Manglish, and particles like `lah`, `kan`, `je`, and `kot`.
- Model readiness is visible in-app, including whether the selected model supports `ms`.
- Direct archive downloads are supported, including Hugging Face `/resolve/.../*.zip` links.

## APK

Current installable APK:

```text
whisper-Malay.apk
```

The same APK is also kept under:

```text
release/whisper-Malay.apk
```

## Android Access Requirements

Because this APK is installed directly from GitHub and not from Google Play, Android may ask for extra confirmation before installation.

You may need to allow:

- **Install unknown apps**: Android Settings → Apps → your browser/file manager → Install unknown apps → Allow from this source.
- **Google Play Protect warning**: Play Protect may warn because the APK is not from Play Store. Review the warning and choose install/allow only if you trust this repository.
- **Microphone permission**: required to record your speech.
- **Accessibility Service**: required so the floating dictation button can insert text into the currently focused text field across apps.
- **Internet access**: required for model downloads, Hugging Face direct URLs, and Gemini cloud transcription/cleanup.
- **Storage space**: local models are large. Malay Whisper Small is about 610 MB; Malay Whisper Medium is about 1.8 GB.
- **Gemini API key**: required only for cloud transcription or cleanup. Local transcription does not need an API key.

Important restrictions:

- Some apps block accessibility text insertion. In that case, whisper-malay copies the transcript to clipboard as fallback.
- Terminal/custom text surfaces may not behave like standard Android text fields.
- Accessibility must usually be re-enabled after reinstalling or changing APK signatures.
- Large local models can be slow or fail on devices with limited RAM.

## Setup

1. Download `whisper-Malay.apk` from this repo.
2. If Android blocks installation, enable **Install unknown apps** for the app you used to open the APK.
3. Accept any Play Protect/unknown-source prompt only if you trust this repository.
4. Open **whisper-malay v2**.
5. Grant **microphone** permission.
6. Enable the **whisper-malay Accessibility Service**.
7. Download **Malay Whisper Small** for the recommended local setup.
8. Confirm **Malay readiness** is shown as supported.
9. Open any app with a text field, tap the floating button, speak, then tap again.

## Local Models

Models are stored under:

```text
/data/data/com.kafkasl.phonewhisper/files/models/
```

Malay-capable catalog models:

| Model | Size | Notes |
|---|---:|---|
| Whisper Tiny Multilingual | 111 MB | Fast Malay-capable baseline |
| Whisper Base Multilingual | 198 MB | Balanced baseline |
| Whisper Small Multilingual | 232 MB | Hugging Face ONNX archive |
| Malay Whisper Small | 610 MB | Recommended local Malay model |
| Malay Whisper Medium | 1842 MB | Best quality, slower and heavier |

## Development

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
```

Regression coverage includes:

- Bahasa Melayu default language checks.
- Malay Whisper model catalog checks.
- Hugging Face archive URL handling.
- Built-in Malay WAV fixture validation.
- WER tests for Bahasa Melayu and Manglish sample phrases.

## Privacy

Local mode keeps audio on-device. Cloud mode sends audio directly from the phone to Gemini using the API key stored locally on the device. There is no relay backend in this project.

See [PRIVACY.md](PRIVACY.md).

## License

Personal project by **ahmadafif5321**.
