# VoiceFlow 1.9.10

Private preview update based on the verified VoiceFlow 1.9.9 source checkpoint.

## Intended changes
- Floating microphone adopts the existing Daybook colors and identity, with explicit tap feedback and reduced-motion-aware state changes.
- Permissions become a direct Settings panel with separate microphone, floating-service and optional-keyboard states. Each row opens the appropriate Android control, and status refreshes on return. Duplicate setup buttons are removed.
- Show bubble sits beside bubble size and haptics. API connection and history privacy remain available under Advanced.
- Backup has a fixed, visible operation/result area with immediate activity indication, honest operation descriptions, success/error states and complete details.

## Privacy and compatibility
- No changes to encryption, backup contents, restore overwrite confirmation, automatic-backup opt-in, private password/PIN handling, draft invalidation or provider-key isolation are intended.
- The original preview signing identity is retained so this can update the matching preview installation. This is not a Play/production release.
- Google's authorization/testing notices are controlled by Google and are not hidden or bypassed. The app requests only its private Drive app-data scope. Real account consent and Drive persistence are not established by synthetic tests.
- Physical vibration strength must be confirmed on a phone. Emulator feedback checks establish API/setting behavior, not a vibrating motor.

## Validation
Final build, lint and native results are recorded in VALIDATION-1.9.10.md. Historical test receipts do not establish this version's behavior.
