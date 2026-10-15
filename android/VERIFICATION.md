
## 1.9.15 round4 — haptics + reboot (2026-10-12)
- Root cause: haptics used only View.performHapticFeedback(CONTEXT_CLICK) with no VIBRATE permission; Samsung One UI ignores that constant and mutes view haptics when system touch feedback is off.
- Fix: Haptics.java drives VibratorManager directly (predefined CLICK/TICK/HEAVY_CLICK, one-shot 18-35 ms fallback, USAGE_ACCESSIBILITY on API 33+); VIBRATE permission; fired on bubble press (ACTION_DOWN), release/tap (Daybook.feedback), hold-menu pop (long press), and when Haptic feedback is switched on. Default ON.
- Emulator dumpsys vibrator_manager after one tap on a Home button:
  TOUCH  | app.voiceflow (uid=10148) | performHapticFeedback(constant=6) | cancelled_superseded
  ACCESSIBILITY | app.voiceflow (uid=10148) | finished | Prebaked=TICK(MEDIUM, with fallback)
  -> the direct vibrator request runs and wins. Bubble press/pop call the same Haptics.fire; not separately captured because the emulator (no HW accel) kept ANRing System UI before the overlay could be tapped.
- Reboot: adb reboot x2 -> FlowAccessibility re-bound ("Bubble service connected"). With service disabled, BOOT_COMPLETED -> notification "Tap to turn VoiceFlow back on".

## 1.9.15 round5 — approved designs (2026-10-12, emulator API 35, no HW accel)
- Settings: four cards render with Copper/Sand tokens (out/round5-settings.png, round5-settings-dictation.png).
- Button: glass square + coral waveform V on Contacts "First name" (out/round5-button.png); keyboard-up baseline glyph (round5-slide-to-keyboard.png).
- Fan: a stationary hold opens Cancel/Delete/Keyboard. At the right edge it fans left: Delete at 255° (nearest the thumb), Keyboard at 185° (round5-arcfan.png), then auto-closed after idle.
- Slide to Keyboard: the hover gave a TICK; release opened the typing keyboard. A hold with the keyboard up dismissed it (mInputShown=false).
- Dwell on Delete: "Meet me at Niagara" became "Meet me at " (exactly one word after the dwell fix).
- dumpsys vibrator_manager (usage ACCESSIBILITY, app.voiceflow): press Prebaked=CLICK 02:29:31.988, pop Step=35ms 02:29:33.807 (the emulator lacks HEAVY_CLICK, so this is the one-shot fallback), hover Prebaked=TICK 02:29:39.521, select TICK 02:29:41.832.
- Not verified: the exact 300ms hold timing and spring smoothness (the emulator delivered input 1-5s late); the reduced-motion fade; TalkBack traversal; the caret-guard rotation, left-edge and bottom fan directions on screen (logic ported from arc.js but not driven); the real glass look and haptic feel on the Fold.
- Fix found in testing: auto-close no longer fires while the finger is still down; releasing on Delete after a dwell delete no longer deletes a second word.

## 1.9.15 round6 — solid button, no auto-close, one-tap tiles (2026-10-12, emulator)
- Solid button and solid tiles: out/round6-button.png, out/round6-arcfan.png.
- No auto-close: the fan window was still present 14s after a hold-and-release.
- Tap mode: hold-and-release, then ONE tap on Keyboard -> mInputShown=true. ONE tap on Delete -> "Meet me at" became "Meet me ".
- Slide to Keyboard still works: hover TICK 02:48:32.552, select TICK 02:48:34.848, mInputShown=true.
- The double tap did not reproduce on the emulator: one tap on Keyboard opened the keyboard even on the round5 code. The changes are defensive fixes for the paths that can need a second tap on a real phone (see the report).

## 1.9.15 round7 (2026-10-12, emulator API 35)
- Cause of "Delete registers once and the fan disappears": by design in round5/6, act(Delete) called close(). Separately, deleteWord read a possibly cached node; it now calls refresh() so rapid taps see the updated text. There was no debounce or busy flag. The fold-back animation was the close itself.
- 3 taps on Delete, about 150ms apart, in tap mode: 'one … eight' -> '… seven ' -> '… six ' -> '… five ' (3 words); fan window still present.
- Hold on Delete for 1.5s: 5 sequential word deletes; fan still open.
- Slide and release on Delete: one word deleted; fan still open.
- Tap in the field: the cursor moved (end=10) and the fan stayed open; the next Delete removed the text before the new cursor ('th' of 'three').
- Tap elsewhere (bottom of screen): fan closed (ACTION_OUTSIDE with no field selection change within 350ms).
- One tap on Keyboard: fan closed, mInputShown=true. Cancel tap: fan closed.
- Testing note: `uiautomator dump` unbinds other accessibility services, which killed VoiceFlow's service mid-test in earlier runs; verification now uses logcat and dumpsys only.
- Not verified: Niagara launcher (installed v1.16.32 from its official GitHub release, but the emulator stalled in Niagara onboarding with system ANRs before the search could be opened); coral look on the Fold; dark-background fan screenshot (see report).
