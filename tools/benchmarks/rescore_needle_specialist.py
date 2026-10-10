#!/usr/bin/env python3
"""Mechanically rescore saved native envelopes; no new inference or fitting."""
import argparse
import json
from pathlib import Path

from specialist_metrics import needle_proposals, rebind_calls, summarize


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--reports", type=Path, required=True)
    args = p.parse_args()
    for path in sorted(args.reports.glob("needle-*.json")):
        report = json.loads(path.read_text())
        if "exactCallAccuracy" not in report:
            continue
        for row in report["results"]:
            calls = needle_proposals(row["raw"])
            delivered = needle_proposals(row["raw"], include_suppressed=False)
            if report.get("opaqueNames"):
                calls = rebind_calls(calls, row["opaqueBinding"])
                delivered = rebind_calls(delivered, row["opaqueBinding"])
            row.update(calls=calls, deliveredCalls=delivered)
        report.update(summarize(report["results"]))
        report["engineDeliveredMetrics"] = summarize([dict(r, calls=r["deliveredCalls"]) for r in report["results"]])
        report["suppressedRows"] = sum(bool(r["raw"].get("suppressed_calls")) for r in report["results"])
        report["evaluationContract"] = "raw_proposals_including_native_suppressed_calls_v2"
        path.write_text(json.dumps(report, indent=2)+"\n")
        print(path.name, report["correct"], "/", report["cases"], "raw;", report["engineDeliveredMetrics"]["correct"], "delivered; suppressed", report["suppressedRows"])


if __name__ == "__main__":
    main()
