# VoiceFlow 1.9.10 validation

## Result
**Passed supported-environment checks:** all 25 selected final-candidate native invocations and all five pure-Java suites passed. Real Google account/Drive persistence and physical vibration remain unverified; this is a private preview, not device certification.

## Final artifact and build
- Version **1.9.10**, version code **20**.
- APK SHA-256: `a4c45e8279cfebb9b1bb9dc3f59c7c2ed6799c4efdc0516c9b104d42fc9ca092`.
- Original Preview signing identity retained; v2 signature and alignment verified. No signing key is disclosed in the distributed source.
- Installed APK was pulled back and independently hashed twice, including after emulator recovery; both hashes match the final artifact.
- Fresh official Android CLI build: aapt2, javac, D8, zipalign and apksigner. This is **not Gradle assemblePreview**.
- Official standalone Android lint: **0 errors, 27 warnings**. The manual lint model omits Gradle-only checks; this is **not Gradle lintPreview**.
- Manifest/resource/library compatibility passed: 230 resources, 223 non-app values, 80 resource payloads, 26 library metadata files, 10 runtime Java resources, 101 library R classes, 1,132 fields and 40 styleable arrays.
- Five pure suites passed: CoreTest (29 cases plus word-count checks), LiveInsertionTest, EditorInsertionTest, BubblePlacementTest and HistoryDaysTest.
- A packaging-side rebuild from sanitized source produced the identical final APK hash using the separately supplied original signing identity.

The final production correction after the first candidate changes only BackupActivity action-button height from fixed 44dp to wrap-content. Daybook's existing 44dp minimum remains. Enlarged and wrapped action labels can grow inside the settings scroll area. All other production-source hashes are unchanged by this final correction.

## Native checks passed
Disposable Android 15/API 35 AOSP x86_64 emulator, software CPU emulation, 360×720 display at 160dpi. Tests use synthetic editors, speech, preferences, controlled workers and mock network responses.

- **Settings and History:** 344 assertions and 52 physical taps. Every direct Settings row opens on its first physical tap, return focus is restored, saved choices survive recreation, and History ordering/expansion/Copy/Delete/Keep/Clear work.
- **Permissions:** disabled-keyboard, granted-microphone and denied-microphone/enabled-keyboard branches passed. First taps route correctly; real disclosure Cancel/return and the actual Android keyboard picker/Back are exercised. External permission/Settings Activity intents are intercepted and canceled. No Android permission grant or external Settings-page completion is claimed.
- **Floating controls:** native tap/hold/drag, recording/Finish, live insertion, anchor stability, keyboard toggling, real typing-keyboard Delete/Return, composer clearance, preference persistence and hiding after leaving input passed.
- **Floating feedback:** animations enabled and disabled passed 41/43 assertions. Light/dark component palettes and optional exact haptic API dispatch are checked. Touch-target position/size stays stable across interrupted animations.
- **Backup feedback:** 110 assertions and 9 physical taps passed. Immediate pinned activity indication, indeterminate progress, duplicate-work rejection, success/error persistence, first-tap full Details and destroyed-owner cleanup are checked with controlled synthetic work. Actual missing-Play-services Connect/Restore error guards passed.
- **Backup bounds:** all four configurations passed: full display at font scale 1.0 and 2.0, reduced 360×420 display at 1.0, and reduced display at 2.0 with animations disabled. Each covers portrait → landscape → portrait, scroll endpoints, pinned footer, reachable Details, rotation during work and return after Back. Action-text measurements report no clipped/ellipsized action labels. Compact status titles may intentionally ellipsize; Details remains reachable.
- **Backup safety:** 165 offline Drive transport/lifecycle checks, 82 rejected-generation discovery checks, 41 encrypted archive/schema checks, 29 provider-credential portability/isolation checks, 17 deleted-history/draft exclusion checks, 8 draft-recreation checks and 6 disconnected-backup UI checks passed.
- **Private input and recovery:** password/PIN native commits, raw case/prefix/suffix preservation, leading-zero digit normalization, mismatch refusal, changed-selection protection, sensitive-clipboard fallback and restored keyboard preference passed. Ordinary/private pending results recover exactly once during service teardown; manual edits and changed targets are preserved. Native composing-text revision passed.
- **Request privacy and recorder lifecycle:** API private-context exclusions passed with mock HTTPS. Recorder dismissal, screen lock, timeout/empty-result recovery, private detached recovery and startup/Finish/cancellation lifecycle passed. Diagnostics record exactly three transcription attempts/uploads, including one private transcription, and zero chat/cleanup/recap requests; no diagnostic events were dropped.
- **Crash/ANR review:** zero app or fixture crash/ANR matches in 12 captured candidate log intervals. This does not imply a rolling Android log buffer retained every line of the multi-hour session.

## Earlier attempts and fixture corrections
Earlier failed attempts are preserved and explicitly mapped to their successful successors in the acceptance summary. Corrections include screen/scroll-coordinate conversion, waiting for actual scroll completion, independent Restore feedback, exact uppercase heading expectations, public enabled-IME inspection, real-window animator initialization, isolated recap traffic and current minimal-bubble expectations.

The final Auth fixture also handles IME insets in an outer container, measures a genuinely usable scroll viewport, verifies actual accessibility-visible focus, and prevents its synthetic container from stealing focus on resize. Its hide-keyboard and privacy assertions were not weakened. No app change was needed for those fixture failures.

Cold-start System UI/Quickstep ANRs and an emulator host CPU-thread exit occurred outside candidate test intervals. Their diagnostics were preserved; tests were blocked until the emulator recovered and the installed APK hash was reverified. A repeated picker attempt hit an Android accessibility-window timeout; the diagnostic rerun passed with unchanged first-tap conditions.

**Retired, not passed or silently skipped:** one mismatched-credential floating-Delete case and three floating-Delete composition cases. The app intentionally has no floating Delete/Enter controls. Current minimal-control absence and ordinary keyboard Delete/Return are tested separately.

## Not established
- Live Google authorization, Drive upload/restore, real account persistence or second-device restoration.
- Physical haptic motor behavior/strength.
- Actual split-screen/freeform window-manager behavior. Reduced-display layout checks are not represented as real split-screen testing.
- Full Android-device/version coverage or a separately exercised dark-mode BackupActivity layout. Floating components were rendered in both light and dark themes.

## Receipts
- Final build/lint/resource/source receipts: `evidence/1.9.10-build/large-font-fix/`.
- Current hashes and build receipt: `evidence/1.9.10-build/native-inputs.json`.
- Authoritative native outcomes, limits and earlier-attempt mapping: `evidence/1.9.10-native/final-candidate/acceptance-summary.json`.
- Installed identity, instrumentation logs, screenshots and crash scan: `evidence/1.9.10-native/final-candidate/`.
- Test-harness corrections and compile/signature receipts: `evidence/1.9.10-fixture-repairs/`.
