#!/usr/bin/env bash
# Build either/both products without inventing a replacement update-signing key.
set -euo pipefail
cd "$(dirname "$0")/.."
task_mode=signed
task_product=both
while [[ $# -gt 0 ]]; do
  case "$1" in
    --unsigned) task_mode=unsigned; shift ;;
    --qa) task_mode=qa; shift ;;
    --product) [[ $# -ge 2 ]] || { echo '--product needs vonotation, notes or both' >&2; exit 2; }; task_product="$2"; shift 2 ;;
    *) echo 'Usage: build-split.sh [--unsigned|--qa] [--product vonotation|notes|both]' >&2; exit 2 ;;
  esac
done
case "$task_product" in both) task_products=(vonotation notes) ;; vonotation|notes) task_products=("$task_product") ;; *) echo 'Unknown product' >&2; exit 2 ;; esac
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_SDK_ROOT or ANDROID_HOME}}"
task_temp="$(mktemp -d "${TMPDIR:-/tmp}/vonotation-split-build.XXXXXX")"
trap 'rm -rf "$task_temp"' EXIT
if [[ "$task_mode" == qa ]]; then
  export VOICEFLOW_KEYSTORE="$task_temp/vonotation-qa.keystore" VONO_NOTES_KEYSTORE="$task_temp/notes-qa.keystore"
  export VONO_NOTES_KEYSTORE_PASSWORD=android VONO_NOTES_KEY_PASSWORD=android
  for task_key in "$VOICEFLOW_KEYSTORE" "$VONO_NOTES_KEYSTORE"; do
    keytool -genkeypair -keystore "$task_key" -storepass android -keypass android -alias voiceflow -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Disposable split-build QA only' >/dev/null 2>&1
  done
elif [[ "$task_mode" == unsigned ]]; then
  export VONO_UNSIGNED_BUILDS=true
else
  unset VONO_UNSIGNED_BUILDS
  for task_item in "${task_products[@]}"; do
    if [[ "$task_item" == notes ]]; then task_key="${VONO_NOTES_KEYSTORE:-$PWD/notes-signing/vononotes.keystore}"; else task_key="${VOICEFLOW_KEYSTORE:-$PWD/preview-signing/voiceflow.keystore}"; fi
    [[ -f "$task_key" ]] || { printf '%s\n' "Missing $task_item signing keystore. Restore the correct key, or explicitly use --unsigned. No replacement key is generated." >&2; exit 1; }
  done
fi
task_tasks=()
for task_item in "${task_products[@]}"; do
  if [[ "$task_item" == notes ]]; then task_variant=NotesPreview; else task_variant=VonotationPreview; fi
  task_tasks+=("assemble$task_variant" "lint$task_variant")
done
bash gradlew --no-daemon "${task_tasks[@]}"
bash tests/run-pure-tests.sh
task_output="$PWD/build/split-$task_mode"
mkdir -p "$task_output"
for task_item in "${task_products[@]}"; do
  task_metadata="$PWD/app/build/outputs/apk/$task_item/preview/output-metadata.json"
  task_apk="$(python3 - "$task_metadata" <<'PY'
from pathlib import Path
import json, sys
path=Path(sys.argv[1]); data=json.loads(path.read_text())
if len(data['elements']) != 1: raise SystemExit('Expected one universal split-product APK')
print(path.parent/data['elements'][0]['outputFile'])
PY
)"
  if [[ "$task_mode" == unsigned ]]; then task_suffix=UNSIGNED; elif [[ "$task_mode" == qa ]]; then task_suffix=DISPOSABLE-QA; else task_suffix=SIGNED; fi
  task_target="$task_output/$task_item-$task_suffix.apk"
  cp "$task_apk" "$task_target"
  "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt2" dump badging "$task_target" > "$task_target.badging.txt"
  if [[ "$task_mode" != unsigned ]]; then
    "$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --print-certs "$task_target" > "$task_target.signing.txt"
    if [[ "$task_mode" == signed && "$task_item" == vonotation ]]; then
      python3 - "$task_target.signing.txt" <<'PY'
from pathlib import Path
import re,sys
text=Path(sys.argv[1]).read_text()
found=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)',text)
if not found or found.group(1)!='5dca2219fbe89fb885b24c904fd4c57d3b58930922d21d455fc10d7a35f7568a':
    raise SystemExit('Refusing to label Voitation as an update: original signing certificate does not match')
PY
    fi
  fi
  python3 - "$task_target" "$task_item" "$task_mode" <<'PY'
from pathlib import Path
import hashlib,json,re,sys
path=Path(sys.argv[1]);product,mode=sys.argv[2:]
text=path.with_suffix(path.suffix+'.badging.txt').read_text()
match=re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",text)
if not match: raise SystemExit('Missing APK install metadata')
expected='app.vonotation.notes' if product=='notes' else 'app.voiceflow'
if match.group(1)!=expected: raise SystemExit('Unexpected product package')
data=path.read_bytes()
receipt={'name':path.name,'package':expected,'versionCode':int(match.group(2)),'versionName':match.group(3),'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'signing':mode,'runtime_testing':'not performed by this build script','qualification':'Not installable; requires original product signer' if mode=='unsigned' else 'Disposable QA certificate; never use as update or uninstall existing app' if mode=='qa' else 'Signed build; inspect signing receipt before distribution'}
path.with_suffix('.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
PY
done
if [[ "$task_mode" == unsigned ]]; then
  printf '%s\n' 'UNSIGNED: these files cannot be installed until signed with the appropriate product key. Voitation updates require its existing signer. Never uninstall the old app to work around a signature mismatch.' > "$task_output/README.txt"
elif [[ "$task_mode" == qa ]]; then
  printf '%s\n' 'DISPOSABLE QA ONLY: not update-compatible and not a production release. Use only fresh disposable emulator installations. Do not uninstall or replace an app with user data.' > "$task_output/README.txt"
else
  printf '%s\n' 'Both products keep separate package identities and data. A signed build alone is not physical-device/provider verification.' > "$task_output/README.txt"
fi
printf '%s\n' "$task_output"
