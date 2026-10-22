#!/usr/bin/env python3
"""Read-only raw exact-call development evaluation; no validation masking."""
import argparse
import ctypes
import hashlib
import json
import os
import statistics
import time
from pathlib import Path

from benchmark_needle_mobile_actions import load_engine, percentile
from bounded_specialist_contract import choice_metrics, choice_result, decode_choice
from specialist_metrics import needle_proposals, rebind_calls, summarize


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("library", "model", "data", "report"):
        p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--reverse", action="store_true")
    p.add_argument("--opaque-names", action="store_true", help="Replace tool names with fresh bound IDs; retain descriptions/argument schemas")
    p.add_argument("--schema-format", choices=("openai", "flat"), default="openai")
    p.add_argument("--empty-system", action="store_true", help="Pass an explicit empty system string, matching the SDK and training data")
    args = p.parse_args()
    os.environ["NEEDLE_TELEMETRY"] = "0"
    os.environ["DO_NOT_TRACK"] = "1"
    lib = load_engine(args.library.resolve())
    content = args.model.read_bytes()
    buffer = ctypes.create_string_buffer(content)
    if lib.needle_load(buffer, len(content)) != 0:
        raise RuntimeError("needle_load failed")
    rows = [json.loads(s) for s in (args.data/"development.needle.jsonl").read_text().splitlines()]
    results, latency = [], []
    for i, row in enumerate(rows):
        tools = list(reversed(row["tools"])) if args.reverse else row["tools"]
        binding = {}
        if args.opaque_names:
            renamed = []
            for j, t in enumerate(tools):
                name = f"action_{i}_{j}"
                binding[name] = t["name"]
                renamed.append(dict(t, name=name))
            tools = renamed
        schemas = [{"type": "function", "function": t} for t in tools] if args.schema_format == "openai" else tools
        started = time.perf_counter()
        system = row.get("system", "").encode()
        prefix = lib.needle_init(system if system or args.empty_system else None, json.dumps(schemas, separators=(",", ":")).encode(), None)
        calls, envelope = None, {}
        if prefix >= 0:
            output = ctypes.create_string_buffer(65536)
            rc = lib.needle_complete(row["query"].encode(), 192, output, len(output))
            if rc >= 0:
                try:
                    envelope = json.loads(output.value.decode())
                    calls = needle_proposals(envelope)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    pass
            else:
                envelope = {"engineError": rc}
        else:
            envelope = {"engineError": prefix}
        latency.append((time.perf_counter()-started)*1000)
        delivered = needle_proposals(envelope, include_suppressed=False)
        # Compare rebound semantics without masking invalid/unknown calls.
        if args.opaque_names and isinstance(calls, list):
            calls = rebind_calls(calls, binding)
            delivered = rebind_calls(delivered, binding)
        result = {"caseId": row["caseId"], "stage": row["stage"], "expected": row["answers"], "calls": calls, "deliveredCalls": delivered, "opaqueBinding": binding, "prefixTokens": prefix, "raw": envelope}
        if row["specialist"] == "bounded_choice":
            result.update(choice_result(row, decode_choice(row, calls)))
        results.append(result)
        lib.needle_reset()
        if (i+1) % 80 == 0:
            print(f"development {i+1}/{len(rows)}", flush=True)
    report = dict(summarize(results), model=str(args.model), modelSha256=hashlib.sha256(content).hexdigest(), reversed=args.reverse, opaqueNames=args.opaque_names, results=results, latencyMs={"p50": statistics.median(latency), "p95": percentile(latency, .95)}, calibrationOpened=False, finalTestOpened=False)
    report["engineDeliveredMetrics"] = summarize([dict(r, calls=r["deliveredCalls"]) for r in results])
    report["suppressedRows"] = sum(bool(r["raw"].get("suppressed_calls")) for r in results)
    report["evaluationContract"] = "raw_proposals_including_native_suppressed_calls_v2"
    report["schemaFormat"] = args.schema_format
    report["emptySystem"] = args.empty_system
    if rows[0]["specialist"] == "bounded_choice":
        report["boundedMetrics"] = choice_metrics(results)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps({k: v for k, v in report.items() if k != "results"}, indent=2), flush=True)


if __name__ == "__main__":
    main()
