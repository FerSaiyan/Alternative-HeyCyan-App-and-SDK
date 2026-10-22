#!/usr/bin/env python3
"""Score LiteRtBoundedDecisionEvalTest device output against bounded canonical turns.

The device never sees expected choices; this joins its per-case probabilities
with development.canonical.jsonl and reuses the bounded contract metrics. Margin
coverage is descriptive on development data, not a calibrated threshold.
"""
import argparse
import hashlib
import json
from pathlib import Path

from bounded_specialist_contract import choice_metrics, choice_result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--canonical", type=Path, required=True)
    p.add_argument("--device-results", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    args = p.parse_args()
    cases = {r["caseId"]: r for r in map(json.loads, args.canonical.read_text().splitlines())}
    rows = [json.loads(s) for s in args.device_results.read_text().splitlines() if s.strip()]
    header = next(r for r in rows if r["kind"] == "header")
    footer = next((r for r in rows if r["kind"] == "footer"), None)
    scored = [r for r in rows if r["kind"] == "case"]
    results, margins, times = [], [], []
    for row in scored:
        case = cases[row["caseId"]]
        # Device option order must be the canonical host-bound order.
        assert row["optionIds"] == list(case["turn"]["options"]), row["caseId"]
        result = choice_result(case, row["selected"])
        results.append(dict(result, margin=row["margin"], probabilities=row["probabilities"], raw=row["raw"]))
        margins.append((row["margin"], result["selected"] == result["expectedChoice"]))
        times.append(row["decisionMs"])
    coverage = []
    for threshold in (0.0, 0.1, 0.25, 0.5, 0.75, 0.9):
        kept = [ok for margin, ok in margins if margin >= threshold]
        coverage.append({"minMargin": threshold, "accepted": len(kept), "acceptedCorrect": sum(kept),
                         "acceptedPrecision": sum(kept) / len(kept) if kept else None})
    times.sort()
    report = {
        "runtime": "LiteRT-LM 0.16.0 + shared-decision patch (delimiter+label scoring)",
        "header": header, "footer": footer,
        "canonicalSha256": hashlib.sha256(args.canonical.read_bytes()).hexdigest(),
        "deviceResultsSha256": hashlib.sha256(args.device_results.read_bytes()).hexdigest(),
        "complete": footer is not None and len(scored) == len(cases),
        "oracleTurns": dict(choice_metrics(results), results=results),
        "marginCoverage": coverage,
        "decisionMs": {"p50": times[len(times) // 2], "p95": times[int(len(times) * 0.95)], "calls": len(times)} if times else None,
        "calibrationOpened": False, "finalTestOpened": False, "hostVerifiedMutation": False,
        "limits": "Per-turn choices on generated host state; flat view so a turn is a full episode. No action/readback.",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    m = report["oracleTurns"]
    print(json.dumps({k: m[k] for k in ("cases", "correct", "substantiveChoiceAccuracy", "controlFalseActionRate", "invalidChoices")}))
    print(json.dumps(coverage))
    print("decisionMs", report["decisionMs"], "complete", report["complete"])


if __name__ == "__main__":
    main()
