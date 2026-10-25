# Voitation 0.1.22-preview — clipboard stays available

Recovered dictation no longer disappears after 12 seconds or a keyboard focus event. The preview stays available until a confirmed paste or explicit dismissal. If input focus disappears or moves to a password/PIN field, the preview hides and returns when a usable input is active. A failed paste keeps the copy available to retry.

The Android regression test passed 64 assertions using an external editor and real taps. It waits 13 seconds, verifies a rejected paste retains the preview, checks privacy and focus transitions, pastes exactly once at the current caret, and checks explicit dismissal. Both Android builds, full lint, 43 Java suites, recovery regressions, and native light/dark, large-text and unfolded checks passed.

Package `app.voiceflow`, version code 94, Android 8.0 or later. The existing signing identity is retained so this installs over the current app. Sand & Cocoa, V-wave and Thought Loop remain unchanged. Physical phone testing is still needed. iOS testing and backup/OAuth work remain paused.

Source: `5eeee030876f84aa302037d601ee20a221840adc`. [Native Android verification](https://github.com/junior-rahman/Vonotation/actions/runs/40239234529).
