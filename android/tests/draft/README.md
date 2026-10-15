# Draft recreation fixture

Build the preview app, then run `tests/build-draft-fixture.sh` with `ANDROID_SDK_ROOT` set. Install the produced fixture APK together with the matching preview app.

Run the corrected-app regression:

```sh
adb shell am instrument -w app.voiceflow.draft.fixture/app.voiceflow.DraftRecreationTest
```

For the immutable 1.9.6 checkpoint, the separately staged fixture may be run with `-e observe_original true` to verify the candidate before calling it a confirmed bug.

The fixture launches native MainActivity, captures its actual `onSaveInstanceState` Bundle containing synthetic draft A, opens BackupActivity, and verifies that the parent has stopped. It restores synthetic draft B through `BackupData.restore`, then asks Android to recreate MainActivity in the same process. The instrumentation supplies the original pre-restore Bundle A at `callActivityOnCreate`; it does not replace that Bundle with a fresh post-restore state. The corrected app must keep B. The observation mode expects the original app to overwrite B with A and reports `REPRODUCED` only if that happens.

All preferences and draft content are synthetic and restored after the test. This does not perform Google sign-in or a remote Drive restore. A compiler/signer pass alone does not establish a native lifecycle result. Android system dialogs, boot failures or ADB failures can block the native test.

For a real process restart, run each case separately (`restore`, `delete`, `clear`):

```sh
adb shell am instrument -w -e process_stage seed -e process_case restore app.voiceflow.draft.fixture/app.voiceflow.DraftRecreationTest
adb shell am force-stop app.voiceflow
adb shell am instrument -w -e process_stage verify app.voiceflow.draft.fixture/app.voiceflow.DraftRecreationTest
```

The seed phase leaves its synthetic state and caches the exact pre-mutation Bundle. It flushes the preference mutation before the intentional stop; it does not simulate a physical preference-write failure. Verify requires a different process ID, passes that cached Bundle to a real fresh MainActivity, and checks that replaced/deleted A cannot return or appear in a newly created backup snapshot. Committed history and dictionary must remain. The local epoch stores no draft text; B remains ephemeral until a later saved-instance-state Bundle can recover it. Delete and clear cases also prove that unrelated live B is kept before process death. Verify restores the original test preferences and removes the private fixture cache.
