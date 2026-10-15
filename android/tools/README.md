# VoiceFlow fixed-graph Android CLI build

This path freshly compiles the Java app and public Google Auth dependency graph
with supported Android SDK tools. It does **not** run Gradle or Android lint.
The restored 1.9.7 project remains the baseline. No lost 1.9.8 binary, baseline
DEX, or APK resource blob is used as a new build input.

Requirements: OpenJDK 17, Python 3, curl, Android SDK platform 35 and build-tools
35.0.0. Set `JAVA_HOME` and `ANDROID_SDK_ROOT`; put `$JAVA_HOME/bin` on PATH.
Use official Google Maven and Maven Central. No Gradle/GitHub download is needed
by this path. Normal Gradle build files remain available for a future verified
Gradle environment.

From the project root:

```sh
python3 tools/resolve_cli_dependencies.py \
  --cache /path/to/maven-cache \
  --lock tools/android-cli-dependencies.lock.json
python3 tools/build_android_cli.py --cache /path/to/maven-cache
```

The committed lock records the exact 34 selected public AAR/JAR versions, official
URLs and SHA-256 digests. The build verifies each digest before consumption. The
resolver applies fixed-version highest-version mediation and the official JVM
variant of coroutines core; it is not a general substitute for Gradle variant
resolution. The selected library versions and actual class inventory were checked
against the preserved baseline APK.

The builder strictly composes this graph's manifests and rejects conflicts or
unsupported merger directives. AAPT2 compiles every AAR resource directory, links
against Android 35, and resolves the original package R symbols to fresh linked
IDs. Whole merged styleable arrays and their dynamic indexes are retained, as in
the baseline AGP output. Obsolete transitive R names absent after version mediation
are omitted; their absence was independently checked in the baseline DEX. The
compile-only SDK `core-lambda-stubs.jar` precedes `android.jar` on javac's boot
classpath; it is never packaged or given to D8 as a program input.

All production Java is compiled fresh with source/target 8. D8 desugars app and
library bytecode for minimum API 26. Runtime Kotlin builtins, Google version
properties and Java service registrations are packaged. Build-time ProGuard rules,
signatures and licensing metadata are excluded from runtime ZIP merging. Inherited
project licensing remains in `LICENSES/`. The original public Preview development
keystore signs the APK; this is not a production or Play certificate. No API key,
Google token, OAuth Web client secret or private user data is a build input.

Outputs:

- `app/build/outputs/apk/preview/VoiceFlow-<version>.apk`
- `app/build/cli-preview/build-result.json`, signature, badging, resource table,
  merged manifest and before/after production-source hash checks
- `app/build/intermediates/javac/preview/compilePreviewJavaWithJavac/classes`
  (fresh CLI classes placed there solely for the existing fixture builders)

Optional independent verification, with Pillow available for decoded PNG checks:

```sh
python3 tools/verify_cli_baseline.py \
  --baseline /path/to/preserved-1.9.7.apk \
  --candidate app/build/outputs/apk/preview/VoiceFlow-1.9.9.apk \
  --report /path/to/resource-check.json
python3 tools/verify_library_r.py \
  --baseline /path/to/preserved-1.9.7.apk \
  --candidate app/build/outputs/apk/preview/VoiceFlow-1.9.9.apk \
  --report /path/to/library-r-check.json
```

For 1.9.9, the fresh signed APK passed manifest comparison except requested version
metadata; all 230 resource names, 223 non-app values, 80 decoded resource payloads,
26 library property/version files and 10 runtime Java resources were checked. All
101 library R classes, 1,132 scalar/array fields and 40 linked styleable arrays
matched the baseline, with no missing non-app non-synthetic class. See
`evidence/1.9.9-build/` for exact receipts and preserved intermediate build failures.
These are build/source checks, not native runtime or real Google Drive proof.

The CLI APK deliberately does not reuse the baseline APK's address-specific
AGP-generated `assets/dexopt/baseline.prof`/`.profm` profiles. Functional resources,
manifest declarations and dependency classes are preserved; startup optimization
profiles would need fresh generation in a full Android build pipeline. This APK
also identifies its CLI build honestly instead of embedding a false AGP receipt.
