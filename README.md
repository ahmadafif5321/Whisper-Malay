# whisper-malay

Refine by **ahmadafif5321**. Original project by **kafkasl** 

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

## Setup

1. Install `whisper-Malay.apk`.
2. Open **whisper-malay v2**.
3. Grant microphone permission.
4. Enable the Accessibility Service.
5. Download **Malay Whisper Small** for the recommended local setup.
6. Use **Malay Whisper Medium** only when you want best quality and have enough storage/RAM.

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
