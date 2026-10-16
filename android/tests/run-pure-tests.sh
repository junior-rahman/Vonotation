#!/usr/bin/env bash
# Compile the Android-free production classes with the pure-Java suites and run them on the JVM.
# Synthetic text/logic tests only: no microphone, no provider calls, no Android framework.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/app/build/pure-tests}"
rm -rf "$out"; mkdir -p "$out"
src="$root/app/src/main/java/app/voiceflow"
prod=(Cleaner TextInsertion TranscriptGuard LanguagePolicy EditorInsertion LiveInsertion BubblePlacement HistoryDays DictationProblem RecordingMode KeyMask OwnWindow GoogleAuthError AccessibilityList)
for extra in PunctuationCommands ClipboardText QuickThoughtPolicy ThoughtTimeline UpdatePolicy CursorDeletion LineConnectionState ThoughtDaySource ThoughtLengthText; do [[ -f "$src/$extra.java" ]] && prod+=("$extra"); done
files=(); for p in "${prod[@]}"; do files+=("$src/$p.java"); done
suites=(); for t in "$root"/tests/*Test.java; do suites+=("$t"); done
compiler=(javac)
if ! command -v javac >/dev/null 2>&1; then
    compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
if ! "${compiler[@]}" -encoding UTF-8 --release 8 -nowarn -d "$out" "${files[@]}" "${suites[@]}" >"$out/compile.log" 2>&1; then
    cat "$out/compile.log"
    exit 1
fi
fail=0
for t in "${suites[@]}"; do n=$(basename "$t" .java); printf '%s: ' "$n"; if java -ea -cp "$out" app.voiceflow.$n; then :; else echo "FAILED $n"; fail=1; fi; done
exit $fail
