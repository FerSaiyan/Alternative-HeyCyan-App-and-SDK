#!/usr/bin/env python3
"""Fail closed on stale Tasker Settings navigation exports (diagnostic only)."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ALLOWED_LABELS = frozenset(("Search Settings", "Network & internet", "Connected devices", "Apps",
                            "Notifications", "Sound & vibration", "Modes", "Display & touch",
                            "Wallpaper & style", "Internet", "SIMs", "Airplane mode",
                            "Hotspot & tethering", "Data Saver", "VPN"))


def audit(row: dict) -> dict:
    if row.get("source") != "TASKER_AUTOINPUT" or row.get("operation") != "TAP":
        raise ValueError("This is not a Tasker-observed tap")
    if row.get("device") != "authenticated_Pixel_9a":
        raise ValueError("Wrong declared Tasker device provenance")
    if row.get("outcomeVerified") is not True:
        raise ValueError("Unverified action")
    if row.get("targetLabel") != "Network & internet" or not isinstance(row.get("targetNodeIndex"), int):
        raise ValueError("Tap was not bound to the expected Tasker node")
    before, after = row["pre"], row["post"]
    labels = []
    for frame in (before, after):
        if frame.get("provider") != "TASKER_AUTOINPUT" or frame.get("packageName") != "com.android.settings":
            raise ValueError("A screen was not observed through Tasker in Settings")
        if not re.fullmatch(r"[a-f0-9]{64}", frame.get("snapshotSha256", "")):
            raise ValueError("Missing observation hash")
        if not isinstance(frame.get("createdAtMs"), int):
            raise ValueError("Missing observation timestamp")
        if set(frame) != {"provider", "packageName", "createdAtMs", "snapshotSha256", "allowlistedNodes"}:
            raise ValueError("Unexpected raw observation fields")
        nodes = frame["allowlistedNodes"]
        if any(set(node) != {"nodeIndex", "label"} or node["label"] not in ALLOWED_LABELS
               or not isinstance(node["nodeIndex"], int) for node in nodes):
            raise ValueError("Unexpected or sensitive node export")
        labels.append({node["label"] for node in nodes})
    if not {"Search Settings", "Network & internet"}.issubset(labels[0]) or {"Internet", "SIMs"} & labels[0]:
        raise ValueError("Pre-action screen was already the destination or not Settings home")
    if not {"Internet", "SIMs"}.issubset(labels[1]) or "Search Settings" in labels[1]:
        raise ValueError("Tasker did not observe the destination page")
    if before["createdAtMs"] >= after["createdAtMs"] or before["snapshotSha256"] == after["snapshotSha256"]:
        raise ValueError("Stale or unchanged Tasker observation")
    if row.get("targetNodeIndex") not in {node["nodeIndex"] for node in before["allowlistedNodes"]
                                          if node["label"] == "Network & internet"}:
        raise ValueError("Target not present in the pre-action allowlist")
    if row.get("observedDestinationMarkers") != ["Internet", "SIMs"]:
        raise ValueError("Destination markers are not the observed Tasker pair")
    if not 1 <= row.get("candidateCount", 0) <= 8 or row.get("actionToVerificationMs", 0) <= 0:
        raise ValueError("Missing candidate/latency measurements")
    if row.get("observationToVerificationMs") != after["createdAtMs"] - before["createdAtMs"]:
        raise ValueError("Observation-to-verification latency does not match timestamps")
    return {"source": row["source"], "episodeId": row["episodeId"],
            "outcomeVerifiedByTasker": True, "observationToVerificationMs": row["observationToVerificationMs"],
            "actionToVerificationMs": row["actionToVerificationMs"],
            "candidateCount": row["candidateCount"], "readyForTraining": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(json.loads(args.file.read_text())), indent=2))
