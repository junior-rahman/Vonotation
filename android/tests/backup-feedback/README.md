# Native backup feedback fixture

Build the exact production preview APK and then run:

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk tests/build-backup-feedback-fixture.sh
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb install -r build/tests/backup-feedback/VoiceFlow-backup-feedback-fixture.apk
adb shell am instrument -w app.voiceflow.backup.feedback.fixture/app.voiceflow.BackupFeedbackTest
```

Use an isolated, disposable emulator installation. Match the installed production
APK's version, hash and signing certificate to the fixture build inputs. Production
classes are classpath-only; the fixture APK contains only `BackupFeedbackTest` and
its inner classes. The builder supports `javac`, an explicitly provided ECJ JAR,
or the existing `jdk.compiler` module, as the other native fixture builders do.

## What this checks

The installed production `BackupActivity`, its actual private dispatcher and its
native widgets are exercised. The fixture temporarily seeds **synthetic** local
preferences, with automatic backup Off, and blocks HTTP/HTTPS. It restores the
original preference and backup-secret maps in `finally`; interrupted instrument
runs cannot guarantee cleanup, so do not run this on a real-data installation.

1. The feedback panel is outside the settings `ScrollView`. Its visible bounds
   remain unchanged at the top and bottom of the settings, without overlapping
   or eliminating the scroll viewport.
2. On an emulator without Play services, one physical down/up pair each on
   Connect and Restore must immediately produce the real missing-services error.
   No retry, accessibility click or `performClick` substitutes for the first tap.
   The fixture skips Google authorization entirely if Play services is installed.
3. A controlled **synthetic** worker calls the actual `perform` → `begin` → `task`
   path through reflection. Production contains no fixture entry point or fake
   success path. Progress appears synchronously before the worker completes,
   remains pinned, and the native progress bar is indeterminate. When Android
   animations are disabled, the static "In progress" state remains while the
   animated indicator is hidden. No percentage is invented.
4. Two physical taps on the disabled Restore action while the controlled worker
   is paused, plus a second dispatcher request, cannot start duplicate work or
   change the operation ticket. Configuration-based action guards return after
   completion; disconnected upload remains disabled.
5. Releasing the worker produces the actual dispatcher's persistent success
   result. A separately controlled exception produces a visible error, strips
   control characters and leaves retry actions usable. Results remain visible
   while scrolled. One first physical Details tap opens the complete result or
   error text, and one OK tap closes it.
6. A delayed worker is intentionally held across a physical Back tap. Activity
   destruction increments the operation ticket, clears queued owned password
   buffers, and rejects the late completion. A newly launched Activity does not
   inherit the previous owner's busy state or result.
7. Automatic backup stays Off, no job is scheduled and no HTTP/HTTPS request is
   made. Executed assertions and physical taps are counted at runtime.
8. A new operation clears a previous inline Google error, and a blocked Google
   connection offers a usable pinned recovery action. Missing-services assertions
   preserve the actionable message while allowing its actual ConnectionResult code.
9. During a **synthetic** controlled `Restoring…` dispatcher stage, a physical
   header Back tap opens the stay-on-screen explanation and keeps worker ownership.
   Stay preserves the operation, and completion releases busy state. No local data
   is replaced by this scenario; it does not prove restore atomicity or resilience
   to Home, process death, power loss, or Android task removal.

Screenshots are exported through `UiAutomation.takeScreenshot()` to the target
app's external-media directory. Names include `1910` and `synthetic`; the runner
must still record the actual installed APK hash/version and a fresh crash/ANR log
interval. These screenshots show the native production Activity with clearly
synthetic worker results. They are not Google success evidence or HTML renders.

## Additional runtime scenarios

Run this fixture with normal animations and again with the emulator's animation
scales disabled; record the setting and restore it afterward. Also inspect light
and dark themes, portrait/landscape, and large text: the fixed panel must stay
readable, leave a usable settings viewport, and offer Details for truncated text.
Verify that Back during a real pending consent screen, canceling Google consent,
wrong-password recovery, canceling the restore replacement confirmation and
rotation during work never trigger uploads, data replacement or automatic opt-in.
Those flows need separate authorized native/Google testing and must not be counted
as covered by this controlled fixture.

Existing `DriveCloudTest` and archive fixtures remain the authority for offline
Drive-generation, archive-exclusion, rollback, cancellation and credential-policy
regressions. This fixture is **not live Google sign-in, upload, remote restore,
network throughput, real haptic-motor or end-to-end encryption verification**.
