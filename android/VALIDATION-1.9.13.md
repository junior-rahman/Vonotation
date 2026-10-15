# VoiceFlow 1.9.13 preview validation

Continues the signed 1.9.12 source, resumed from original 1.9.10. The exact original source remains in baseline/. Discarded 1.9.11 work is not incorporated.

Version 1.9.13, code 23; package app.voiceflow; minimum API 26, target API 35.

APK SHA-256: 7da9326922025e5e9b5af402b8e6454a3896a5ff13edb2a7a83bd47c1f2ea64d
Signer certificate SHA-256: 56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae

## Changed behavior

Manual language choices are removed. Cloud recognition always omits language hints, including old restored/saved settings, and uses /audio/transcriptions. Whisper requests ask for verbose_json and temperature 0, without personal spelling prompts. The dictionary still applies after recognition. Segment scores can reject low-confidence, likely non-speech or repetitive output, asking the user to retry rather than inserting it. These scores are not language-confidence scores and are not calibrated on the user's speech. Confidently wrong language selection remains possible; recognition accuracy is not claimed fixed.

AI cleanup only formats punctuation, capitalization and paragraphs. A local guard requires unchanged word order and words, ignoring case/punctuation. Responses, refusals, rephrasing, removed words and masked profanity are rejected. Dictation's existing failure path delivers the original/basic-cleaned words with a separate warning. This is enforced after the response, rather than relying on instructions alone. A refusal phrase spoken by the user is accepted when unchanged.

Optional English-letter conversion remains. Each native-script word is checked against an Android ICU pronunciation reference; numbers and Latin words must remain. This comparison tolerates some natural spelling variation. It is conservative and approximate, can reject legitimate conversions, and does not prove semantic fidelity. On Android below 10, checked conversion falls back to the original transcript with a warning. Offline and sensitive-field bypasses remain.

Android recognition requests supported high-precision automatic language switching and unmasked offensive words. Recognizers may ignore these flags; older devices use the phone service's language support. Daily summaries remain separate from dictation.

## Build and checks

Fresh SDK35 CLI build, Java compilation, D8, packaging, alignment and original-key v2 signing passed. All 34 public dependency artifacts were checksum-verified. Standalone official Android lint passed with 0 errors and 27 warnings; Gradle build/lint were not executed. Production Java hashes match the final build receipt. Evidence is under evidence/1.9.13-build/.

12 pure-Java language-policy checks and 24 transcript-guard regressions passed. Cases include the reported greeting-to-answer mistake; question answering; refusal/censorship on profanity; unchanged profanity; a dictated refusal; word/numeric changes; Bengali native script; Chinese replacing English; checked Banglish; mixed Latin/native text; empty output and extra words.

Native mocked-HTTPS fidelity suite passed on the exact signed APK. The emulator installed 1.9.12, then upgraded to 1.9.13 without uninstalling; installed package metadata reports code 23. Checks exercised automatic request parameters despite saved Bengali/Nepali selections, production ICU pronunciation checking and refusal/answer rejection, profanity-preserving formatting, and the actual production Dictation completion/fallback callback for four changed responses. The callback delivered the original/basic-cleaned text and a separate warning. Backup output preference, private whitespace, recap and offline behavior also passed. This does not test real microphone recognition or insertion into a native input field. There are no app/fixture crash/ANR matches in the one captured test log window; this is a limited scan, not a stability guarantee. See evidence/1.9.13-native/.

## Limits

There are no live provider calls or real microphone speech samples in these tests. Automatic language accuracy, mixed-language recognition, confidence thresholds on real accents/noise, broader Banglish/Hindi/Nepali quality, phone UI and Galaxy Z Fold behavior remain unverified. The manual-picker changes are covered by source/build checks; the complete updated Daybook physical-tap UI suite has not run for this version. Existing native UI acceptance limitations are recorded in VALIDATION-1.9.12.md. This is a private signed preview.

Provider reference: https://console.groq.com/docs/speech-to-text (transcription endpoint, supported Whisper models, optional language, temperature and segment metadata). Android references: https://developer.android.com/reference/android/speech/RecognizerIntent and https://developer.android.com/reference/android/icu/text/Transliterator.
