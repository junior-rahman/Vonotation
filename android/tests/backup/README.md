# Isolated backup checks

Build the app with `./build-apk.sh`, then build the fixture with Java 17 and Android SDK 35:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk ./tests/build-backup-fixture.sh
```

The builder uses the app's compiled preview classes as a classpath and packages only the test classes in the fixture APK. Both APKs must use the same development signing certificate. You can override the fixture keystore path with `VOICEFLOW_KEYSTORE`; this builder uses the existing preview alias and development password, just like the preview app.

Install the APKs on a disposable test device or emulator:

```sh
adb install -r build/VoiceFlow.apk
adb install -r build/tests/backup/VoiceFlow-backup-fixture.apk
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupCredentialPolicyTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupArchiveTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupHistoryDeletionTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupRestoreBusyTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupGenerationPickerTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.ThoughtStorageTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.ThoughtDailySummaryCacheTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.ThoughtMinimumLengthTest
```

`BackupCredentialPolicyTest` uses a separate preferences file, synthetic credential envelopes and a temporary draft. It checks that snapshots exclude secrets, legacy schema 1 keys are discarded, all Dictation/Notes/Translation model settings remain portable, thoughts and recap settings change the portable fingerprint while local credentials do not, same-endpoint device credentials remain, changed endpoints (including changed paths) and invalid local endpoints clear device credentials, a fresh phone never imports an archived key, and failed preference commits roll back data and credentials together. Its commit failure is injected after the first write, then the rollback is allowed to complete; it is not a physical storage failure test.

`BackupArchiveTest` checks AES-GCM archive authenticity, independent randomness, Unicode, bounded strict schema validation and malformed archive rejection. It exercises an encrypted schema round-trip with 300 thoughts plus 100 dictations, validates the 10,000-thought limit, checks all model settings and backward-compatible defaults, and tests portable freshness after summary, draft and switch edits. Its synthetic legacy key fixture confirms old encrypted archives can still be decoded while credentials are stripped before restore.

`BackupGenerationPickerTest` opens the production native restore picker with five synthetic generations, checks all five appear in its adapter and selects the fifth. It does not authorize Google or invoke backup transport. This is a native list/callback check; it does not claim a physical touch or corrupted Drive archive test.

The production encryption and parser bounds can also be checked on the JVM:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk bash tests/run-backup-archive-jvm.sh /tmp/backup-archive-tests
```

This executes real archive encryption/decryption for 300 and 10,000 thoughts with 100 dictations, applies the same read/write allocation guard, and checks Unicode, tamper rejection and pre-write UTF-8/complexity bounds. Android SDK JSON types provide compile-time linkage only; this JVM suite does not execute Android's schema parser. `BackupJsonBoundsTest` also runs in the ordinary pure Java suite.

`BackupHistoryDeletionTest` checks deletion and Clear history against newly created encrypted archives, including removal of matching raw/clean drafts, preservation of unrelated unsaved drafts and absent-entry behavior.

`BackupRestoreBusyTest` uses isolated preferences and temporary synthetic busy counters to check that active note processing, audio/call finalization and queued thought saves refuse restore without replacing local data. Run it only on an idle disposable installation with no configured Google backup.

`ThoughtDailySummaryCacheTest` uses its own preferences file and synthetic UTC/calendar-day entries. It checks that identical same-date history and thought inputs keep independent recaps; text, added entries, model, endpoint, timezone and date changes miss the stale cache; original-source changes invalidate thought recaps while retaining default history compatibility; pruning and entry deletion affect only the requested scope; and concurrent writes retain every day in both scopes. The default history API is checked alongside the scope overloads. It does not change the device timezone, record audio, or call a provider.

`ThoughtMinimumLengthTest` uses isolated preferences/files, generated WAV segments, and injected ASR/parser results. It derives the one/two-line boundary from native `TextPaint` at the canonical 320-unit width and 17-unit sans-serif size, checks whitespace and Unicode shaping, and repeats storage decisions with isolated density/font-scale configurations. It checks deletion plus tombstones only for normally finished, complete, new short captures; raw/source retention for qualifying thoughts; and preservation of offline, legacy, edited, superseded, interrupted, missing-part and uncertain captures. Wrong-schema provider replies must reject without changing pending data; an actual successful empty string may use the zero-line policy. No microphone, provider call, device display setting, or real preference is used. Run it on an idle disposable installation; fixture-created retry hooks are removed during cleanup. The layout-failure fail-open branch cannot be injected through this fixture and is not claimed as exercised.

These tests do not use real API credentials, call an API provider, exercise Google sign-in, upload to Drive, restore from Drive, or prove physical power-loss recovery. Report build success separately from instrumentation success. If no disposable Android device or emulator is available, report these fixtures as compiled only; compilation does not establish a runtime pass.
