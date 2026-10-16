# Isolated backup checks

Build the app with `./build-apk.sh`, then build the fixture with Java 17 and Android SDK 35:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk ./tests/build-backup-fixture.sh
```

The builder uses the app's compiled preview classes as a classpath, and packages only the three tests in the fixture APK. Both APKs must use the same development signing certificate. You can override the fixture keystore path with `VOICEFLOW_KEYSTORE`; this builder uses the existing preview alias and development password, just like the preview app.

Install the APKs on a disposable test device or emulator:

```sh
adb install -r build/VoiceFlow.apk
adb install -r build/tests/backup/VoiceFlow-backup-fixture.apk
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupCredentialPolicyTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupArchiveTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupHistoryDeletionTest
adb shell am instrument -w app.voiceflow.backup.fixture/app.voiceflow.BackupRestoreBusyTest
```

`BackupCredentialPolicyTest` uses a separate preferences file, synthetic credential envelopes and a temporary draft. It checks that snapshots exclude secrets, legacy schema 1 keys are discarded, provider/model/endpoint settings remain portable, same-endpoint device credentials remain, changed endpoints (including changed paths) and invalid local endpoints clear device credentials, a fresh phone never imports an archived key, and failed preference commits roll back data and credentials together. Its commit failure is injected after the first write, then the rollback is allowed to complete; it is not a physical storage failure test.

`BackupArchiveTest` checks AES-GCM archive authenticity, independent randomness, Unicode, bounded strict schema validation and malformed archive rejection. Its synthetic legacy key fixture confirms old encrypted archives can still be decoded while credentials are stripped before restore.

`BackupHistoryDeletionTest` checks deletion and Clear history against newly created encrypted archives, including removal of matching raw/clean drafts, preservation of unrelated unsaved drafts and absent-entry behavior.

`BackupRestoreBusyTest` uses isolated preferences and temporary synthetic busy counters to check that active note processing, audio/call finalization and queued thought saves refuse restore without replacing local data. Run it only on an idle disposable installation with no configured Google backup.

These tests do not use real API credentials, call an API provider, exercise Google sign-in, upload to Drive, restore from Drive, or prove physical power-loss recovery. Report build success separately from instrumentation success.
