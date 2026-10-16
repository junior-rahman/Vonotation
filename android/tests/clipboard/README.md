# Clipboard storage fixture

Build the current preview app first. With JDK 17 and Android SDK 35 available:

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash tests/build-clipboard-fixture.sh
```

The builder uses the compiled production classes as its classpath. The fixture APK contains the tests, not a second copy of the app implementation. It uses the preview signing key; the installed target app must have the same signer.

On an isolated Android device or emulator, install the current app and fixture, then run:

```bash
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb install -r build/tests/clipboard/VonoTation-clipboard-fixture.apk
adb shell am instrument -w app.voiceflow.clipboard.fixture/app.voiceflow.ClipboardStorageTest
```

Status for this update: compiled only; not executed on an Android device or emulator. Compilation is not evidence that the scenarios pass at runtime.

The scenarios exercise the production SQLite archive with synthetic text: exact 30-day expiry, deferred physical cleanup, no count-based eviction, fixed event timestamps, matching own-copy callback suppression, 50-item keyset pages, full text larger than a CursorWindow row, whitespace and Unicode, legacy migration with unknown dates, sensitive event hash exclusion, clock rollback visibility, deletion of text chunks and database reopen.

Each run creates a uniquely named fixture database in the target app's private database directory and deletes it in cleanup. It does not open or alter the user's `clipboard-history.db`, read or write the real system clipboard, invoke a provider, or start a recorder. A force-stopped fixture may leave its synthetic `clipboard-fixture-*.db` file behind.

These storage scenarios do not verify the platform clipboard listener, default keyboard access, actual password-field metadata callbacks, JobScheduler execution across reboot, Daybook touch/scroll rendering, or real cross-app paste. Android restricts background clipboard reads; the app can retain only copies Android makes accessible while the app is open or it is the selected keyboard. Logical reads hide expired entries immediately; Android may defer the background job that physically deletes expired rows. Clipboard text remains local and is excluded from Drive backup.
