#!/usr/bin/env bash
# Native Apple SDK compilation and actual XCTest/XCUITest execution. No signing identity is used.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v xcodebuild >/dev/null || { printf '%s\n' 'Xcode is required; this script cannot validate iOS on Linux.' >&2; exit 2; }
task_output="${VONOTATION_CI_OUTPUT:-$PWD/build/ci}"
mkdir -p "$task_output/logs" "$task_output/screenshots"
python3 scripts/generate-project.py --check
xcodebuild -version > "$task_output/xcode-version.txt"
xcrun simctl list runtimes --json > "$task_output/runtimes.json"
python3 scripts/select-simulators.py > "$task_output/simulators.json"
task_derived="$task_output/DerivedData"
task_common=(-project VonoTation.xcodeproj -scheme VonoTation -derivedDataPath "$task_derived" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO)
xcodebuild "${task_common[@]}" -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build-for-testing | tee "$task_output/logs/build-for-testing.log"
for task_family in iPhone iPad; do
  task_udid="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]]["udid"])' "$task_output/simulators.json" "$task_family")"
  task_device_state="$(xcrun simctl list devices --json | python3 -c 'import json,sys; print(next(d["state"] for a in json.load(sys.stdin)["devices"].values() for d in a if d["udid"] == sys.argv[1]))' "$task_udid")"
  if [[ "$task_device_state" != 'Booted' ]]; then xcrun simctl boot "$task_udid"; fi
  xcrun simctl bootstatus "$task_udid" -b
  xcrun simctl install "$task_udid" "$task_derived/Build/Products/Debug-iphonesimulator/VonoTation.app"
  xcrun simctl privacy "$task_udid" grant microphone app.vonotation.ios
  xcodebuild "${task_common[@]}" -configuration Debug -destination "platform=iOS Simulator,id=$task_udid" -parallel-testing-enabled NO -resultBundlePath "$task_output/$task_family.xcresult" test-without-building | tee "$task_output/logs/test-$task_family.log"
  xcrun xcresulttool get test-results summary --path "$task_output/$task_family.xcresult" > "$task_output/$task_family-test-summary.json"
  xcrun xcresulttool get test-results tests --path "$task_output/$task_family.xcresult" > "$task_output/$task_family-test-tree.json"
  for task_screen in home dictations thoughts clipboard settings; do
    # terminate can report 'not running'; launching and screenshot failures remain fatal.
    xcrun simctl terminate "$task_udid" app.vonotation.ios 2>/dev/null || true
    xcrun simctl launch "$task_udid" app.vonotation.ios --ui-testing --ui-fixtures --ui-screen "$task_screen"
    # Native XCTest already asserts readiness; allow SwiftUI's first rendering pass to finish.
    sleep 2
    xcrun simctl io "$task_udid" screenshot "$task_output/screenshots/$task_family-$task_screen.png"
  done
  xcrun simctl terminate "$task_udid" app.vonotation.ios
  xcrun simctl shutdown "$task_udid"
done
xcodebuild "${task_common[@]}" -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' -resultBundlePath "$task_output/device-sdk.xcresult" build | tee "$task_output/logs/device-sdk-build.log"
xcrun xcresulttool get build-results --path "$task_output/device-sdk.xcresult" > "$task_output/device-sdk-build.json"
xcodebuild "${task_common[@]}" -configuration Release -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -archivePath "$task_output/VonoTation-Simulator.xcarchive" archive | tee "$task_output/logs/simulator-archive.log"
ditto -c -k --sequesterRsrc --keepParent "$task_output/VonoTation-Simulator.xcarchive/Products/Applications/VonoTation.app" "$task_output/VonoTation-iOS-Simulator.zip"
python3 scripts/verification-manifest.py "$task_output"
printf '%s\n' 'Native iPhone and iPad tests, unsigned device SDK build and simulator archive completed.'
