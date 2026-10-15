# VoiceFlow rebuild toolchain, October 4 2026

Workspace-local official Android SDK and OpenJDK tools were restored after scratch maintenance.
Source `env.sh` before CLI build or fixture compilation. Gradle 8.9 is still blocked by automatic approval review; do not claim `assemblePreview` or `lintPreview` ran. The parent selected an Android CLI build with aapt2 + javac + d8 + zipalign + apksigner using these restored tools.

- OpenJDK reference implementation 17+35: official download.java.net archive; published SHA-256 matched.
- SDK command-line tools 11076708: official dl.google.com; local archive SHA-256 recorded.
- SDK Platform 35 revision 2; Build Tools 35.0.0; Platform Tools 37.0.1; Emulator 37.2.12; AOSP API35 x86_64 revision 2: installed through official Google package index.
- AVD VoiceFlow199: 540x1080, density240, 2 CPU cores, 2048 MiB RAM, TCG because /dev/kvm is absent.
- AOSP default image has no Google Play services. Native missing-GMS controls can be exercised; real Google authorization/upload/restore cannot be claimed.

`evidence/download-approval-rejections.json` preserves rejected Gradle redirected/GitHub requests and the abandoned Azul probe. No alternative mirror/proxy or bypass was used. Oracle JDK endpoint returned a small HTML 'Site Unavailable' response, not JDK bytes; the official OpenJDK RI subsequently worked.

Native emulator and ADB are owned by the long-lived `native-namespace.py` process. Queue task requests in `native199-queue/*.request.json`; each produces matching response and stdout/stderr files. Root must save/pin production APK and fixtures before writing `native199-install-authorized.json`. No test/installation script runs before this marker. No Phone disabling or global error-dialog hiding was performed. Preserve initial boot logs separately from the candidate APK interval. Root writes `native199-release-after-save.json` only after final artifacts/evidence are saved.
