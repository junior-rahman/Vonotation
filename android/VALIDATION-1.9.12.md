# VoiceFlow 1.9.12 preview validation

Resumed from the saved 1.9.10 source, with that exact original source ZIP preserved in baseline/. This does not incorporate the discarded 1.9.11 source.

Version: 1.9.12, code 22. Package: app.voiceflow. Minimum API 26, target API 35.

APK SHA-256: cff0895a828605c28a2f306838506937140a96596e123b3f55f2cfdc9e505185
Signer certificate SHA-256: 56d07d6216174d9ecaf6314131e3f4853592ab6c4f031ef368eac8b0deaef0ae

Changes: optional cloud auto-detection, Nepali selection, and Bengali/Hindi/Nepali output in English letters using the existing AI connection. Output conversion defaults Off and works independently of cleanup, sharing one request when both are selected. Existing settings remain. Offline mode and password/PIN dictation bypass conversion. Conversion errors keep original captured text with a warning. The output preference is included in backups; old backups default it Off. Older apps cannot restore backups containing the new preference.

Fresh Android SDK build passed: resource linking, Java compilation, D8, APK packaging, alignment and v2 signing. All 34 public app dependencies were checksum-verified against the retained lock. Signing uses the recovered original preview key. The source was unchanged during compilation. Standalone official Android lint passed with 0 errors and 27 warnings; Gradle build/lint were not run. Receipts are under evidence/1.9.12-build/.

The six pure-Java suites passed during the initial resume, including the new 15-check language policy suite. The logic tested by those suites remains unchanged in this APK. Native fixtures were freshly compiled against the final production classes with matching signatures.

Native outcomes on the exact final APK: four suites passed: API/language requests (mock HTTPS), backup credential policy, 41 encrypted archive/schema checks, and 17 history/draft deletion checks. The emulator installed original 1.9.10 and then upgraded in place to 1.9.12; installed package metadata reports version code 22.

UI acceptance is incomplete. The first UI run failed on a single History tap before reaching the new controls. A fixture-only focus-readiness guard was added without changing app code or repeating taps. The next run failed before touching a control because screenshot storage returned ENOTCONN. Captured logs show Android System UI, Launcher and Media Provider ANRs. Neither UI attempt is counted as a pass. New language controls still need a successful UI run and phone verification. The original failures and the four passing reports are retained under evidence/1.9.12-native/.

No app/fixture crash or ANR matches were found in the six captured test log intervals. This scan is limited to those windows and does not establish general stability.

Limits: fixtures use synthetic text and mocked HTTPS. They do not establish real speech accuracy, mixed-language detection quality, provider transliteration quality, live Google authorization/upload/restore, or Galaxy Z Fold behavior. A separate Grok connection is not implemented; the configured transcription and cleanup providers remain in use. This is a private signed preview, not a Play release.
