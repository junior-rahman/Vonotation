# VoiceFlow handoff (1.9.15, build 26, round7)

## What it is
VoiceFlow is an Android dictation app (applicationId `app.voiceflow`, minSdk 26, targetSdk 35, Java, no Kotlin). A floating bubble appears over any app whenever a text field is focused. Tap it to record. Audio goes to a cloud speech-to-text API (Groq/OpenAI-compatible, using the user's own API key), comes back verbatim in English (optional AI cleanup), and is inserted into the field. History is kept on the phone (Daybook Home screen). The UI uses the "Copper and Sand" palette (Daybook.java).

## Architecture (app/src/main/java/app/voiceflow)
- **FlowAccessibility.java**: the core. An AccessibilityService that finds the focused field, draws the bubble (TYPE_ACCESSIBILITY_OVERLAY), runs dictation, inserts text, toggles the typing keyboard, and does the arc fan wiring (`openFan`, `deleteWordBeforeCaret`, `openTypingKeyboardOnce`, `caretLine`). Launcher search detection is `detectHomeInput` / `homeInputOpen`. Config: `res/xml/accessibility.xml`.
- **FloatingIcon.java**: draws the glyphs: waveform V (7 bars; keyboard state = compressed bars + baseline), cancel, finish, delete, keyboard.
- **ArcFan.java**: the expanded state (see the specs below). Its own small overlay window; tap mode vs slide mode; Delete repeat; outside-tap logic.
- **Haptics.java**: direct Vibrator/VibratorManager. PRESS=CLICK, RELEASE=TICK, POP=HEAVY_CLICK, with a 18–35ms one-shot fallback and USAGE_ACCESSIBILITY on API 33+. Respects the `haptic_feedback` pref (default on).
- **FlowKeyboard.java**: an optional IME with no visible view (VoiceFlow keyboard). It gives a direct input connection; typing switches back to the user's normal keyboard.
- **Settings**: MainActivity.java (`settings()`: four expandable cards Cloud setup / Dictation / Backup / Permissions; `advancedPanel()`, `apiDialog()`), Daybook.java (tokens, cards, buttons), Ui.java, Store.java (prefs), Vault.java (API key), KeyMask.java (masked key preview), ModelCatalog.java.
- **Reboot**: BootReceiver.java + BubbleHealth.java (notification "Tap to turn VoiceFlow back on").
- **Backup** (deferred): BackupActivity/DriveBackup/DriveAuth.

## Build
- JDK 17 + Android SDK (platform 35, build-tools 35.0.0). On the dev box: `source /workspace/voiceflow/env.sh` sets JAVA_HOME, ANDROID_SDK_ROOT and VOICEFLOW_KEYSTORE.
- `bash build-apk.sh` runs `./gradlew assemblePreview`, then apksigner verify. Output: `build/VoiceFlow.apk` (from `app/build/outputs/apk/preview/app-preview.apk`).
- Do not change versionCode/versionName unless the user asks.

## Signing
- The original release key is MISSING. All 1.9.15 builds use a preview keystore that is NOT in the source zip. On the dev box it is `/workspace/voiceflow/keys/voiceflow-preview-1.9.15.keystore` (alias `voiceflow`; the passwords are in `app/build.gradle`). Point `VOICEFLOW_KEYSTORE` at it, or put it at `preview-signing/voiceflow.keystore`.
- Cert SHA-1 A7:80:92:53:A2:9D:DD:19:16:F9:B4:B2:98:92:31:95:DA:58:EB:3F (SHA-256 5dca2219…7568a).
- Builds signed with this key won't install over pre-1.9.15 builds (old key). The user must back up and uninstall first. Losing this keystore means another uninstall.

## Current state and history
- 1.9.13 source base; 1.9.15 plan: English cloud only, verbatim transcripts (answers/refusals rejected), opt-in recaps, retry buffer, compact settings, API-key-required message.
- round4: haptics fixed (root cause: only View.performHapticFeedback(CONTEXT_CLICK), no VIBRATE permission; Samsung ignores it). Reboot notification.
- round5: approved designs built (square button, arc fan, four-card Settings).
- round6: glass removed, no idle auto-close, tap-mode tiles act on the first touch-up, explicit keyboard open.
- round7: coral button; Delete repeatable and never closes the fan; hold-repeat; field taps pass through; outside taps close; Niagara/launcher search detection.

## Approved design specs
- **Button**: 48dp square, 12dp corners, solid coral fill #B3422F→#A33A28 (top to bottom), white thin-stroke waveform V, soft dark shadow, no light surface. Keyboard visible = bars settle onto a baseline (no badge). Glow treatment B is behind `GLOW_WHILE_DICTATING=false`.
- **Arc fan**:
  - Hold 300ms: Cancel (darker #8F3424) over the button; Delete + Keyboard (coral, white glyphs) fan out on a 76dp arc.
  - Edge-aware: right edge fans left [255°,185°], left edge fans right [285°,355°], near the bottom fans up [155°,85°]. 8dp screen margin; 48dp targets at least 8dp apart. Delete is nearest the thumb.
  - Caret guard rotates ±5° steps only when the fan opens. Staggered spring along the arc; fold back in reverse. Reduced motion = 150ms fade.
  - Slide to select: 12% magnify + TICK on hover. Releasing on Delete deletes and stays open; on Keyboard opens the keyboard; on Cancel closes; on empty space stays open.
  - Hold-and-release without moving = tap mode.
  - Delete: each tap deletes one word before the cursor and stays open; hold = first word at 450ms, then every 300ms.
  - Taps in the focused field pass through and keep it open; other outside taps close (ACTION_OUTSIDE + no selection change within 350ms). Keyboard and Cancel close.
  - HEAVY_CLICK on pop. Labels only on hover/focus. No idle auto-close.
- **Settings**: Copper and Sand tokens unchanged (paper #F1E9DE, surface #FFFAF3, ink #342820, muted #726255, accent #8F5736, soft #E9D8C5, line #D8C8B6, action #BC4C36, coral #B3422F). Four cards, one open at a time.

## Open issues and deferred work
- **Drive backup**: Google sign-in "cancelled" because the OAuth Android client in Cloud project voiceflow-37d9d is registered to the old SHA-1 68:24:BC:2C:…. Add an Android OAuth client for SHA-1 A7:80:92:53:A2:9D:DD:19:16:F9:B4:B2:98:92:31:95:DA:58:EB:3F (package app.voiceflow).
- **Language detection**: paused (English only, `language=en`).
- **Stale tests**: tests/fixtures/DaybookUiTest.java and FloatingFeedbackTest.java expect the old Settings layout and the old performHapticFeedback haptics.
- **Keyboard tile on the Galaxy Fold**: reported as sometimes needing a hold or a second tap. Not reproducible on the emulator. Investigate the FlowKeyboard IME path (`FlowKeyboard.showTypingKeyboard`) when the VoiceFlow keyboard is the selected IME.
- **Niagara Launcher**: detection added (round7) but not confirmed on a device.
- Not verified on a device: left-edge/bottom fan directions, caret-guard rotation, TalkBack order, reduced motion.
- Delete uses ACTION_SET_TEXT. Some apps' fields may reject it.
- Emulator testing tip: `uiautomator dump` unbinds other accessibility services (it kills VoiceFlow's service). Use logcat/dumpsys, and re-enable the service after a dump.

## Starter prompt (paste into the new tool)
> You're continuing VoiceFlow, an Android (Java) dictation app. Read HANDOFF.md first, then FlowAccessibility.java, ArcFan.java, FloatingIcon.java, Haptics.java and MainActivity.java. Keep version 1.9.15 / build 26 and the preview signing key (`VOICEFLOW_KEYSTORE`; passwords in app/build.gradle) unless I say otherwise. Build with `bash build-apk.sh` (JDK 17, SDK 35). Follow the approved design specs in HANDOFF.md exactly: coral 48dp/12dp button with white waveform V; arc fan rules. Keep the existing Copper and Sand colors. Before changing behavior, explain the root cause; after changing it, verify on an emulator or device and tell me plainly what was and wasn't verified. First task: <describe the task>.
