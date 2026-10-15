#!/usr/bin/env python3
"""One targeted Side-control interval after root's exact SystemUI recovery.

Reuses frozen native fixture methods and the separately pinned read-only global
focus/hierarchy guard. Existing app/Side fixture are hash-verified, not reinstalled.
No Daybook, Cloud, archive or Google authorization test is repeated.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

TOOLCHAIN = Path(__file__).resolve().parent
PROJECT = TOOLCHAIN.parent / "VoiceFlow"
EVIDENCE = PROJECT / "evidence/1.9.9-side-native-corrected"
GUARDED_RUNNER = TOOLCHAIN / "run-daybook-corrected-199.py"
GUARDED_RUNNER_SHA256 = "c1cf64c5a61596e037663eeae8f281c210d2f22ec7946ac57822ce73783512a5"
DEFAULT_MARKER = TOOLCHAIN / "native199-side-corrected-authorized.json"
DAYBOOK_SUMMARY = PROJECT / "evidence/1.9.9-daybook-native-corrected/summary.json"
SIDE_APK = PROJECT / "build/tests/side/VoiceFlow-side-fixture.apk"
SIDE_SHA256 = "f6f619bc2f27d396b9b29b108ec7ff9a843cecd44201f0c46cd7cf639242fff4"


def load_guarded_runner():
    if hashlib.sha256(GUARDED_RUNNER.read_bytes()).hexdigest() != GUARDED_RUNNER_SHA256:
        raise RuntimeError("BLOCKED: shared read-only modal guard runner changed from its reviewed frozen bytes")
    specification = importlib.util.spec_from_file_location("voiceflow_guarded_native199", GUARDED_RUNNER)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    module.EVIDENCE = EVIDENCE
    return module


def build_side_runner(guarded, base, marker):
    parent = guarded.build_corrected_runner(base, marker)

    class SideRunner(parent):
        fixture_path = SIDE_APK
        fixture_sha256 = SIDE_SHA256
        fixture_package = "app.voiceflow.fixture"
        fixture_label = "existing-side-fixture"
        install_fixture = False

        def __init__(self, budget):
            super().__init__(budget)
            self.summary.update(run_kind="Targeted original Side fixture after explicit SystemUI recovery and corrected-Daybook interval",
                                original_failed_side_receipt=str(PROJECT / "evidence/1.9.9-native/side-controls.json"),
                                original_side_fixture_sha256=SIDE_SHA256,
                                guarded_runner_sha256=GUARDED_RUNNER_SHA256,
                                previous_daybook_summary_path=str(DAYBOOK_SUMMARY),
                                production_reinstalled=False, side_fixture_reinstalled=False,
                                cloud_or_daybook_repeated=False,
                                runtime_scope="Fresh targeted native interval on the existing isolated AOSP API35 TCG emulator. Synthetic speech/editor fixture only; no live transcript provider, Google account consent or Drive operation.")
            self.summary.pop("original_failed_daybook_receipt", None)
            self.summary.pop("corrected_fixture_sha256", None)

        def gates_and_install(self):
            if not DAYBOOK_SUMMARY.is_file():
                raise RuntimeError("BLOCKED: corrected-Daybook interval has not completed; no concurrent native work")
            previous = json.loads(DAYBOOK_SUMMARY.read_text())
            if not previous.get("finished_utc") or previous.get("active_stage") is not None:
                raise RuntimeError("BLOCKED: corrected-Daybook interval is active; no concurrent native work")
            if previous.get("latest_health", {}).get("unsafe_to_continue"):
                raise RuntimeError("BLOCKED: corrected-Daybook ended with unsafe health; root must investigate before a Side interval")
            self.summary["previous_daybook_status"] = previous.get("status")
            self.summary["previous_daybook_summary_sha256"] = base.digest(DAYBOOK_SUMMARY)
            super().gates_and_install()

        def run_tests(self):
            self.health("before-side-setup")
            # The frozen setup preserves other accessibility services and does
            # not disable Phone/Bluetooth or hide system errors. It establishes
            # the exact synthetic recognizer, permissions and preference state.
            self.side_setup()
            self.health("before-side-first-input")
            stage = self.stage("side-controls-corrected", "app.voiceflow.fixture/.SidePreview", timeout=600,
                               dependency="side-setup", reset=False, target="app.voiceflow.fixture")
            if not stage.get("command"):
                self.failure_capture("side-not-started")
                self.summary["side_native_captures"] = "BLOCKED: no SidePreview execution; existing files are not presented as fresh captures"
                self.checkpoint()
                return
            for filename in ("side-preview.png", "side-recording.png", "side-finished.png", "side-keyboard-visible.png"):
                self.pull_capture(filename.removesuffix(".png"), "files/" + filename,
                                  filename.removesuffix(".png") + "-199-corrected.png",
                                  package="app.voiceflow.fixture", run_as=True, required=True)

    return SideRunner


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--marker", type=Path, default=DEFAULT_MARKER)
    parser.add_argument("--wall-budget-seconds", type=int, default=1200)
    options = parser.parse_args()
    if not 120 <= options.wall_budget_seconds <= 3450:
        parser.error("Use a wall budget between 120 and 3450 seconds in the existing queue")
    if (EVIDENCE / "summary.json").exists():
        raise SystemExit("Existing targeted Side receipt found; refusing to overwrite or automatically retry it")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    guarded = load_guarded_runner()
    base = guarded.load_frozen_runner()
    runner = build_side_runner(guarded, base, options.marker.resolve())(options.wall_budget_seconds)
    runner.checkpoint()
    try:
        runner.gates_and_install()
        runner.run_tests()
    except BaseException as failure:
        runner.summary["runner_failure"] = {"class": type(failure).__name__, "message": str(failure), "time": base.now(),
                                            "blocking": str(failure).startswith("BLOCKED:")}
        runner.checkpoint()
    finally:
        try:
            runner.finalise()
        except BaseException as failure:
            runner.summary["finalisation_failure"] = {"class": type(failure).__name__, "message": str(failure), "time": base.now()}
            runner.summary["status"] = "FAILED"; runner.checkpoint()
    return 0 if runner.summary["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
