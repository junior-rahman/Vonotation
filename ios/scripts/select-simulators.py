#!/usr/bin/env python3
"""Select an available native iPhone and iPad, without assuming model names."""
import json
import re
import subprocess
import sys

data = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"]))
available = []
for runtime, devices in data["devices"].items():
    if ".iOS-" not in runtime:
        continue
    version = tuple(int(x) for x in re.findall(r"\d+", runtime.rsplit("iOS-", 1)[1]))
    for device in devices:
        if device.get("isAvailable"):
            available.append((version, runtime, device))
selected = {}
for family in ("iPhone", "iPad"):
    candidates = [x for x in available if x[2]["name"].startswith(family)]
    if not candidates:
        raise SystemExit(f"No available {family} simulator. Install an iOS runtime and create this device family in Xcode first.")
    candidates.sort(key=lambda x: (x[0], x[2]["name"]), reverse=True)
    version, runtime, device = candidates[0]
    selected[family] = {"udid": device["udid"], "name": device["name"], "runtime": runtime}
json.dump(selected, sys.stdout, indent=2)
print()
