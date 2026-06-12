# Privacy Policy for whisper-malay v2

Developed by **ahmadafif5321**.

whisper-malay v2 records speech, transcribes it, and inserts the resulting text into focused Android text fields after you explicitly tap the floating overlay button.

## Local Mode

In local mode, audio is processed on-device using local ASR models. Audio does not leave the phone.

## Cloud Mode

In cloud mode, recorded audio is sent directly from the device to Google Gemini for transcription using your own API key.

If cleanup is enabled, transcript text is sent directly from the device to Google Gemini to improve punctuation, capitalization, and obvious speech-to-text errors. The Malay cleanup prompt is designed to preserve Bahasa Melayu, Manglish, and common particles.

## API Keys

Your Gemini API key is stored locally in app storage and used only for direct requests from your device to Google Gemini.

This project does not operate a relay server.

## Accessibility Service

The Accessibility Service is used only to find the focused text field and insert dictated text after you interact with the overlay. It is not intended for browsing monitoring, analytics, keylogging, or background automation.

## Data Collection

This project does not collect accounts, analytics, uploaded recordings, or transcripts. Third-party services you choose to use, such as Google Gemini, process data according to their own terms and privacy policies.
