# VoiceFlow rebuild checkpoint — 1.9.9 Preview

Restarted October 4, 2026, from the saved final 1.9.7 source package. The existing app, package app.voiceflow, preview signing certificate, AuthorizationClient-only architecture and user-reported Google configuration are retained. No Google project/client, billing or publishing change is authorized or performed.

## Preserved baseline

Source SHA-256: cf81603f1fbd48253d3d80b342f304fcb7cef9efd180638340c2db9ad8609b6b.
APK SHA-256: 6c2ded08d7e43f058bbde4a5d549c2729ba80eefb59b96326ccb58757314d476.
Source ZIP CRC, safe archive paths and bundled-APK equality passed during recovery. Historical 1.9.7 evidence inside the source remains tied to that version. The exact selected Daybook reference SHA-256 is e5cab1f514d030dc84e621ad525633388e05651df0578b4dc209fbba8c8f7063.

## Lost checkpoint and fresh evidence

The corrected 1.9.8 APK, source modifications and raw native logs were not saved before the previous environment went offline and scratch maintenance removed them. Earlier chat reports described completed tests; those reports are historical context, not preserved 1.9.9 execution evidence. The saved provisional 1.9.8 screenshots/reference/review still describe the initial failed first-tap candidate. No lost APK is presented as downloadable.

The rebuilt checkpoint uses version 1.9.9/code 19 to distinguish it from both lost 1.9.8 candidates. Native Daybook Home/Settings, optional haptics, backup presentation and the Settings first-tap regression have been reapplied to the recovered existing project. New tests and captures must come from the newly built binary.

## Current build state

Fresh Android CLI compilation, resource linking, D8 conversion, alignment and APK signing passed using official SDK/JDK/Maven artifacts. This is not a Gradle build or lint pass: automatic approval review rejected the official Gradle distribution's GitHub redirect, and that route was abandoned. The build scripts and dependency hash lock are in tools/.

Version 1.9.9/code 19 APK SHA-256: 2bdb49cefdd5235de2670eab04bfe0b73fee51ca73a43df42bbf69e582af0270. Independent apksigner verification passed APK v2 and the inherited custom Preview certificate. Manifest/resource/library packaging comparison against the baseline passed. prebuilt/VoiceFlow.apk now contains this new APK.

The AOSP API 35 guest is booted and the inherited emulator was not restarted. Its initial timeout came from ADB startup configuration, not proven guest boot failure. Explicit local-listener recovery succeeded after an unsupported hostname syntax attempt, both preserved. The app installed, pulled APK identity matched, and cold launch/resumed MainActivity passed. The full native run completed: credential/archive/history/rejected-generation/draft/cloud checks passed, including 165 offline cloud checks. Initial Daybook failed a contradictory fixture preference before any tap. Floating controls were blocked by a visible System UI ANR dialog whose DropBox event predates app installation. The original health probe missed global focus, so zero app-log matches is not a clean full-runtime pass. Corrected targeted runs and fresh Home/Settings captures remain pending. All original failures and read-only environment diagnostics are retained. Standalone CLI lint passed with zero errors and 26 warnings, distinct from Gradle lintPreview.

## Safeguards and unresolved limits

Backup is opt-in; history/dictionary/settings remain encrypted; credentials/audio/private password/PIN dictations remain excluded; restore still requires overwrite confirmation. Provider/model/endpoint settings are portable; provider reconnection remains required on a new phone. No access/refresh tokens, API keys or Web client secret are embedded.

The actual historical 1.9.6 input-dispatch ANR remains unresolved. Real Google consent/Drive upload/restore, another-phone restore and Testing grant reauthorization have not been independently exercised. Neither synthetic HTTPS tests nor missing-services UI tests establish live Google operation. The selected Daybook HTML is a preview separate from the native app.
