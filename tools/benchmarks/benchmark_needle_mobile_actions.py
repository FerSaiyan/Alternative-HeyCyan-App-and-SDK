#!/usr/bin/env python3
"""Benchmark Needle 3 against CyanBridge's bounded mobile-action corpus.

This uses only Python's standard library and Needle's published C ABI. Model
weights and the shared library remain external to the repository.
"""

from __future__ import annotations

import argparse
import ctypes
import json
import math
import os
import statistics
import time
from collections import defaultdict
from pathlib import Path


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)]


def metrics(rows: list[dict], threshold: float) -> dict:
    model_rows = [row for row in rows if not row["policyBoundary"]]
    actionable = [row for row in model_rows if row["expected"] != "detailed_planner"]
    structurally_valid = [row for row in model_rows if row["structuralValid"]]
    auto = [
        row for row in model_rows
        if row["structuralValid"]
        and row["selected"] != "detailed_planner"
        and row["confidence"] >= threshold
    ]
    correct_auto = [row for row in auto if row["selected"] == row["expected"]]
    return {
        "cases": len(model_rows),
        "rawTop1Accuracy": sum(row["selected"] == row["expected"] for row in model_rows) / max(1, len(model_rows)),
        "exactlyOneCallRate": len(structurally_valid) / max(1, len(model_rows)),
        "argumentExactRate": sum(row["argumentCorrect"] for row in structurally_valid) / max(1, len(structurally_valid)),
        "autoActionThreshold": threshold,
        "autoActions": len(auto),
        "autoActionPrecision": len(correct_auto) / max(1, len(auto)),
        "actionableCoverage": len(correct_auto) / max(1, len(actionable)),
        "fallbackRate": 1.0 - len(auto) / max(1, len(model_rows)),
    }


def fit_threshold(rows: list[dict], target_precision: float) -> tuple[float, dict]:
    values = sorted({0.0, 1.000001, *(row["confidence"] for row in rows if not row["policyBoundary"])})
    options = []
    for threshold in values:
        result = metrics(rows, threshold)
        if result["autoActions"] == 0 or result["autoActionPrecision"] >= target_precision:
            options.append((result["actionableCoverage"], result["autoActionPrecision"], -threshold, threshold, result))
    _, _, _, threshold, result = max(options)
    return threshold, result


def load_engine(library: Path):
    lib = ctypes.CDLL(str(library))
    lib.needle_load.argtypes = [ctypes.c_void_p, ctypes.c_uint64]
    lib.needle_load.restype = ctypes.c_int
    lib.needle_init.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p]
    lib.needle_init.restype = ctypes.c_int
    lib.needle_complete.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    lib.needle_complete.restype = ctypes.c_int
    lib.needle_reset.argtypes = []
    lib.needle_reset.restype = None
    return lib


def tool_schema(candidate: dict) -> dict:
    properties = {}
    required = []
    argument = candidate.get("argument")
    if argument:
        properties[argument["name"]] = {"type": "string", "description": argument["description"]}
        required.append(argument["name"])
    return {
        "type": "function",
        "function": {
            "name": candidate["id"],
            "description": candidate["description"],
            "parameters": {"type": "object", "properties": properties, "required": required},
        },
    }


def normalize_argument(value: str) -> str:
    return " ".join(value.strip().strip("'\"“”‘’«»。. ").casefold().split())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path("android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl"))
    parser.add_argument("--model", type=Path, default=Path("/tmp/opencode/needle3/needle3.cact"))
    parser.add_argument("--library", type=Path, default=Path("/tmp/opencode/needle3/wheel/needle/libneedle3.so"))
    parser.add_argument("--output", type=Path, default=Path("/tmp/opencode/needle3/mobile-actions-benchmark.json"))
    parser.add_argument("--target-precision", type=float, default=0.98)
    parser.add_argument("--max-tokens", type=int, default=256)
    args = parser.parse_args()

    os.environ["NEEDLE_TELEMETRY"] = "0"
    os.environ["DO_NOT_TRACK"] = "1"
    cases = [json.loads(line) for line in args.dataset.read_text().splitlines() if line.strip()]
    lib = load_engine(args.library.resolve())
    model_bytes = args.model.read_bytes()
    model_buffer = ctypes.create_string_buffer(model_bytes)
    started = time.perf_counter()
    load_result = lib.needle_load(model_buffer, len(model_bytes))
    model_load_ms = (time.perf_counter() - started) * 1000
    if load_result != 0:
        raise RuntimeError(f"needle_load failed: {load_result}")

    results = []
    for index, case in enumerate(cases):
        expected = case["expected"]["candidateId"]
        if expected == "policy_block":
            results.append({
                "id": case["id"], "split": case["split"], "locale": case["locale"],
                "expected": expected, "selected": None, "confidence": 0.0,
                "structuralValid": True, "argumentCorrect": True, "policyBoundary": True,
                "initMs": 0.0, "completeMs": 0.0, "totalMs": 0.0,
            })
            continue

        tools = json.dumps([tool_schema(candidate) for candidate in case["candidates"]], ensure_ascii=False, separators=(",", ":")).encode()
        started = time.perf_counter()
        init_result = lib.needle_init(None, tools, None)
        init_ms = (time.perf_counter() - started) * 1000
        if init_result < 0:
            raise RuntimeError(f"needle_init failed for {case['id']}: {init_result}")

        prompt = f"User goal: {case['goal']}\nChoose exactly one safe next action.".encode()
        output = ctypes.create_string_buffer(65536)
        started = time.perf_counter()
        rc = lib.needle_complete(prompt, args.max_tokens, output, len(output))
        complete_ms = (time.perf_counter() - started) * 1000
        if rc < 0:
            envelope = {"function_calls": [], "confidence": 0.0, "error_code": str(rc)}
        else:
            try:
                envelope = json.loads(output.value.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                envelope = {
                    "function_calls": [],
                    "confidence": 0.0,
                    "error_code": "invalid_engine_envelope",
                    "reasoning": f"{type(error).__name__}: {error}; raw={output.value[:500]!r}",
                }
        calls = envelope.get("function_calls") or []
        known = {candidate["id"] for candidate in case["candidates"]}
        structural = len(calls) == 1 and calls[0].get("name") in known
        selected = calls[0]["name"] if structural else None
        argument_correct = True
        expected_argument = case["expected"].get("argumentText")
        if structural and expected_argument is not None:
            candidate = next(candidate for candidate in case["candidates"] if candidate["id"] == selected)
            argument = candidate.get("argument")
            actual = (calls[0].get("arguments") or {}).get(argument["name"], "") if argument else ""
            argument_correct = normalize_argument(str(actual)) == normalize_argument(expected_argument)
        result = {
            "id": case["id"], "split": case["split"], "locale": case["locale"],
            "expected": expected, "selected": selected,
            "confidence": float(envelope.get("confidence") or 0.0),
            "structuralValid": structural, "argumentCorrect": argument_correct,
            "policyBoundary": False, "initMs": init_ms, "completeMs": complete_ms,
            "totalMs": init_ms + complete_ms, "callCount": len(calls),
            "reasoning": envelope.get("reasoning"), "errorCode": envelope.get("error_code"),
        }
        results.append(result)
        lib.needle_reset()
        if (index + 1) % 20 == 0:
            print(f"Needle progress {index + 1}/{len(cases)}")

    calibration = [row for row in results if row["split"] == "calibration"]
    test = [row for row in results if row["split"] == "test"]
    threshold, calibration_metrics = fit_threshold(calibration, args.target_precision)
    test_metrics = metrics(test, threshold)
    model_results = [row for row in results if not row["policyBoundary"]]
    locale_accuracy = {}
    by_locale = defaultdict(list)
    for row in model_results:
        by_locale[row["locale"]].append(row)
    for locale, rows in sorted(by_locale.items()):
        locale_accuracy[locale] = sum(row["selected"] == row["expected"] for row in rows) / len(rows)
    latencies = [row["totalMs"] for row in model_results]
    report = {
        "engine": "Cactus-Compute/needle3",
        "dataset": str(args.dataset),
        "modelBytes": len(model_bytes),
        "modelLoadMs": model_load_ms,
        "maxTokens": args.max_tokens,
        "targetPrecision": args.target_precision,
        "calibration": calibration_metrics,
        "test": test_metrics,
        "latencyMs": {
            "total": {
                "p50": percentile(latencies, 0.50),
                "p90": percentile(latencies, 0.90),
                "p95": percentile(latencies, 0.95),
                "mean": statistics.fmean(latencies),
            },
            "dynamicToolInit": {
                "p50": percentile([row["initMs"] for row in model_results], 0.50),
                "p90": percentile([row["initMs"] for row in model_results], 0.90),
                "p95": percentile([row["initMs"] for row in model_results], 0.95),
                "mean": statistics.fmean(row["initMs"] for row in model_results),
            },
            "completion": {
                "p50": percentile([row["completeMs"] for row in model_results], 0.50),
                "p90": percentile([row["completeMs"] for row in model_results], 0.90),
                "p95": percentile([row["completeMs"] for row in model_results], 0.95),
                "mean": statistics.fmean(row["completeMs"] for row in model_results),
            },
        },
        "localeRawAccuracy": locale_accuracy,
        "failures": [row for row in results if not row["policyBoundary"] and row["selected"] != row["expected"]],
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({key: report[key] for key in ("modelLoadMs", "calibration", "test", "latencyMs", "localeRawAccuracy")}, ensure_ascii=False, indent=2))
    print(f"report={args.output}")


if __name__ == "__main__":
    main()
