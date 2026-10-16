# VonoTation

Preview: **0.1.14-preview**, version code **86**, package `app.voiceflow`.

[Download APK, source and checksums](https://github.com/JuelHossain/Vonotation/releases/tag/v0.1.14-preview) · [Android source](android/) · [Changes and validation](release-notes/v0.1.14-preview.txt)

## Quick thoughts

New Quick thought captures are skipped when their complete, successful transcription occupies **fewer than two normal phone-width lines**. The reference size is fixed, so folding the phone or changing font scale does not change the rule. Whitespace is collapsed only for measuring; retained transcripts keep their original text. Existing saved thoughts are never removed by this rule, and active-input dictation is unaffected.

Offline captures stay pending until text is available. Older pending recordings, explicit manual Retry/Save actions, interrupted or recovered captures, missing segments and uncertain transcription are preserved. A skipped new capture has its source audio removed only after its metadata and skip record are saved successfully. Retained thoughts keep the existing hidden **Source audio** playback.

Quick thoughts follows the existing **Daybook daily timeline**, with the same date grouping used by Dictation. Open a saved thought for its detailed summary and full transcript. Playback stays hidden until **Source audio** is tapped. Optional daily recaps use the connected **Notes model** to summarize that day's thoughts; enable them only if you want them. The existing colors, typography and card design are retained.

Enable **Settings → Quick thoughts** and connect **Floating microphone** under Permissions. Outside VonoTation, tap the faint dot when no input is active to record on the current screen. Hold it to open saved Quick thoughts. Drag it freely within the usable screen; the idle and active controls share the saved position. A hold or drag does not start recording. Use **Finish** or **Cancel** to end capture. Changing apps, focusing a field or tapping elsewhere does not end it; system interruptions may finalize a recording. Normal input dictation still writes into the active field.

Source audio is saved locally even without a connection or API key. AI transcription, note organization and optional recaps wait for the Notes provider and a connection. Completed pending recordings retry after connectivity returns; Android may defer background work. For retained thoughts, original transcripts remain available; failed or incomplete processing keeps the original recording. Retained audio is stored on this phone and excluded from text backups; transcription sends audio to your configured Notes provider when connected.

Turning Quick thoughts off hides both its card and idle dot while retaining saved thoughts. The microphone is closed while idle, and expanded idle controls shrink after 60 seconds. Reminders remain a future feature.

## Retained features

The compact latest-copy pill and scrollable clipboard popup remain included. Delete uses the editor's current cursor or selection and stops when it cannot safely confirm an edit. Quiet Call Notes recovery is retained; Android and wireless-debugging changes can still disconnect it.

Open **Settings → Update**, allow installation if Android asks, then confirm Android's installer. An older build may need Update tapped again after returning from permission settings. The updater verifies hash, size, package, version and installed signing identity, and defers while audio work is active. The public update feed advances only after its public APK is verified.

Backup controls remain available. Google Drive OAuth registration, Google account consent and live backup/restore remain unverified for this preview.

## Verification and source

Release preparation records actual build, test, APK identity, signing and source-exclusion results in `verification/v0.1.14-preview/VERIFICATION.txt`. The release workflow separately validates prepared artifacts before publication. Historical documents under `android/` describe older versions; `android/app/build.gradle` identifies this preview.

Phone/device testing: not performed.

Actual Samsung/Niagara behavior, reference-line measurement, short-capture filtering, timeline interactions, gestures, input deletion, microphone capture, live Notes-provider transcription/recaps, installation, notifications, wireless debugging, Google sign-in and Drive backup/restore remain unverified. Source review and JVM checks do not establish phone or provider behavior.

Private signing keys, API keys, recordings, caches and machine configuration are excluded. Use JDK 17, Android SDK 35 and Build Tools 35.0.0; run `bash gradlew :app:assembleDebug` inside `android`. Compatible preview signing requires the existing private key through `VOICEFLOW_KEYSTORE`; it is not included.
