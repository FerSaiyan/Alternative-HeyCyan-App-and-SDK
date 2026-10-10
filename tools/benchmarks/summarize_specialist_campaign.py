#!/usr/bin/env python3
"""Assemble compact development results from completed training/evaluation reports."""
import argparse
import json
from pathlib import Path

FIELDS = ("cases", "correct", "exactCallAccuracy", "positiveCases", "negativeCases", "positiveExactAccuracy", "negativeFalseActionRate", "negativeInvalidRate", "rawEmittedCallPrecision", "emittedRows", "invalidRows", "rawClassifierNegativeActionRate")


def compact(name, model, metrics):
    return dict(name=name, model=model, **{key: metrics.get(key) for key in FIELDS})


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    entries, base_seen = [], set()
    for path in sorted((args.root/"models").glob("laya-*/report.json")):
        if not path.parent.name.startswith(("laya-voice-", "laya-config-")):
            continue
        report = json.loads(path.read_text())
        specialist = "voice" if "voice" in path.parent.name else "config"
        if specialist not in base_seen:
            entries.append(compact(f"laya-{specialist}-base", "laya", report["before"]))
            base_seen.add(specialist)
        entries.append(compact(path.parent.name, "laya", report["after"]))
        entries.append(compact(path.parent.name+"-reversed", "laya", report["reversedOrder"]))
    for path in sorted((args.root/"reports").glob("*.json")):
        report = json.loads(path.read_text())
        if "exactCallAccuracy" in report:
            entries.append(compact(path.stem, "needle" if path.stem.startswith("needle") else "laya" if path.stem.startswith("laya") else "deterministic", report))
    result = {"results": entries, "calibrationEvaluated": False, "finalTestEvaluated": False,
              "limits": ["Open development results; generated counterfactuals and product templates, not recorded-ASR/Tasker evidence", "Voice predicts operation only, not source arguments or Android Intent execution", "Laya configuration exact calls include an independent host value binder; Needle generates typed arguments", "Needle depth ablation exports a 20L-trained adapter at 8L/4L; it is not independent reduced-depth fine-tuning", "Raw precision is uncalibrated and is not an automatic-action gate"]}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2)+"\n")
    print("| Variant | Exact | Positive exact | Negative false action | Raw call precision |")
    print("|---|---:|---:|---:|---:|")
    for row in entries:
        print(f"| {row['name']} | {row['correct']}/{row['cases']} ({row['exactCallAccuracy']:.1%}) | {row['positiveExactAccuracy']:.1%} | {row['negativeFalseActionRate']:.1%} | {row['rawEmittedCallPrecision']:.1%} |")


if __name__ == "__main__":
    main()
