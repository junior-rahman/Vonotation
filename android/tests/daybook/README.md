# Native Daybook regression fixture

Build the preview app, then run `tests/build-daybook-fixture.sh` with `ANDROID_SDK_ROOT` set. Install the resulting fixture APK and the matching signed preview app. Production classes are classpath-only; the fixture contains only `DaybookUiTest` and its inner classes, signed with the existing VoiceFlow Preview certificate.

```sh
adb shell am instrument -w app.voiceflow.daybook.fixture/app.voiceflow.DaybookUiTest
```

This fixture launches the installed production `MainActivity` and `BackupActivity`. It seeds clearly synthetic history, cached recaps, dictionary and preferences, blocks HTTP/HTTPS calls in the target process, and restores the original preferences, backup-secret preference map, clipboard and in-memory draft in `finally`. Cached recap display starts On, while on-device mode and an empty provider credential prevent AI requests. Each seeded cache is checked against its exact day/model/endpoint/timezone fingerprint before launch. Run only on a disposable test installation; an interrupted instrumentation cannot execute that cleanup.

Each direct Settings row receives exactly one physical touchscreen down/up pair. Finding or scrolling to a row does not click it. The test never retries a failed tap, never calls `performClick`, never substitutes an accessibility click, and never counts a second-tap workaround as a pass. It also checks native return focus, real option persistence, actual first-tap dictionary editing with physical key events, newest-first days/entries, inline expansion/recreation, clipboard Copy, Delete, and the Keep/Clear history confirmation. The real Show daily recaps switch must hide recap cards when Off and recover valid offline cached cards when On; changing visibility must preserve saved dictations and send no requests.

Backup checks exercise the actual disconnected screen, its Off default and disabled upload/automation guards. On an installation without Google Play services, physical Connect and Restore taps must produce the app's missing-services message without changing backup configuration, scheduling a backup job or making external requests. The fixture deliberately does not initiate Google consent on a device with Play services. These checks are **not live Google authorization, upload or Drive restore verification**.

The new `haptic_feedback` preference is verified through the production backup snapshot, real password-based AES-GCM archive encode/decode and local restore. Schema 1 backups without that setting must default to `true`; malformed non-boolean settings must be rejected before mutation. This verifies preference portability, not a physical haptic motor.

Four PNGs are taken through Android `UiAutomation.takeScreenshot()` from the actual installed native screens and written to the target app's external media directory:

- `daybook-home-199-rebuilt.png`
- `daybook-settings-199-rebuilt.png`
- `daybook-backup-199-rebuilt.png`
- `daybook-google-unavailable-199-rebuilt.png` (only when Google Play services is absent)

The screenshot suffix is only a filename. The runner must independently pin the installed production APK's version/hash and associate it with these captures. Screens show synthetic QA data; none are HTML/reference renders.

Executed assertions (including input-delivery and screenshot-export assertions) are counted at runtime and returned in the instrumentation result; physical taps are reported separately. No expected total is hard-coded. Compiler/signature checks do not establish a native pass. Record the clean version-specific log interval separately; a result string alone is not a crash/ANR-free runtime receipt. A failed screenshot export is a fixture failure, and failed first taps include the phase plus a native control-tree diagnostic. Cleanup failures also fail the instrumentation.
