#!/usr/bin/env python3
"""Read-only exact-call evaluation for synthetic phone UI Needle examples."""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import statistics
import time
from collections import Counter
from pathlib import Path

from benchmark_needle_mobile_actions import load_engine, percentile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--split", choices=("validation", "test"), default="test")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    os.environ["NEEDLE_TELEMETRY"] = "0"
    os.environ["DO_NOT_TRACK"] = "1"
    lib = load_engine(args.library.resolve())
    model_bytes = args.model.read_bytes()
    buffer = ctypes.create_string_buffer(model_bytes)
    started = time.perf_counter()
    if lib.needle_load(buffer, len(model_bytes)) != 0:
        raise RuntimeError("needle_load failed")
    load_ms = (time.perf_counter() - started) * 1000
    rows = [json.loads(line) for line in (args.data / f"{args.split}.needle.jsonl").read_text().splitlines()]
    stages, latency, failures = {}, [], []
    errors = Counter()
    call_counts = Counter()
    for i, row in enumerate(rows):
        schemas = [{"type": "function", "function": t} for t in row["tools"]]
        system = row["system"].encode()
        started = time.perf_counter()
        prefix_tokens = lib.needle_init(system, json.dumps(schemas, separators=(",", ":")).encode(), None)
        if prefix_tokens < 0:
            raise RuntimeError(f"needle_init failed on {row['caseId']}: {prefix_tokens}")
        output = ctypes.create_string_buffer(65536)
        rc = lib.needle_complete(row["query"].encode(), 192, output, len(output))
        elapsed = (time.perf_counter() - started) * 1000
        if rc < 0:
            errors[f"engine_{rc}"] += 1
            calls = None
        else:
            try:
                envelope = json.loads(output.value.decode())
                calls = envelope.get("function_calls")
            except (json.JSONDecodeError, UnicodeDecodeError):
                calls = None
                errors["invalid_response"] += 1
        expected = row["answers"]
        # No-call is a valid, required answer on unsupported/ambiguous screens.
        correct = calls == expected if isinstance(calls, list) else False
        call_counts[str(len(calls)) if isinstance(calls, list) else "invalid"] += 1
        stage = row["stage"]
        total, good = stages.get(stage, (0, 0))
        stages[stage] = (total + 1, good + correct)
        latency.append(elapsed)
        if not correct:
            failures.append({"caseId": row["caseId"], "expected": expected,
                             "calls": calls, "prefixTokens": prefix_tokens})
        lib.needle_reset()
        if (i + 1) % 32 == 0:
            print(f"{i+1}/{len(rows)} synthetic rows", flush=True)
    result = {"modelSha256": hashlib.sha256(model_bytes).hexdigest(),
              "model": str(args.model), "split": args.split,
              "label": "synthetic-only exact calls; not a Tasker success rate",
              "cases": len(rows), "correct": len(rows) - len(failures),
              "accuracy": (len(rows) - len(failures)) / len(rows),
              "stages": {stage: {"cases": count, "correct": good}
                         for stage, (count, good) in stages.items()},
              "callCountHistogram": call_counts, "errors": errors, "loadMs": load_ms,
              "latencyMs": {"p50": statistics.median(latency), "p95": percentile(latency, .95)},
              "failures": failures}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: result[k] for k in ("cases", "correct", "accuracy", "stages", "latencyMs")}, indent=2))


if __name__ == "__main__":
    main()
