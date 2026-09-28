#!/usr/bin/env python3
"""Export one allowlisted Tasker Settings episode after an independent ADB UI check."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from audit_tasker_settings_episode import audit


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], check=True,
                          capture_output=True, text=True, timeout=50).stdout


def verify_external_ui(xml: str) -> dict:
    start = xml.find("<?xml")
    end = xml.find("</hierarchy>") + len("</hierarchy>")
    if start < 0 or end < len("</hierarchy>"):
        raise ValueError("ADB did not return a complete independent UI hierarchy")
    raw = xml[start:end]
    tree = ET.fromstring(raw)
    packages = {node.get("package") for node in tree.iter("node") if node.get("package")}
    titles = {node.get("content-desc") for node in tree.iter("node")
              if (node.get("resource-id") or "").endswith(":id/collapsing_toolbar")}
    labels = {node.get("text") for node in tree.iter("node")}
    markers = {"Internet", "SIMs", "Airplane mode"}
    if packages != {"com.android.settings"} or "Network & internet" not in titles or not markers.issubset(labels):
        raise ValueError("Independent Android UI does not confirm the Network page")
    return {"provider": "ADB_UIAUTOMATOR_DUMP", "verified": True,
            "observedTitle": "Network & internet", "observedMarkers": sorted(markers),
            "hierarchySha256": hashlib.sha256(raw.encode()).hexdigest(),
            "capturedAtUtcMs": int(time.time() * 1000)}


def export(row: dict, xml: str) -> tuple[dict, dict]:
    row = dict(row)
    # One prototype run stored action-to-verification time under the wrong
    # name. Preserve its measured value and derive the other clock span from
    # the two observed Tasker timestamps; do not claim it was remeasured.
    if "actionToVerificationMs" not in row:
        row["actionToVerificationMs"] = row["observationToVerificationMs"]
        row["observationToVerificationMs"] = row["post"]["createdAtMs"] - row["pre"]["createdAtMs"]
        row["timingCorrection"] = "prototype_action_timer_renamed; observation_span_derived_from_tasker_timestamps"
    report = audit(row)
    row["independentOutcomeVerification"] = verify_external_ui(xml)
    return row, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path,
                        default=Path("android/CyanBridge/training/tasker_real_v1/settings_network.json"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("Explicit emulator serial required")
    if adb(args.serial, "emu", "avd", "name").splitlines()[0] != "Pixel_9a":
        parser.error("Only authenticated Pixel_9a is accepted for this Tasker HIL export")
    if adb(args.serial, "shell", "getprop", "sys.boot_completed").strip() != "1":
        parser.error("The Pixel AVD has not booted")
    packages = adb(args.serial, "shell", "pm", "list", "packages").splitlines()
    for package in ("net.dinglisch.android.taskerm", "com.joaomgcd.autoinput"):
        if f"package:{package}" not in packages:
            parser.error(f"Missing real Tasker/AutoInput package {package}")
    row, report = export(json.loads(args.input.read_text()), adb(args.serial, "exec-out", "uiautomator", "dump", "/dev/tty"))
    if adb(args.serial, "emu", "avd", "name").splitlines()[0] != "Pixel_9a":
        parser.error("The AVD changed during independent outcome verification")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    pending = args.output.with_suffix(".tmp")
    pending.write_text(json.dumps(row, ensure_ascii=False, indent=2) + "\n")
    pending.replace(args.output)
    print(json.dumps({**report, "independentOutcomeVerification": True,
                      "externalHierarchySha256": row["independentOutcomeVerification"]["hierarchySha256"]}, indent=2))


if __name__ == "__main__":
    main()
