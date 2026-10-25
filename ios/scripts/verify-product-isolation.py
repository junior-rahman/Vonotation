#!/usr/bin/env python3
"""Verify identities from the actual SDK-built app bundles after both native runs."""
import json
from pathlib import Path
import plistlib
import sys

original, notes, output = map(Path, sys.argv[1:])
products = []
for app, expected_id, expected_name, expected_scheme in (
    (original, "app.vonotation.ios", "Voitation", "vonotation"),
    (notes, "app.vonotation.notes.ios", "Vocading", "vononotes"),
):
    info = plistlib.loads((app / "Info.plist").read_bytes())
    schemes = [s for row in info.get("CFBundleURLTypes", []) for s in row.get("CFBundleURLSchemes", [])]
    assert info["CFBundleIdentifier"] == expected_id, info
    assert info["CFBundleDisplayName"] == expected_name, info
    assert schemes == [expected_scheme], schemes
    assert not (app / "embedded.mobileprovision").exists(), "Native CI must not include provisioning credentials"
    products.append({"app": app.name, "bundle_identifier": expected_id, "url_schemes": schemes})
(output / "PRODUCT-ISOLATION.json").write_text(json.dumps({"products": products, "scope": "Actual Apple SDK bundle identity and product-specific native tests; no shared app group or data migration is configured."}, indent=2) + "\n")
print("Verified independent Voitation and Vocading bundle identities.")
