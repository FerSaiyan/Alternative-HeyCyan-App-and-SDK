#!/usr/bin/env python3
"""Calibrate bounded-action acceptance and compare real benchmark reports."""

from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)]


def summary(values: list[float]) -> dict:
    return {
        "p50": percentile(values, 0.50),
        "p90": percentile(values, 0.90),
        "p95": percentile(values, 0.95),
        "mean": statistics.fmean(values) if values else 0.0,
    }


def parse_android_log(path: Path) -> list[dict]:
    rows = []
    marker = "JEV_CAL_RESULT "
    for line in path.read_text(errors="replace").splitlines():
        if marker not in line:
            continue
        payload = line.split(marker, 1)[1]
        try:
            row = json.loads(payload)
        except json.JSONDecodeError:
            continue
        # A re-run can leave duplicates in logcat. Last observation wins.
        rows.append(row)
    deduplicated = {(row["model"], row["id"]): row for row in rows}
    return list(deduplicated.values())


def embedding_metrics(rows: list[dict], score_threshold: float, margin_threshold: float) -> dict:
    rows = [row for row in rows if not row.get("policyBoundary")]
    actionable = [row for row in rows if row["expected"] != "detailed_planner"]
    accepted = [
        row for row in rows
        if row["selected"] != "detailed_planner"
        and row["topScore"] >= score_threshold
        and row["margin"] >= margin_threshold
    ]
    correct = [row for row in accepted if row["selected"] == row["expected"]]
    return {
        "cases": len(rows),
        "rawTop1Accuracy": sum(row["selected"] == row["expected"] for row in rows) / max(1, len(rows)),
        "minimumTopScore": score_threshold,
        "minimumMargin": margin_threshold,
        "autoActions": len(accepted),
        "autoActionPrecision": len(correct) / max(1, len(accepted)),
        "actionableCoverage": len(correct) / max(1, len(actionable)),
        "fallbackRate": 1.0 - len(accepted) / max(1, len(rows)),
    }


def fit_embedding_thresholds(rows: list[dict], target_precision: float) -> tuple[float, float, dict]:
    model_rows = [row for row in rows if not row.get("policyBoundary")]
    scores = sorted({-1.0, 1.000001, *(float(row["topScore"]) for row in model_rows)})
    margins = sorted({0.0, 2.000001, *(float(row["margin"]) for row in model_rows)})
    options = []
    for score in scores:
        for margin in margins:
            result = embedding_metrics(model_rows, score, margin)
            if result["autoActions"] and result["autoActionPrecision"] >= target_precision:
                options.append(
                    (
                        result["actionableCoverage"],
                        result["autoActions"],
                        result["autoActionPrecision"],
                        -score,
                        -margin,
                        score,
                        margin,
                        result,
                    ),
                )
    if not options:
        result = embedding_metrics(model_rows, 1.000001, 2.000001)
        return 1.000001, 2.000001, result
    *_, score, margin, result = max(options)
    return score, margin, result


def per_locale_accuracy(rows: list[dict]) -> dict:
    grouped = defaultdict(list)
    for row in rows:
        if not row.get("policyBoundary"):
            grouped[row["locale"]].append(row)
    return {
        locale: sum(row["selected"] == row["expected"] for row in locale_rows) / len(locale_rows)
        for locale, locale_rows in sorted(grouped.items())
    }


def analyze_embedding_model(rows: list[dict], target_precision: float) -> dict:
    calibration = [row for row in rows if row["split"] == "calibration"]
    test = [row for row in rows if row["split"] == "test"]
    score, margin, calibration_metrics = fit_embedding_thresholds(calibration, target_precision)
    test_metrics = embedding_metrics(test, score, margin)
    measured = [row for row in rows if not row.get("policyBoundary")]
    return {
        "targetPrecision": target_precision,
        "calibration": calibration_metrics,
        "test": test_metrics,
        "latencyMs": {
            "cachedCandidatesQueryOnly": summary([float(row["queryMs"]) for row in measured]),
            "uncachedDynamicCandidates": summary([float(row["coldDecisionMs"]) for row in measured]),
        },
        "localeRawAccuracy": per_locale_accuracy(measured),
        "failures": [row for row in test if not row.get("policyBoundary") and row["selected"] != row["expected"]],
    }


def compact_needle_report(report: dict) -> dict:
    return {
        "modelBytes": report["modelBytes"],
        "modelLoadMs": report["modelLoadMs"],
        "calibration": report["calibration"],
        "test": report["test"],
        "latencyMs": report["latencyMs"],
        "localeRawAccuracy": report["localeRawAccuracy"],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-log", type=Path, required=True)
    parser.add_argument("--needle-report", type=Path)
    parser.add_argument("--output", type=Path, default=Path("/tmp/opencode/local-agent-calibration-comparison.json"))
    parser.add_argument("--target-precision", type=float, default=0.98)
    parser.add_argument("--expected-cases", type=int, default=169)
    args = parser.parse_args()

    rows = parse_android_log(args.android_log)
    grouped = defaultdict(list)
    for row in rows:
        grouped[row["model"]].append(row)
    if not grouped:
        raise SystemExit(f"No JEV_CAL_RESULT records found in {args.android_log}")
    incomplete = {name: len(model_rows) for name, model_rows in grouped.items() if len(model_rows) != args.expected_cases}
    if incomplete:
        raise SystemExit(f"Incomplete Android benchmark: expected {args.expected_cases} per model, got {incomplete}")

    report = {
        "datasetCases": args.expected_cases,
        "targetPrecision": args.target_precision,
        "embeddings": {
            name: analyze_embedding_model(model_rows, args.target_precision)
            for name, model_rows in sorted(grouped.items())
        },
    }
    if args.needle_report:
        report["needle"] = compact_needle_report(json.loads(args.needle_report.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    printable = {
        "datasetCases": report["datasetCases"],
        "embeddings": {
            name: {key: value for key, value in model.items() if key != "failures"}
            for name, model in report["embeddings"].items()
        },
        "needle": report.get("needle"),
    }
    print(json.dumps(printable, ensure_ascii=False, indent=2))
    print(f"report={args.output}")


if __name__ == "__main__":
    main()
