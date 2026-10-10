#!/usr/bin/env python3
"""Bounded development probe of native flat/wrapped schemas and null/empty system."""
import argparse
import ctypes
import json
import os
from pathlib import Path

from benchmark_needle_mobile_actions import load_engine
from specialist_metrics import needle_proposals, summarize


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("library", "model", "data", "report"):
        p.add_argument("--"+name, type=Path, required=True)
    args = p.parse_args()
    os.environ["NEEDLE_TELEMETRY"] = "0"
    os.environ["DO_NOT_TRACK"] = "1"
    lib = load_engine(args.library.resolve())
    content = args.model.read_bytes()
    buffer = ctypes.create_string_buffer(content)
    assert lib.needle_load(buffer, len(content)) == 0
    all_rows = [json.loads(s) for s in (args.data/"development.needle.jsonl").read_text().splitlines()]
    # Twelve contiguous examples, including linked positives and counterfactuals;
    # this is a format diagnostic, not an independent accuracy estimate.
    rows = all_rows[:12]
    summaries = {}
    for format in ("openai", "flat"):
        for empty in (False, True):
            results = []
            for row in rows:
                tools = row["tools"] if format == "flat" else [{"type": "function", "function": t} for t in row["tools"]]
                prefix = lib.needle_init(b"" if empty else None, json.dumps(tools,separators=(",", ":")).encode(), None)
                envelope = {}
                if prefix >= 0:
                    output = ctypes.create_string_buffer(65536)
                    rc = lib.needle_complete(row["query"].encode(), 192, output, len(output))
                    if rc >= 0:
                        envelope = json.loads(output.value.decode())
                results.append({"caseId": row["caseId"], "stage": row["stage"], "expected": row["answers"], "calls": needle_proposals(envelope), "prefixTokens": prefix, "raw": envelope})
                lib.needle_reset()
            summaries[f"{format}_{'empty' if empty else 'null'}"] = dict(summarize(results), results=results)
    report = {"model": str(args.model), "formatProbe": summaries, "finalTestOpened": False}
    args.report.write_text(json.dumps(report,indent=2)+"\n")
    for name, result in summaries.items():
        print(name, result["correct"],"/",result["cases"],"prefixes",[r["prefixTokens"] for r in result["results"]],flush=True)


if __name__ == "__main__":
    main()
