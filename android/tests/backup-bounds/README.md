# BackupActivity native bounds fixture (1.9.10)

This separate instrumentation package runs the actual signed app's BackupActivity. It does not contain app classes or production test hooks. All status and pending-work results are explicitly synthetic; HTTP/HTTPS requests are blocked. No Google consent, upload, real restore, or real data is exercised.

## Build

Build the production preview classes first using the official toolchain. Then:

```sh
source /workspace/scratch/201e51819cdc/voiceflow_toolchain/env.sh
./tests/build-backup-bounds-fixture.sh
sha256sum build/tests/backup-bounds/VoiceFlow-backup-bounds-fixture.apk
```

APK: `build/tests/backup-bounds/VoiceFlow-backup-bounds-fixture.apk`

Component: `app.voiceflow.backup.bounds.fixture/app.voiceflow.BackupBoundsTest`

The builder packages only BackupBoundsTest and its inner classes; actual app classes are D8 resolution inputs only. It signs with the same preview certificate as the target app. The test checks versionName 1.9.10; the external runner must also verify and record the exact target APK hash before installation.

## Runner prerequisites and configuration matrix

Only run on the authorized disposable emulator, after other native suites finish. Do not run concurrently with another instrumentation suite or input injector. Preserve and restore every runner-controlled setting in a `finally`/shell trap, including pre-existing overrides and absent settings. This fixture never changes global font scale, animation settings, display size/density, or user rotation settings. Its requested orientation belongs only to the test Activity, which is finished in cleanup. SharedPreferences and backup-secret snapshots are restored and verified in cleanup.

Record original `wm size`, `wm density`, `settings get system font_scale`, `settings get system accelerometer_rotation`, `settings get system user_rotation`, and all three global animation scale settings. Keep density unchanged. Preserve absent setting values by deleting rather than writing the literal string `null` on restore. Preserve existing wm size/density overrides, rather than unconditionally resetting them. Wait for configuration delivery before each run; the fixture independently asserts the expected actual font scale.

Suggested minimum matrix:

1. Original display, font scale 1.0, normal/current animation settings.
2. Original display, font scale 2.0, normal/current animation settings.
3. Reduced display of approximately 360 by 420 density-independent pixels, font scale 1.0.
4. The same reduced display, font scale 2.0, with window_animation_scale, transition_animation_scale, and animator_duration_scale all zero.

Calculate reduced physical width/height from the current density (`dp * densityDpi / 160`). On a 420dpi emulator, 946 by 1102 pixels is approximately 360 by 420dp. Pass `-e maxHeightDp 450` for both reduced-display runs, so accidentally testing the full-height screen cannot silently count as small-window coverage. A reduced-display run exercises a genuinely constrained native app layout, but is not proof of actual split-screen/freeform window-manager behavior. If a supported split-screen/freeform route is used instead, record it and use the same measured-height assertion.

Each invocation requests portrait, landscape, and portrait again, checks current bounds, and rotates away/back during a pending local worker. Thus the matrix covers normal/enlarged fonts in portrait, landscape, and constrained-height layouts. The instrumented activity must be allowed to honor requested orientation. The fixture reports a failure rather than claiming a pass if the runner/device ignores it.

Example invocation after installing the approved fixture APK and setting the chosen external configuration:

```sh
adb -s emulator-5554 shell am instrument -w \
  -e case small-font2-reduced-motion \
  -e fontScale 2.0 \
  -e maxHeightDp 450 \
  app.voiceflow.backup.bounds.fixture/app.voiceflow.BackupBoundsTest
```

Use unique `case` names per run. `fontScale` is an assertion, not a mutation. Optional `minHeightDp`/`maxHeightDp` assert actual native decor height. `orientations` defaults to `portrait,landscape,portrait`; it can be narrowed to isolate a failure. `exerciseBusy` defaults to true; false skips only the synthetic busy/rotation section, not other checks. Always run the full default sequence for acceptance.

## Evidence and pass interpretation

An ADB exit code of zero alone is insufficient. Require `INSTRUMENTATION_RESULT: result=PASS`, no `INSTRUMENTATION_RESULT: failure=`, and final instrumentation code `-1`. Record the target app APK hash, fixture hash, actual Android image/API, display size/density, font settings, animation settings, per-run logs, screenshots, and clean app-specific crash/ANR interval. This fixture was built separately; building it does not count as runtime validation.

The fixture emits `measurement` records and a final JSON `bounds` array with actual screen-coordinate root, viewport, footer, Details and title rectangles, available height, orientation, font scale, compact flag, visible stage text, title ellipsis count, and animation/progress state. It asserts:

- A usable settings viewport at least 48dp high and 120dp wide, with reachable scroll overflow and top return.
- Full footer containment, no viewport overlap, no clipping, unchanged pinned bounds on scroll, and the production compact threshold matching actual height/font.
- Actual title visibility in every state; busy title is the real current operation. Reduced-motion busy state must retain the title even when its spinner is hidden.
- Terminal Details is fully visible and at least 44dp in both dimensions, with no touch-focus interception.
- Exactly one physical touchscreen DOWN/UP opens a fresh actual AlertDialog containing the full status. There is no fallback tap, performClick, focus manipulation, or scroll-to-Details. Native OK and Back dismissal are each tested, followed by another independent first-tap opening.
- Orientation interruption preserves the actual operation owner and dispatch count. Fresh Activity return after native Back does not inherit completed/busy state.

`action_text` records separately inspect actual enabled Connect Google and Restore from Google Drive actions after scrolling each into view. They check complete native text layout against available content height, text extent, ellipsis and visible bounds. Failures are collected in `action_text_issues` and cause failure at the end, allowing footer/Details/orientation evidence to finish first. This catches the existing fixed 44dp action-height risk at large fonts without letting hidden TextViews count as successful rendered text. No Google action is tapped.

Screenshots are saved under the app's external media directory, with names `backup-bounds-1910-<case>-<stage>-synthetic.png`. The final report includes the exact device directory. Failed action-text cases also capture the offending visible action.
