#!/usr/bin/env python3
"""One saved-and-pinned VoiceFlow 1.9.9 run in the existing ADB namespace.

No emulator is created, restarted, reset or stopped here. Invoke only through the
existing native199 filesystem queue after root's saved-artifact install gate.
Each native stage runs once; failed physical taps are never retried. Checkpoint
JSONs and full command stdout/stderr remain available while later stages run.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import zlib

TOOLCHAIN = Path(__file__).resolve().parent
WORKSPACE = TOOLCHAIN.parent
PROJECT = WORKSPACE / "VoiceFlow"
ADB = TOOLCHAIN / "android-sdk/platform-tools/adb"
SERIAL = "emulator-5554"
PACKAGE = "app.voiceflow"
PRODUCTION_APK = WORKSPACE / "VoiceFlow-1.9.9.apk"
EXPECTED_PRODUCTION_SHA256 = "2bdb49cefdd5235de2670eab04bfe0b73fee51ca73a43df42bbf69e582af0270"
MARKER = TOOLCHAIN / "native199-install-authorized.json"
FIXTURE_MANIFEST = TOOLCHAIN / "fixtures-built-199.json"
EVIDENCE = PROJECT / "evidence/1.9.9-native"
APP_RE = re.compile(r"\bapp\.voiceflow(?:\.[A-Za-z0-9_.]+)?\b")


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(path):
    sha = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)


def parse_instrumentation(output):
    """Parse actual result fields/codes; never synthesize a check count."""
    fields = {}
    active = None
    codes = []
    for line in output.splitlines():
        match = re.match(r"INSTRUMENTATION_RESULT: ([^=]+)=(.*)", line)
        if match:
            active = match.group(1).strip()
            fields[active] = match.group(2)
        elif line.startswith("INSTRUMENTATION_"):
            active = None
            code = re.match(r"INSTRUMENTATION_CODE:\s*(-?\d+)", line)
            if code:
                codes.append(int(code.group(1)))
        elif active:
            fields[active] += "\n" + line
    count = None
    raw_count = fields.get("executed_checks")
    if raw_count is not None and re.fullmatch(r"\d+", raw_count.strip()):
        count = int(raw_count)
    elif re.match(r"(?:PASS|SEEDED):\s*(\d+)\b", fields.get("result", "")):
        count = int(re.match(r"(?:PASS|SEEDED):\s*(\d+)\b", fields["result"]).group(1))
    return {"fields": fields, "instrumentation_codes": codes,
            "executed_assertions": count,
            "physical_taps": int(fields["physical_taps"]) if fields.get("physical_taps", "").isdigit() else None,
            "am_reported_failure": "INSTRUMENTATION_FAILED" in output}


def log_health(text):
    """Keep matched lines and unrelated incidents; zero matches is interval-limited."""
    lines = text.splitlines()
    app = []
    system = []
    unsafe_system = []
    for index, line in enumerate(lines):
        lower = line.lower()
        kind = None
        if re.search(r"\bam_anr\s*:", lower) or "anr in " in lower or ("anr" in lower and "windowmanager" in lower):
            kind = "ANR"
        elif re.search(r"\bam_crash\s*:", lower):
            kind = "CRASH"
        elif "fatal exception" in lower:
            kind = "JAVA_FATAL"
        elif "fatal signal " in lower:
            kind = "NATIVE_FATAL"
        elif "cmdline:" in lower and "debug" in lower and APP_RE.search(line):
            kind = "APP_TOMBSTONE"
        if kind:
            # FATAL EXCEPTION is usually followed by an AndroidRuntime Process line.
            context = "\n".join(lines[index:min(len(lines), index + 18)])
            matches_app = bool(APP_RE.search(line))
            if kind in ("JAVA_FATAL", "NATIVE_FATAL"):
                matches_app |= bool(re.search(r"(?:Process:|Cmdline:|>>>|\()\s*app\.voiceflow\b", context))
            item = {"line": index + 1, "kind": kind, "text": line}
            if matches_app:
                app.append(item)
            else:
                system.append(item)
        if "fatal exception in system process" in lower or "watchdog killing system process" in lower:
            unsafe_system.append({"line": index + 1, "text": line})
    return {"app_or_fixture_crash_anr_matched_lines": app,
            "app_or_fixture_matched_line_count": len(app),
            "unrelated_system_crash_anr_matched_lines": system,
            "unrelated_system_matched_line_count": len(system),
            "system_process_failure_matches": unsafe_system,
            "scope": "Unfiltered logcat lines collected only in this version-specific interval; matched-line totals are not unique incident counts."}


def inspect_png(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Pulled bytes are not a PNG")
    offset = 8
    width = height = None
    saw_end = False
    while offset + 12 <= len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(data):
            raise ValueError("PNG chunk is truncated")
        payload = data[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack(">I", data[offset + 8 + length:end])[0]
        if zlib.crc32(kind + payload) & 0xFFFFFFFF != expected_crc:
            raise ValueError("PNG chunk CRC failed")
        if kind == b"IHDR":
            if length != 13:
                raise ValueError("PNG IHDR is malformed")
            width, height = struct.unpack(">II", payload[:8])
        if kind == b"IEND":
            saw_end = True
            if end != len(data):
                raise ValueError("Unexpected bytes after PNG IEND")
            break
        offset = end
    if not saw_end or not width or not height:
        raise ValueError("PNG has no complete image")
    return {"width": width, "height": height, "png_chunks_crc_verified": True}


class Runner:
    def __init__(self, budget):
        self.env = os.environ.copy()
        self.env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:5037"
        self.started_monotonic = time.monotonic()
        self.deadline = self.started_monotonic + budget
        self.logcat = None
        self.logcat_out = self.logcat_err = None
        self.baseline_anr = b""
        self.unhealthy = False
        self.summary = {"version": "1.9.9", "version_code": 19, "production_sha256": EXPECTED_PRODUCTION_SHA256,
                        "serial": SERIAL, "started_utc": now(), "status": "STARTING",
                        "installed": False, "launched": False, "stages": [], "screenshots": [],
                        "runtime_scope": "Fresh native run on existing isolated AOSP API 35 TCG emulator; synthetic local/fake-server fixtures. No Google account consent, live upload or live Drive restore.",
                        "live_google_authorization": "BLOCKED: AOSP emulator has no Google Play services",
                        "no_automatic_retries": True, "wall_budget_seconds": budget}

    def checkpoint(self):
        self.summary["updated_utc"] = now()
        write_json(EVIDENCE / "summary.json", self.summary)

    def command(self, name, args, timeout=60, record=True):
        command = [str(ADB), "-s", SERIAL] + list(args)
        return self.host_command(name, command, timeout, record)

    def host_command(self, name, command, timeout=60, record=True):
        remaining = self.deadline - time.monotonic()
        actual_timeout = max(1, min(timeout, remaining - 20))
        out_path = EVIDENCE / "commands" / (name + ".stdout.log")
        err_path = EVIDENCE / "commands" / (name + ".stderr.log")
        out_path.parent.mkdir(parents=True, exist_ok=True)
        if out_path.exists() or err_path.exists():
            raise RuntimeError("Refusing to overwrite an existing command receipt: " + name)
        receipt = {"name": name, "argv": command, "started_utc": now(), "timeout_seconds": actual_timeout,
                   "stdout_path": str(out_path), "stderr_path": str(err_path), "timed_out": False}
        beginning = time.monotonic()
        with out_path.open("wb") as output, err_path.open("wb") as error:
            child = subprocess.Popen(command, env=self.env, stdout=output, stderr=error)
            try:
                receipt["exit_code"] = child.wait(timeout=actual_timeout)
            except subprocess.TimeoutExpired:
                receipt["timed_out"] = True
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill(); child.wait(timeout=5)
                receipt["exit_code"] = child.returncode
        receipt["finished_utc"] = now()
        receipt["elapsed_seconds"] = round(time.monotonic() - beginning, 3)
        receipt["status"] = "PASS" if receipt["exit_code"] == 0 and not receipt["timed_out"] else "FAILED"
        if record:
            write_json(EVIDENCE / "commands" / (name + ".json"), receipt)
        return receipt, out_path.read_bytes(), err_path.read_bytes()

    def required_command(self, name, args, timeout=60):
        receipt, output, error = self.command(name, args, timeout)
        if receipt["status"] != "PASS":
            raise RuntimeError("Required command failed: " + name)
        return receipt, output, error

    def gates_and_install(self):
        if not MARKER.is_file():
            raise RuntimeError("BLOCKED: root saved-artifact install marker is absent")
        marker = json.loads(MARKER.read_text())
        manifest = json.loads(FIXTURE_MANIFEST.read_text())
        pinned = marker.get("sha256", {})
        fixture_by_stage = {item["stage"]: item for item in manifest["fixtures"]}
        required = [PRODUCTION_APK] + [Path(fixture_by_stage[name]["apkPath"]) for name in ("daybook", "backup", "draft", "drive", "side")]
        verified = []
        for apk in required:
            actual = digest(apk)
            if pinned.get(str(apk.resolve())) != actual:
                raise RuntimeError("BLOCKED: APK bytes do not match root save marker: " + str(apk))
            verified.append({"path": str(apk), "sha256": actual})
        if verified[0]["sha256"] != EXPECTED_PRODUCTION_SHA256 or manifest.get("productionApkSha256") != EXPECTED_PRODUCTION_SHA256:
            raise RuntimeError("BLOCKED: production APK is not the root-pinned 1.9.9 build")
        for item in fixture_by_stage.values():
            if not item.get("buildPassed") or not item.get("signatureVerified") or item.get("productionClassesPackaged") != [] or not item.get("classpathMatchesFinalProductionByteForByte"):
                raise RuntimeError("BLOCKED: fixture build/classpath isolation receipt is incomplete")
        self.summary["artifact_gate"] = {"status": "PASS", "marker_sha256": digest(MARKER), "apks": verified,
                                         "source_saved_version": marker.get("source_saved_version"), "apk_saved_version": marker.get("apk_saved_version")}
        self.checkpoint()
        _, boot, _ = self.required_command("gate-boot-completed", ["shell", "getprop", "sys.boot_completed"], 30)
        _, qemu, _ = self.required_command("gate-qemu", ["shell", "getprop", "ro.kernel.qemu"], 30)
        _, packages, _ = self.required_command("gate-packages", ["shell", "pm", "list", "packages"], 60)
        if boot.strip() != b"1" or qemu.strip() != b"1":
            raise RuntimeError("BLOCKED: existing emulator is not fully booted or is not an emulator")
        if b"package:com.google.android.gms" in packages.splitlines():
            raise RuntimeError("BLOCKED: these isolated synthetic fixtures require an AOSP device without Google services")
        # Preserve boot/pre-install errors and any old last-ANR record before clearing buffers.
        _, baseline, _ = self.required_command("baseline-logcat-all", ["logcat", "-b", "all", "-d", "-v", "threadtime"], 120)
        (EVIDENCE / "baseline-logcat-all.log").write_bytes(baseline)
        _, self.baseline_anr, _ = self.required_command("baseline-lastanr", ["shell", "dumpsys", "activity", "lastanr"], 60)
        (EVIDENCE / "baseline-lastanr.log").write_bytes(self.baseline_anr)
        _, initial_windows, _ = self.required_command("gate-focused-system-dialog", ["shell", "dumpsys", "window", "windows"], 60)
        focused_errors = [line for line in initial_windows.decode(errors="replace").splitlines()
                          if re.search(r"mCurrentFocus.*(?:Application Error|Application Not Responding|ANR)", line, re.I)]
        if focused_errors:
            self.summary["blocking_system_modal"] = focused_errors
            raise RuntimeError("BLOCKED: focused application error/ANR modal; full baseline preserved and no dialog dismissed")
        self.required_command("fresh-interval-clear-preserved-buffers", ["logcat", "-b", "all", "-c"], 30)
        self.start_logcat()
        for index, apk in enumerate(required):
            self.summary["active_stage"] = "install-" + apk.name; self.checkpoint()
            receipt, output, _ = self.required_command("install-%02d" % index, ["install", "--no-incremental", "-r", str(apk)], 180)
            if b"Success" not in output:
                raise RuntimeError("Install returned no Success receipt: " + apk.name)
        self.summary["installed"] = True
        _, paths, _ = self.required_command("installed-apk-path", ["shell", "pm", "path", PACKAGE], 30)
        candidates = [line.removeprefix("package:").strip() for line in paths.decode().splitlines() if line.startswith("package:")]
        if len(candidates) != 1:
            raise RuntimeError("Unexpected installed APK split/path list")
        pulled = EVIDENCE / "installed-production.apk"
        self.required_command("installed-apk-pull", ["pull", candidates[0], str(pulled)], 120)
        installed_hash = digest(pulled)
        self.summary["installed_apk"] = {"path": str(pulled), "device_path": candidates[0], "sha256": installed_hash,
                                          "equals_saved_production": installed_hash == EXPECTED_PRODUCTION_SHA256}
        if installed_hash != EXPECTED_PRODUCTION_SHA256:
            raise RuntimeError("Installed APK differs from saved pinned production bytes")
        _, package_dump, _ = self.required_command("installed-package-metadata", ["shell", "dumpsys", "package", PACKAGE], 60)
        package_text = package_dump.decode(errors="replace")
        if not re.search(r"\bversionName=1\.9\.9\b", package_text) or not re.search(r"\bversionCode=19\b", package_text):
            raise RuntimeError("Installed versionName/versionCode does not match 1.9.9/19")
        self.required_command("wake-display", ["shell", "input", "keyevent", "224"], 30)
        self.required_command("dismiss-keyguard", ["shell", "wm", "dismiss-keyguard"], 30)
        self.required_command("test-display-timeout", ["shell", "settings", "put", "system", "screen_off_timeout", "1800000"], 30)
        self.health("prelaunch")
        if self.unhealthy:
            raise RuntimeError("Native app/fixture/system health is unsafe before cold launch; no tests started")
        self.required_command("cold-launch-force-stop", ["shell", "am", "force-stop", PACKAGE], 30)
        _, launch, _ = self.required_command("native-main-launch", ["shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity"], 90)
        self.summary["launched"] = b"Status: ok" in launch
        if not self.summary["launched"]:
            raise RuntimeError("Production MainActivity did not return Status: ok")
        _, activity_state, _ = self.required_command("native-main-resumed-state", ["shell", "dumpsys", "activity", "activities"], 60)
        resumed_lines = [line for line in activity_state.decode(errors="replace").splitlines()
                         if re.search(r"(?:mResumedActivity|topResumedActivity).*app\.voiceflow/(?:\.MainActivity|app\.voiceflow\.MainActivity)", line)]
        self.summary["cold_launch"] = {"status": "PASS" if resumed_lines else "FAILED", "am_start_wait_receipt": "commands/native-main-launch.json",
                                       "resumed_activity_lines": resumed_lines, "state_receipt": "commands/native-main-resumed-state.json"}
        if not resumed_lines:
            raise RuntimeError("Cold-start Status: ok did not establish MainActivity as the resumed Activity")
        self.summary["status"] = "RUNNING"; self.checkpoint()

    def start_logcat(self):
        self.logcat_out = (EVIDENCE / "native-live-all-199.log").open("wb")
        self.logcat_err = (EVIDENCE / "native-logcat-stderr-199.log").open("wb")
        self.logcat = subprocess.Popen([str(ADB), "-s", SERIAL, "logcat", "-b", "all", "-v", "threadtime"],
                                      env=self.env, stdout=self.logcat_out, stderr=self.logcat_err)
        self.summary["runtime_interval_started_utc"] = now()

    def health(self, name):
        live = EVIDENCE / "native-live-all-199.log"
        health = log_health(live.read_text(errors="replace") if live.exists() else "")
        health["checked_utc"] = now()
        health["continuous_logcat_alive"] = self.logcat is not None and self.logcat.poll() is None
        anr_receipt, anr, _ = self.command(name + "-lastanr", ["shell", "dumpsys", "activity", "lastanr"], 60)
        last_anr_path = EVIDENCE / (name + "-lastanr.log"); last_anr_path.write_bytes(anr)
        new_anr = anr != self.baseline_anr
        anr_head = "\n".join(anr.decode(errors="replace").splitlines()[:180])
        health["lastanr_changed_from_preserved_baseline"] = new_anr
        health["new_lastanr_names_app_or_fixture"] = bool(new_anr and re.search(r"(?:ANR in|Process(?: Name)?:)\s*app\.voiceflow\b", anr_head))
        health["lastanr_path"] = str(last_anr_path)
        window_receipt, window, _ = self.command(name + "-windows", ["shell", "dumpsys", "window", "windows"], 60)
        blocked_window = [line for line in window.decode(errors="replace").splitlines()
                          if re.search(r"mCurrentFocus.*(?:Application Error|Application Not Responding|ANR)", line, re.I)]
        health["focused_error_dialog_lines"] = blocked_window
        transport_ok = anr_receipt["status"] == "PASS" and window_receipt["status"] == "PASS"
        health["unsafe_to_continue"] = bool(health["app_or_fixture_matched_line_count"] or health["new_lastanr_names_app_or_fixture"]
                                             or health["system_process_failure_matches"] or blocked_window
                                             or not transport_ok or not health["continuous_logcat_alive"])
        health["status"] = "FAILED" if health["unsafe_to_continue"] else "PASS_WITH_UNRELATED_SYSTEM_ERRORS" if health["unrelated_system_matched_line_count"] else "PASS"
        write_json(EVIDENCE / (name + "-health.json"), health)
        self.summary["latest_health"] = health
        self.unhealthy |= health["unsafe_to_continue"]
        return health

    def stage(self, name, component, args=(), timeout=300, expected_prefix="PASS:", dependency=None, reset=True, target=PACKAGE):
        receipt = {"name": name, "component": component, "args": list(args), "target_package": target,
                   "started_utc": now(), "dependency": dependency}
        if self.unhealthy:
            receipt.update(status="BLOCKED", reason="App, fixture, system/transport or focused error-dialog health is unsafe; logs preserved and no dialogs dismissed")
        elif dependency and not self.stage_succeeded(dependency):
            receipt.update(status="BLOCKED", reason="Required stage did not succeed: " + dependency)
        elif self.deadline - time.monotonic() < 90:
            receipt.update(status="BLOCKED", reason="Runner wall budget exhausted before starting this stage; no input or automatic retry")
        else:
            self.summary["active_stage"] = name; self.checkpoint()
            if reset:
                reset_receipt, _, _ = self.command(name + "-force-stop", ["shell", "am", "force-stop", target], 30)
                receipt["force_stop"] = reset_receipt
                if reset_receipt["status"] != "PASS":
                    receipt.update(status="BLOCKED", reason="Could not isolate the target process")
                    self.unhealthy = True
            if "status" not in receipt:
                command, output, error = self.command(name, ["shell", "am", "instrument", "-w"] + list(args) + [component], timeout)
                parsed = parse_instrumentation(output.decode(errors="replace"))
                receipt.update(command=command, instrumentation=parsed)
                fields = parsed["fields"]
                succeeded = command["status"] == "PASS" and fields.get("result", "").startswith(expected_prefix) and not fields.get("failure") and not fields.get("shortMsg") and parsed["instrumentation_codes"] and parsed["instrumentation_codes"][-1] == -1 and not parsed["am_reported_failure"]
                receipt["status"] = "PASS" if succeeded else "FAILED"
                receipt["assertions_status"] = receipt["status"]
                receipt["executed_assertions"] = parsed["executed_assertions"]
                if command["timed_out"]:
                    # Stop only the timed-out synthetic target, not the emulator/system.
                    stopped, _, _ = self.command(name + "-timeout-stop-target", ["shell", "am", "force-stop", target], 30)
                    receipt["timeout_target_stop"] = stopped
                    receipt["fixture_finally_cleanup"] = "UNVERIFIED after timeout; disposable synthetic installation only"
                if not succeeded:
                    self.failure_capture(name)
                receipt["health"] = self.health(name)
                receipt["runtime_health_status"] = receipt["health"]["status"]
                receipt["clean_native_pass"] = bool(succeeded and receipt["health"]["status"] == "PASS")
                if receipt["health"]["unsafe_to_continue"]:
                    # Keep the assertion result separately. An app ANR/crash cannot
                    # become a native PASS merely because the UI assertions passed.
                    receipt["status"] = "FAILED"
        receipt["finished_utc"] = now()
        write_json(EVIDENCE / (name + ".json"), receipt)
        self.summary["stages"].append(receipt); self.checkpoint()
        return receipt

    def stage_succeeded(self, name):
        return any(stage["name"] == name and stage["status"] == "PASS" for stage in self.summary["stages"])

    def failure_capture(self, name):
        command, output, _ = self.command(name + "-unresolved-screen", ["exec-out", "screencap", "-p"], 60)
        if command["status"] == "PASS":
            try:
                metadata = inspect_png(output)
                path = EVIDENCE / (name + "-unresolved-screen.png"); path.write_bytes(output)
                write_json(path.with_suffix(".json"), {"native_screen": True, "unresolved_stage": name,
                                                       "production_sha256": EXPECTED_PRODUCTION_SHA256, **metadata})
            except ValueError as error:
                write_json(EVIDENCE / (name + "-unresolved-screen-export.json"), {"status": "FAILED", "reason": str(error)})

    def pull_capture(self, name, remote, local, package=PACKAGE, run_as=False, required=True):
        arguments = ["exec-out", "run-as", package, "cat", remote] if run_as else ["exec-out", "cat", remote]
        receipt, data, error = self.command(name + "-pull", arguments, 60)
        result = {"name": name, "package": package, "remote_path": remote, "local_path": str(EVIDENCE / local),
                  "production_sha256": EXPECTED_PRODUCTION_SHA256, "production_version": "1.9.9", "synthetic_qa_data": True,
                  "native_screenshot": True, "reference_or_html_render": False, "required": required, "command": receipt}
        try:
            if receipt["status"] != "PASS":
                raise ValueError("Actual native screenshot file was not exported/pullable; see command stderr")
            result.update(inspect_png(data))
            (EVIDENCE / local).write_bytes(data)
            result["sha256"] = hashlib.sha256(data).hexdigest()
            hash_args = ["shell", "run-as", package, "sha256sum", remote] if run_as else ["shell", "sha256sum", remote]
            hash_receipt, device_hash, _ = self.command(name + "-remote-hash", hash_args, 30)
            if hash_receipt["status"] != "PASS" or device_hash.decode(errors="replace").split()[0] != result["sha256"]:
                raise ValueError("Pulled screenshot hash differs from device file or device hash could not be checked")
            result["device_file_hash_matches"] = True; result["status"] = "PASS"
        except (ValueError, IndexError) as error:
            result.update(status="FAILED" if required else "BLOCKED", reason=str(error))
        self.summary["screenshots"].append(result); self.checkpoint()
        write_json(EVIDENCE / (name + "-capture.json"), result)
        return result

    def side_setup(self):
        name = "side-setup"
        if self.unhealthy or self.deadline - time.monotonic() < 90:
            receipt = {"name": name, "status": "BLOCKED", "reason": "Unsafe health or insufficient remaining runner budget"}
            self.summary["stages"].append(receipt); self.checkpoint(); return
        commands = [
            ("side-overlay-appop", ["shell", "cmd", "appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow"]),
            ("side-grant-main-audio", ["shell", "pm", "grant", PACKAGE, "android.permission.RECORD_AUDIO"]),
            ("side-grant-fixture-audio", ["shell", "pm", "grant", "app.voiceflow.fixture", "android.permission.RECORD_AUDIO"]),
            ("side-enable-ime", ["shell", "ime", "enable", PACKAGE + "/.FlowKeyboard"]),
            ("side-fake-recognizer", ["shell", "settings", "put", "secure", "voice_recognition_service", "app.voiceflow.fixture/.FakeSpeech"])]
        for command_name, argv in commands:
            command, _, _ = self.command(command_name, argv, 45)
            if command["status"] != "PASS":
                self.summary["stages"].append({"name": name, "status": "FAILED", "reason": "Fixture setup command failed", "command": command}); self.checkpoint(); return
        setup = self.stage("side-preferences", "app.voiceflow.fixture/app.voiceflow.ControlSetup", timeout=180, expected_prefix="Prepared synthetic speech controls")
        if setup["status"] != "PASS" or self.unhealthy:
            self.summary["stages"].append({"name": name, "status": "BLOCKED", "reason": "ControlSetup or its native health did not safely prepare synthetic preferences"}); self.checkpoint(); return
        _, old_services, _ = self.required_command("side-original-accessibility-services", ["shell", "settings", "get", "secure", "enabled_accessibility_services"], 30)
        services = [value for value in old_services.decode().strip().split(":") if value and value != "null"]
        if PACKAGE + "/.FlowAccessibility" not in services:
            services.append(PACKAGE + "/.FlowAccessibility")
        # Preserve other enabled services. No global accessibility deletion or error suppression.
        for command_name, argv in [
            ("side-enable-accessibility-component", ["shell", "settings", "put", "secure", "enabled_accessibility_services", ":".join(services)]),
            ("side-enable-accessibility", ["shell", "settings", "put", "secure", "accessibility_enabled", "1"]),
            ("side-select-ime", ["shell", "ime", "set", PACKAGE + "/.FlowKeyboard"]),
            ("side-wake-display", ["shell", "input", "keyevent", "224"]),
            ("side-dismiss-keyguard", ["shell", "wm", "dismiss-keyguard"])]:
            command, _, _ = self.command(command_name, argv, 45)
            if command["status"] != "PASS":
                self.summary["stages"].append({"name": name, "status": "FAILED", "reason": "Fixture setup command failed", "command": command}); self.checkpoint(); return
        receipt = {"name": name, "status": "PASS", "finished_utc": now(), "executed_assertions": None,
                   "note": "Synthetic setup commands and ControlSetup succeeded; this is preparation, not a real speech/Google functional pass. No Phone/Bluetooth packages disabled, system dialogs suppressed or other accessibility services removed."}
        write_json(EVIDENCE / (name + ".json"), receipt); self.summary["stages"].append(receipt); self.checkpoint()

    def run_tests(self):
        self.stage("daybook-ui", "app.voiceflow.daybook.fixture/app.voiceflow.DaybookUiTest", timeout=600)
        for suffix in ("home", "settings", "backup", "google-unavailable"):
            self.pull_capture("daybook-" + suffix, "/sdcard/Android/media/app.voiceflow/daybook-" + suffix + "-199-rebuilt.png", "daybook-" + suffix + "-199-rebuilt.png")
        self.stage("credential-policy", "app.voiceflow.backup.fixture/app.voiceflow.BackupCredentialPolicyTest", timeout=300)
        self.stage("archive-schema", "app.voiceflow.backup.fixture/app.voiceflow.BackupArchiveTest", timeout=600)
        self.stage("history-deletion", "app.voiceflow.backup.fixture/app.voiceflow.BackupHistoryDeletionTest", timeout=300)
        self.stage("rejected-generation-listing", "app.voiceflow.drive.fixture/app.voiceflow.DriveListingTest", timeout=300)
        self.stage("draft-same-process", "app.voiceflow.draft.fixture/app.voiceflow.DraftRecreationTest", timeout=300)
        for case in ("restore", "delete", "clear"):
            self.stage("draft-" + case + "-seed", "app.voiceflow.draft.fixture/app.voiceflow.DraftRecreationTest",
                       ["-e", "process_stage", "seed", "-e", "process_case", case], timeout=180, expected_prefix="SEEDED:")
            # A separate, recorded am force-stop precedes verify, giving the production
            # fixture the exact PRE-mutation Bundle and a genuinely different process.
            self.stage("draft-" + case + "-verify", "app.voiceflow.draft.fixture/app.voiceflow.DraftRecreationTest",
                       ["-e", "process_stage", "verify"], timeout=180, dependency="draft-" + case + "-seed")
        self.stage("backup-screen", "app.voiceflow.drive.fixture/app.voiceflow.DriveBackupScreenTest", timeout=300)
        for old, fresh in [("drive-backup-disconnected-195.png", "drive-backup-disconnected-native-199.png"),
                           ("drive-backup-google-unavailable-195.png", "drive-backup-google-unavailable-native-199.png")]:
            self.pull_capture(fresh.removesuffix(".png"), "/sdcard/Android/media/app.voiceflow/" + old, fresh, required=False)
        self.stage("cloud-full-offline", "app.voiceflow.drive.fixture/app.voiceflow.DriveCloudTest", timeout=900)
        self.stage("cloud-partial-listing-offline", "app.voiceflow.drive.fixture/app.voiceflow.DriveCloudTest", ["-e", "partial_listing", "true"], timeout=600)
        self.side_setup()
        self.stage("side-controls", "app.voiceflow.fixture/.SidePreview", timeout=600, dependency="side-setup", reset=False, target="app.voiceflow.fixture")
        for filename in ("side-preview.png", "side-recording.png", "side-finished.png", "side-keyboard-visible.png"):
            self.pull_capture(filename.removesuffix(".png"), "files/" + filename, filename.removesuffix(".png") + "-199.png", package="app.voiceflow.fixture", run_as=True, required=False)

    def finalise(self):
        if self.logcat:
            try:
                self.health("final")
            except BaseException as failure:
                self.summary["final_health_collection_failure"] = {"class": type(failure).__name__, "message": str(failure)}
                self.unhealthy = True
            finally:
                self.logcat.terminate()
                try:
                    self.logcat.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.logcat.kill(); self.logcat.wait(timeout=10)
                self.logcat_out.close(); self.logcat_err.close()
            self.summary["runtime_interval_finished_utc"] = now()
            self.summary["continuous_logcat_exit_code"] = self.logcat.returncode
            # The collector was deliberately terminated after its live health check.
            complete = log_health((EVIDENCE / "native-live-all-199.log").read_text(errors="replace"))
            complete["last_live_health"] = self.summary.get("latest_health")
            write_json(EVIDENCE / "final-runtime-health.json", complete)
            self.summary["final_runtime_health"] = complete
        failed = [stage["name"] for stage in self.summary["stages"] if stage["status"] == "FAILED"]
        blocked = [stage["name"] for stage in self.summary["stages"] if stage["status"] == "BLOCKED"]
        captures_failed = [capture["name"] for capture in self.summary["screenshots"] if capture["required"] and capture["status"] != "PASS"]
        runtime_failure = self.unhealthy or self.summary.get("final_runtime_health", {}).get("app_or_fixture_matched_line_count", 0) > 0
        self.summary.update(finished_utc=now(), active_stage=None, failed_stages=failed, blocked_stages=blocked,
                            required_capture_failures=captures_failed, elapsed_seconds=round(time.monotonic() - self.started_monotonic, 3))
        if self.summary.get("runner_failure", {}).get("blocking") and not self.summary["installed"]:
            self.summary["status"] = "BLOCKED"
        elif self.summary.get("runner_failure") or failed or captures_failed or runtime_failure:
            self.summary["status"] = "FAILED"
        elif blocked:
            self.summary["status"] = "INCOMPLETE"
        else:
            self.summary["status"] = "PASS" if not self.summary.get("final_runtime_health", {}).get("unrelated_system_matched_line_count", 0) else "PASS_WITH_UNRELATED_SYSTEM_ERRORS"
        self.checkpoint()
        print(json.dumps({"status": self.summary["status"], "summary": str(EVIDENCE / "summary.json"),
                          "failed_stages": failed, "blocked_stages": blocked, "required_capture_failures": captures_failed}, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wall-budget-seconds", type=int, default=3300, help="Graceful bound below the existing queue's 3600-second limit")
    options = parser.parse_args()
    if options.wall_budget_seconds < 120 or options.wall_budget_seconds > 3450:
        parser.error("Use a wall budget between 120 and 3450 seconds for the existing queue")
    if (EVIDENCE / "summary.json").exists():
        raise SystemExit("Existing native checkpoint found; refusing to overwrite/retry it. Root must coordinate any explicit continuation separately.")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    runner = Runner(options.wall_budget_seconds)
    runner.checkpoint()
    try:
        runner.gates_and_install()
        runner.run_tests()
    except BaseException as failure:
        runner.summary["runner_failure"] = {"class": type(failure).__name__, "message": str(failure), "time": now(),
                                            "blocking": str(failure).startswith("BLOCKED:")}
        runner.checkpoint()
    finally:
        try:
            runner.finalise()
        except BaseException as failure:
            runner.summary["finalisation_failure"] = {"class": type(failure).__name__, "message": str(failure), "time": now()}
            runner.summary["status"] = "FAILED"; runner.checkpoint()
    return 0 if runner.summary["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
