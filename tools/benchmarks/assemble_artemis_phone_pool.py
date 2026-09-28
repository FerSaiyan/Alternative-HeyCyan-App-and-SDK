#!/usr/bin/env python3
"""Assemble audited, real-emulator observations without inventing train/test splits."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from audit_artemis_phone_episodes import audit


SOURCES = ("settings.jsonl", "clock.jsonl", "youtube_query.jsonl")


def assemble(folder: Path):
    rows = []
    manifest = {"version": 1, "source": "real_isolated_android_emulator_via_pinned_artemis",
                "taskerCompatible": False, "splitAssigned": False, "sources": {}}
    for file in SOURCES:
        path = folder / file
        if not path.exists():
            raise FileNotFoundError(f"Missing independent app source: {path}")
        content = path.read_bytes()
        entries = [json.loads(line) for line in content.splitlines() if line.strip()]
        if not entries:
            raise ValueError(f"No verified episodes in {path}")
        report = audit(entries)
        rows.extend(entries)
        manifest["sources"][file] = {"sha256": hashlib.sha256(content).hexdigest(),
                                     "episodes": report["episodes"], "decisions": report["decisions"],
                                     "screenFamilies": report["componentCount"]}
    ids = [row["episodeId"] for row in rows]
    if len(set(ids)) != sum(source["episodes"] for source in manifest["sources"].values()):
        raise ValueError("Episode IDs collided across apps, or an episode is incomplete")
    # Per-app checks above validate schema. Re-audit the union to detect a
    # renderer signature collision or cross-app episode reuse.
    report = audit(rows)
    manifest["audit"] = report
    manifest["summary"] = {"episodes": report["episodes"], "decisions": report["decisions"],
                           "uniqueScreenSignatures": report["uniqueScreenSignatures"],
                           "independentScreenFamilies": report["componentCount"],
                           "operations": report["operations"]}
    serialized = "".join(json.dumps(row, ensure_ascii=False) + "\n"
                         for row in sorted(rows, key=lambda r: (r["appFamily"], r["episodeId"], r["step"])))
    (folder / "pool.jsonl").write_text(serialized)
    manifest["poolSha256"] = hashlib.sha256(serialized.encode()).hexdigest()
    (folder / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return manifest["summary"], report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, default=Path("android/CyanBridge/training/artemis_real_v1"))
    args = parser.parse_args()
    summary, report = assemble(args.folder)
    print(json.dumps({**summary, "readyForIndependentSplit": report["readyForIndependentSplit"],
                      "readyForTaskerTraining": report["readyForTaskerTraining"]}, indent=2))
