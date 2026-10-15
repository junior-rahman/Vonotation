#!/usr/bin/env python3
"""Separate corrected-Daybook interval after the frozen full 1.9.9 suite.

Uses the original runner's result parser, physical-fixture invocation, capture
verification and health checks. Installs only the corrected Daybook fixture;
production/other fixture APKs are not reinstalled and no Cloud/Side test repeats.
No native command runs until a new root saved-artifact marker pins these bytes.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET

TOOLCHAIN = Path(__file__).resolve().parent
WORKSPACE = TOOLCHAIN.parent
PROJECT = WORKSPACE / "VoiceFlow"
EVIDENCE = PROJECT / "evidence/1.9.9-daybook-native-corrected"
FROZEN_RUNNER = TOOLCHAIN / "run-native-199.py"
FROZEN_RUNNER_SHA256 = "fe26bc1d994c353c3fb4811f3188a308b6ba56425a1f7bb4276cf68119f4acf6"
DEFAULT_MARKER = TOOLCHAIN / "native199-daybook-corrected-authorized.json"
INITIAL_MANIFEST = PROJECT / "evidence/1.9.9-daybook-initial-fixture/fixtures-built.json"
CORRECTION_PROOF = PROJECT / "evidence/1.9.9-daybook-corrected-fixture/build-verification.json"
CORRECTED_APK = PROJECT / "qa/1.9.9-corrected/VoiceFlow-daybook-fixture.apk"
CORRECTED_SHA256 = "3b79e722fef53f03edca3dcdca8e1e6739963221fff7aa42d1ac9f1a3d74dc1c"
PRODUCTION_SHA256 = "2bdb49cefdd5235de2670eab04bfe0b73fee51ca73a43df42bbf69e582af0270"
FULL_SUMMARY = PROJECT / "evidence/1.9.9-native/summary.json"
UI_HELPER = TOOLCHAIN / "native-ui-helper/ReadOnlyUiHierarchy.jar"
UI_HELPER_SHA256 = "4e55c3fd00f5ee3445bd479a9ea52cad9a1d083f4c337c94c74b9affe346ffa4"
UI_HELPER_REMOTE = "/data/local/tmp/voiceflow-native199-readonly-hierarchy.jar"


def inspect_focused_modal(window_text, hierarchy_bytes):
    """Inspect recorded global focus + native hierarchy without touching the UI.

    Absence of a log match is not evidence that a system error dialog is absent.
    A missing/invalid hierarchy or missing current focus remains unverified.
    """
    focus = [line.strip() for line in window_text.splitlines()
             if re.search(r"\bm(?:CurrentFocus|FocusedWindow)\s*=", line)]
    active_focus = [line for line in focus if re.search(r"=\s*Window\{", line)]
    # Framework window titles have varied between Android releases. Match the
    # actual focused window, rather than any background error window or app text.
    error_focus = [line for line in active_focus
                   if re.search(r"Application (?:Error|Not Responding)|App(?:lication)? (?:Error|ANR) Dialog|\bANR\b|isn['’]t responding|is not responding|keeps stopping|has stopped", line, re.I)]
    result = {"global_focus_lines": focus, "focused_error_window_lines": error_focus,
              "hierarchy_verified": False, "modal_detected": bool(error_focus),
              "classification": None, "matching_native_nodes": [],
              "scope": "Global focused window plus recorded native hierarchy. Read-only; no dialog dismissal or physical input."}
    titles, controls = [], []
    try:
        if not hierarchy_bytes or len(hierarchy_bytes) > 8 * 1024 * 1024 or b"<!DOCTYPE" in hierarchy_bytes.upper():
            raise ValueError("Hierarchy missing, oversized or has an unsupported document type")
        root = ET.fromstring(hierarchy_bytes)
        if root.tag != "hierarchy" or not any(True for _ in root.iter("node")):
            raise ValueError("Native hierarchy has no UI nodes")
        result["hierarchy_verified"] = True
        for node in root.iter("node"):
            attrs = node.attrib
            if attrs.get("visible-to-user") == "false":
                continue
            text = " ".join((attrs.get("text", ""), attrs.get("content-desc", ""))).strip()
            package = attrs.get("package", "")
            resource = attrs.get("resource-id", "")
            framework = package in ("android", "com.android.systemui") or resource.startswith("android:id/")
            if not framework:
                continue
            detail = {key: attrs.get(key, "") for key in ("text", "content-desc", "package", "resource-id", "bounds", "focused")}
            if re.search(r"isn['’]t responding|is not responding|not responding|keeps stopping|has stopped|Application (?:Error|Not Responding)", text, re.I):
                titles.append(detail)
            if re.search(r"(?:^|/)(?:aerr_(?:close|wait|restart)|button[12])$", resource) or re.fullmatch(r"(?:Wait|Close app|Close application|Restart app|App info|Open app again)", text, re.I):
                controls.append(detail)
        # Both the framework error title and a framework error action must be
        # visible. Ordinary app content discussing an ANR is not a system modal.
        if titles and controls:
            result["modal_detected"] = True
            result["matching_native_nodes"] = titles + controls
    except (ValueError, ET.ParseError) as failure:
        result["hierarchy_failure"] = str(failure)
    evidence = "\n".join(error_focus + [entry["text"] + " " + entry["content-desc"] for entry in titles])
    if result["modal_detected"]:
        result["modal_kind"] = "ANR" if re.search(r"Not Responding|isn['’]t responding|is not responding|\bANR\b", evidence, re.I) else "ERROR_MODAL"
        if re.search(r"System\s*UI|com\.android\.systemui", evidence, re.I):
            result["classification"] = "ENVIRONMENT_SYSTEM_UI_" + result["modal_kind"]
        elif re.search(r"VoiceFlow|app\.voiceflow", evidence, re.I):
            result["classification"] = "VOICEFLOW_FOCUSED_ERROR_MODAL"
        else:
            result["classification"] = "OTHER_FOCUSED_SYSTEM_ERROR_MODAL"
    result["verified_clear"] = bool(active_focus and result["hierarchy_verified"] and not result["modal_detected"])
    result["status"] = "BLOCKED" if result["modal_detected"] else "PASS" if result["verified_clear"] else "UNVERIFIED"
    return result


def load_frozen_runner():
    import hashlib
    if hashlib.sha256(FROZEN_RUNNER.read_bytes()).hexdigest() != FROZEN_RUNNER_SHA256:
        raise RuntimeError("BLOCKED: full-suite runner changed from its frozen reviewed bytes")
    specification = importlib.util.spec_from_file_location("voiceflow_frozen_native199", FROZEN_RUNNER)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    # Only this Python process's imported module uses the new evidence directory.
    # The original active/finished runner and its evidence are never modified.
    module.EVIDENCE = EVIDENCE
    return module


def build_corrected_runner(base, marker):
    class CorrectedRunner(base.Runner):
        fixture_path = CORRECTED_APK
        fixture_sha256 = CORRECTED_SHA256
        fixture_package = "app.voiceflow.daybook.fixture"
        fixture_label = "corrected-daybook-fixture"
        install_fixture = True

        def __init__(self, budget):
            super().__init__(budget)
            self.ui_helper_pushed = False
            self.ui_helper_approved = False
            self.summary.update(run_kind="Corrected Daybook fixture only after original full suite",
                                full_suite_summary_path=str(FULL_SUMMARY),
                                original_failed_daybook_receipt=str(PROJECT / "evidence/1.9.9-native/daybook-ui.json"),
                                corrected_fixture_sha256=CORRECTED_SHA256,
                                original_runner_sha256=FROZEN_RUNNER_SHA256,
                                production_reinstalled=False, other_fixtures_reinstalled=False,
                                corrected_fixture_installed=False, cloud_or_side_repeated=False)

        def focused_modal_probe(self, name):
            """Read global API35 focus and native hierarchy; never press Wait/Close."""
            if not self.ui_helper_approved:
                raise RuntimeError("BLOCKED: saved-and-pinned hierarchy helper has not passed the artifact gate")
            window_receipt, window, _ = self.command(name + "-modal-global-window", ["shell", "dumpsys", "window"], 60)
            if not self.ui_helper_pushed:
                self.required_command("read-only-ui-helper-push", ["push", str(UI_HELPER), UI_HELPER_REMOTE], 60)
                _, remote_hash, _ = self.required_command("read-only-ui-helper-device-sha256", ["shell", "sha256sum", UI_HELPER_REMOTE], 30)
                if remote_hash.decode(errors="replace").split()[0] != UI_HELPER_SHA256:
                    raise RuntimeError("BLOCKED: device hierarchy-helper bytes do not match the saved pin")
                self.ui_helper_pushed = True
            hierarchy_receipt, hierarchy, _ = self.command(name + "-modal-hierarchy-read", ["shell", "env", "CLASSPATH=" + UI_HELPER_REMOTE,
                                                                                          "app_process", "/system/bin", "voiceflow.testtools.ReadOnlyUiHierarchy"], 60)
            hierarchy_path = EVIDENCE / (name + "-native-ui-hierarchy.xml")
            hierarchy_path.write_bytes(hierarchy)
            probe = inspect_focused_modal(window.decode(errors="replace"), hierarchy)
            transport_ok = all(receipt["status"] == "PASS" for receipt in (window_receipt, hierarchy_receipt))
            probe.update(checked_utc=base.now(), global_window_receipt=window_receipt,
                         hierarchy_read_receipt=hierarchy_receipt,
                         hierarchy_path=str(hierarchy_path), transport_verified=transport_ok,
                         no_ui_input=True, no_dialog_dismissal=True,
                         helper_sha256=UI_HELPER_SHA256, helper_is_not_an_apk_or_app_install=True,
                         dont_suppress_accessibility_services=True)
            if not transport_ok:
                probe["verified_clear"] = False
                probe["status"] = "BLOCKED" if probe["modal_detected"] else "UNVERIFIED"
            base.write_json(EVIDENCE / (name + "-focused-modal-probe.json"), probe)
            self.summary["latest_focused_modal_probe"] = probe
            return probe

        def health(self, name):
            # Preserve the frozen runner's log/lastanr result separately. Its
            # windows-only focus check is insufficient on API35 and is not reused
            # as proof of a clear environment.
            health = super().health(name)
            base.write_json(EVIDENCE / (name + "-legacy-log-health.json"), health)
            probe = self.focused_modal_probe(name)
            health["global_focus_and_native_hierarchy"] = probe
            health["app_or_fixture_runtime_findings_detected"] = bool(health["app_or_fixture_matched_line_count"] or health["new_lastanr_names_app_or_fixture"])
            health["environment_status"] = "BLOCKED" if not probe["verified_clear"] else "PASS"
            health["environment_modal_classification"] = probe["classification"]
            health["unsafe_to_continue"] = bool(health["unsafe_to_continue"] or not probe["verified_clear"])
            if health["unsafe_to_continue"]:
                health["status"] = "FAILED"
            base.write_json(EVIDENCE / (name + "-health.json"), health)
            self.summary["latest_health"] = health
            self.unhealthy |= health["unsafe_to_continue"]
            return health

        def gates_and_install(self):
            if not marker.is_file():
                raise RuntimeError("BLOCKED: new root saved-correction authorization marker is absent")
            if not FULL_SUMMARY.is_file():
                raise RuntimeError("BLOCKED: original full-suite native summary is unavailable")
            full = json.loads(FULL_SUMMARY.read_text())
            if not full.get("finished_utc") or full.get("active_stage") is not None:
                raise RuntimeError("BLOCKED: original full-suite runner is still active; no overlapping native execution")
            previous_health = full.get("final_runtime_health", {}).get("last_live_health", full.get("latest_health", {}))
            if previous_health.get("unsafe_to_continue"):
                raise RuntimeError("BLOCKED: original full suite ended with unsafe runtime health; root must investigate before another native interval")
            authorization = json.loads(marker.read_text())
            pinned = authorization.get("sha256", {})
            proof = json.loads(CORRECTION_PROOF.read_text())
            initial = json.loads(INITIAL_MANIFEST.read_text())
            if proof.get("productionApkSha256") != PRODUCTION_SHA256 or proof.get("sha256") != CORRECTED_SHA256:
                raise RuntimeError("BLOCKED: correction proof names unexpected production or fixture bytes")
            if not proof.get("buildPassed") or not proof.get("zipCrcPassed") or not proof.get("signatureVerified") or proof.get("productionClassesPackaged") != [] or not proof.get("classpathMatchesFinalProductionByteForByte"):
                raise RuntimeError("BLOCKED: corrected fixture isolation/signature/classpath proof is incomplete")
            if proof.get("previewSha1") != "6824bc2c9fcf54266ddb53dab1057d0017823ffc":
                raise RuntimeError("BLOCKED: corrected fixture does not use the existing Preview certificate")
            if base.digest(INITIAL_MANIFEST) != proof.get("initialFixturesBuiltManifestSha256"):
                raise RuntimeError("BLOCKED: immutable original fixture manifest differs from the correction's source receipt")
            if base.digest(base.PRODUCTION_APK) != PRODUCTION_SHA256 or base.digest(CORRECTED_APK) != CORRECTED_SHA256:
                raise RuntimeError("BLOCKED: local production/corrected fixture bytes changed")
            for apk, expected in [(base.PRODUCTION_APK, PRODUCTION_SHA256), (self.fixture_path, self.fixture_sha256), (UI_HELPER, UI_HELPER_SHA256)]:
                if base.digest(apk) != expected:
                    raise RuntimeError("BLOCKED: selected saved artifact changed: " + str(apk))
                if pinned.get(str(apk.resolve())) != expected:
                    raise RuntimeError("BLOCKED: new root save marker does not pin the exact APK path/hash: " + str(apk))
            self.ui_helper_approved = True
            # Overlay is written only in this new interval. Keep the original full
            # suite's manifest and original QA fixture unchanged for independent review.
            overlay = json.loads(json.dumps(initial))
            unchanged = []
            for entry in overlay["fixtures"]:
                if entry["stage"] == "daybook":
                    entry.update(apkPath=str(CORRECTED_APK), sha256=CORRECTED_SHA256,
                                 classes=proof["classes"], dexClassNames=proof["dexClassNames"],
                                 productionClassesPackaged=[], classpathMatchesFinalProductionByteForByte=True,
                                 buildPassed=True, signatureVerified=True, previewSha1=proof["previewSha1"])
                else:
                    apk = Path(entry["apkPath"])
                    actual = base.digest(apk)
                    if actual != entry["sha256"]:
                        raise RuntimeError("BLOCKED: an independent original fixture changed while preparing Daybook-only continuation")
                    unchanged.append({"stage": entry["stage"], "path": str(apk), "sha256": actual, "not_reinstalled_or_rerun": True})
            overlay["note"] = "Corrected Daybook-only manifest overlay. Four other fixture entries/bytes are unchanged and are not reinstalled or rerun."
            base.write_json(EVIDENCE / "fixture-manifest-overlay.json", overlay)
            self.summary["artifact_gate"] = {"status": "PASS", "root_marker_path": str(marker), "root_marker_sha256": base.digest(marker),
                                             "source_saved_version": authorization.get("source_saved_version"),
                                             "source_package_sha256": authorization.get("source_package_sha256"),
                                             "production_sha256": PRODUCTION_SHA256, "corrected_fixture_path": str(CORRECTED_APK),
                                             "corrected_fixture_sha256": CORRECTED_SHA256, "unchanged_other_fixtures": unchanged,
                                             "correction_proof_sha256": base.digest(CORRECTION_PROOF),
                                             "ui_helper_path": str(UI_HELPER), "ui_helper_sha256": UI_HELPER_SHA256,
                                             "selected_fixture_path": str(self.fixture_path), "selected_fixture_sha256": self.fixture_sha256}
            self.checkpoint()
            _, boot, _ = self.required_command("gate-boot-completed", ["shell", "getprop", "sys.boot_completed"], 30)
            _, qemu, _ = self.required_command("gate-qemu", ["shell", "getprop", "ro.kernel.qemu"], 30)
            _, packages, _ = self.required_command("gate-packages", ["shell", "pm", "list", "packages"], 60)
            if boot.strip() != b"1" or qemu.strip() != b"1":
                raise RuntimeError("BLOCKED: existing emulator is not fully booted or is not an emulator")
            if b"package:com.google.android.gms" in packages.splitlines():
                raise RuntimeError("BLOCKED: corrected synthetic UI fixture requires the existing AOSP device without Google services")
            _, baseline, _ = self.required_command("baseline-logcat-all", ["logcat", "-b", "all", "-d", "-v", "threadtime"], 120)
            (EVIDENCE / "baseline-logcat-all.log").write_bytes(baseline)
            _, self.baseline_anr, _ = self.required_command("baseline-lastanr", ["shell", "dumpsys", "activity", "lastanr"], 60)
            (EVIDENCE / "baseline-lastanr.log").write_bytes(self.baseline_anr)
            probe = self.focused_modal_probe("preinstall")
            self.summary["preinstall_environment_gate"] = {"status": "PASS" if probe["verified_clear"] else "BLOCKED", "probe": probe,
                                                           "before_any_install_or_ui_input": True}
            self.checkpoint()
            if not probe["verified_clear"]:
                self.failure_capture("preinstall-environment")
                raise RuntimeError("BLOCKED: pre-existing focused modal or unverified global-focus/native-hierarchy probe; no installation or physical input, no dialog dismissed")
            self.required_command("fresh-interval-clear-preserved-buffers", ["logcat", "-b", "all", "-c"], 30)
            self.start_logcat()
            if self.install_fixture:
                self.summary["active_stage"] = "install-corrected-daybook-only"; self.checkpoint()
                _, install, _ = self.required_command("install-corrected-daybook-only", ["install", "--no-incremental", "-r", str(self.fixture_path)], 180)
                if b"Success" not in install:
                    raise RuntimeError("Corrected Daybook install returned no Success receipt")
                self.summary["corrected_fixture_installed"] = True
            self.verify_installed_apk(base.PACKAGE, PRODUCTION_SHA256, "production")
            self.verify_installed_apk(self.fixture_package, self.fixture_sha256, self.fixture_label)
            self.summary["installed"] = True
            _, package_dump, _ = self.required_command("installed-package-metadata", ["shell", "dumpsys", "package", base.PACKAGE], 60)
            package_text = package_dump.decode(errors="replace")
            if not re.search(r"\bversionName=1\.9\.9\b", package_text) or not re.search(r"\bversionCode=19\b", package_text):
                raise RuntimeError("Existing installed production version does not match 1.9.9/19")
            self.required_command("wake-display", ["shell", "input", "keyevent", "224"], 30)
            self.required_command("dismiss-keyguard", ["shell", "wm", "dismiss-keyguard"], 30)
            self.required_command("test-display-timeout", ["shell", "settings", "put", "system", "screen_off_timeout", "1800000"], 30)
            self.health("prelaunch")
            if self.unhealthy:
                raise RuntimeError("Unsafe native health before corrected Daybook cold launch; no fixture test started")
            self.required_command("cold-launch-force-stop", ["shell", "am", "force-stop", base.PACKAGE], 30)
            _, launch, _ = self.required_command("native-main-launch", ["shell", "am", "start", "-W", "-n", base.PACKAGE + "/.MainActivity"], 90)
            self.summary["launched"] = b"Status: ok" in launch
            if not self.summary["launched"]:
                raise RuntimeError("Existing production MainActivity did not cold-launch with Status: ok")
            _, activities, _ = self.required_command("native-main-resumed-state", ["shell", "dumpsys", "activity", "activities"], 60)
            resumed = [line for line in activities.decode(errors="replace").splitlines()
                       if re.search(r"(?:mResumedActivity|topResumedActivity).*app\.voiceflow/(?:\.MainActivity|app\.voiceflow\.MainActivity)", line)]
            self.summary["cold_launch"] = {"status": "PASS" if resumed else "FAILED", "resumed_activity_lines": resumed}
            if not resumed:
                raise RuntimeError("Corrected Daybook interval did not establish a resumed production MainActivity")
            self.summary["status"] = "RUNNING"; self.checkpoint()

        def verify_installed_apk(self, package, expected, label):
            _, paths, _ = self.required_command("installed-" + label + "-path", ["shell", "pm", "path", package], 30)
            candidates = [line.removeprefix("package:").strip() for line in paths.decode().splitlines() if line.startswith("package:")]
            if len(candidates) != 1:
                raise RuntimeError("Unexpected installed split/path list for " + package)
            path = EVIDENCE / ("installed-" + label + ".apk")
            self.required_command("installed-" + label + "-pull", ["pull", candidates[0], str(path)], 120)
            actual = base.digest(path)
            receipt = {"package": package, "path": str(path), "device_path": candidates[0], "sha256": actual, "matches_pinned_bytes": actual == expected}
            self.summary["installed_" + label.replace("-", "_")] = receipt
            if actual != expected:
                raise RuntimeError("Installed " + label + " does not match pinned bytes")

        def run_tests(self):
            self.health("before-daybook-first-input")
            stage = self.stage("daybook-ui-corrected", "app.voiceflow.daybook.fixture/app.voiceflow.DaybookUiTest", timeout=600)
            if not stage.get("command"):
                self.failure_capture("daybook-not-started")
                self.summary["daybook_native_captures"] = "BLOCKED: no corrected fixture execution; existing screenshots are not presented as fresh corrected captures"
                self.checkpoint()
                return
            for suffix in ("home", "settings", "backup", "google-unavailable"):
                self.pull_capture("daybook-" + suffix, "/sdcard/Android/media/app.voiceflow/daybook-" + suffix + "-199-rebuilt.png",
                                  "daybook-" + suffix + "-199-rebuilt.png")

    return CorrectedRunner


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--marker", type=Path, default=DEFAULT_MARKER)
    parser.add_argument("--wall-budget-seconds", type=int, default=1200)
    options = parser.parse_args()
    if options.wall_budget_seconds < 120 or options.wall_budget_seconds > 3450:
        parser.error("Use a wall budget between 120 and 3450 seconds in the existing queue")
    if (EVIDENCE / "summary.json").exists():
        raise SystemExit("Existing corrected-Daybook receipt found; refusing to overwrite or automatically retry it")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    base = load_frozen_runner()
    runner = build_corrected_runner(base, options.marker.resolve())(options.wall_budget_seconds)
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
