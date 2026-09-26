#!/usr/bin/env python3
"""Opt-in Laya comparison on CyanBridge's frozen bounded-action corpus.

Use a pre-provisioned checkpoint and a non-base Python environment containing
torch, transformers and safetensors. Nothing is downloaded by this script.
The Laya repository is imported from --source, pinned by the caller.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import subprocess
import sys
import time
from pathlib import Path


def evaluate(rows: list[dict], top: float, margin: float) -> dict:
    eligible = [r for r in rows if not r["policyBoundary"]]
    accepted = [
        r for r in eligible
        if r["selected"] != "detailed_planner"
        and r["topScore"] >= top and r["margin"] >= margin
    ]
    correct = [r for r in accepted if r["selected"] == r["expected"]]
    actionable = [r for r in eligible if r["expected"] != "detailed_planner"]
    return {
        "cases": len(eligible),
        "rawTop1Accuracy": sum(r["selected"] == r["expected"] for r in eligible) / max(1, len(eligible)),
        "autoActions": len(accepted),
        "autoActionPrecision": len(correct) / max(1, len(accepted)),
        "actionableCoverage": len(correct) / max(1, len(actionable)),
    }


def fit(rows: list[dict], precision: float) -> tuple[float, float]:
    eligible = [r for r in rows if not r["policyBoundary"]]
    scores = sorted({0.0, 1.000001, *(r["topScore"] for r in eligible)})
    margins = sorted({0.0, 1.000001, *(r["margin"] for r in eligible)})
    choices = []
    for top in scores:
        for margin in margins:
            result = evaluate(eligible, top, margin)
            if result["autoActions"] and result["autoActionPrecision"] >= precision:
                choices.append((result["actionableCoverage"], result["autoActions"], -top, -margin, top, margin))
    if not choices:
        return 1.000001, 1.000001
    *_, top, margin = max(choices)
    return top, margin


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Local Laya checkout (not cloned here)")
    parser.add_argument("--checkpoint", type=Path, required=True, help="Pre-provisioned Laya checkpoint directory")
    parser.add_argument("--device", choices=["cpu", "cuda"], default="cpu")
    parser.add_argument("--dataset", type=Path, default=Path("android/CyanBridge/calibration/local_agent_mobile_actions_v1.jsonl"))
    parser.add_argument("--output", type=Path, required=True, help="JSON report path outside Git")
    parser.add_argument("--target-precision", type=float, default=0.98)
    parser.add_argument("--locale", action="append", help="Restrict to a locale (e.g. en). Partial coverage; no release verdict")
    parser.add_argument("--limit", type=int, default=0, help="Smoke-test rows only; no release verdict")
    args = parser.parse_args()
    if not (args.source / "laya" / "__init__.py").is_file() or not (args.checkpoint / "model.safetensors").is_file():
        parser.error("--source and --checkpoint must be existing local files; this script never downloads weights")
    if not 0 < args.target_precision <= 1 or args.limit < 0:
        parser.error("precision must be (0, 1] and limit must be nonnegative")
    cases = [json.loads(line) for line in args.dataset.read_text().splitlines() if line.strip()]
    if args.locale:
        cases = [case for case in cases if case["locale"] in args.locale]
    if args.limit:
        cases = cases[:args.limit]
    sys.path.insert(0, str(args.source.resolve()))
    import laya  # noqa: PLC0415 — intentionally loads from the explicit source checkout
    import torch

    revision = subprocess.check_output(
        ["git", "-C", str(args.source.resolve()), "rev-parse", "HEAD"], text=True,
    ).strip()
    digest = hashlib.sha256()
    with (args.checkpoint / "model.safetensors").open("rb") as checkpoint_file:
        while chunk := checkpoint_file.read(4 * 1024 * 1024):
            digest.update(chunk)
    checkpoint_sha256 = digest.hexdigest()

    started = time.perf_counter()
    agent = laya.load(str(args.checkpoint.resolve()), device=args.device)
    load_ms = (time.perf_counter() - started) * 1000
    rows = []
    for i, case in enumerate(cases):
        expected = case["expected"]["candidateId"]
        row = {
            "id": case["id"], "split": case["split"], "locale": case["locale"],
            "expected": expected, "policyBoundary": expected == "policy_block",
        }
        if row["policyBoundary"]:
            rows.append(row)
            continue
        candidates = case["candidates"]
        criteria = {candidate["id"]: candidate["description"] for candidate in candidates}
        # The model selects a bounded action only; literal arguments are copied
        # from the user later and prose is delegated to the separate local LLM.
        questions = {"next_action": {
            "type": "choice",
            "instructions": "Which one safe action should the phone automation agent take next?",
            "criteria": criteria,
        }}
        state = {
            "goal": case["goal"], "current_app": case["observation"]["packageName"],
            "screen": case["observation"]["screenText"],
        }
        t0 = time.perf_counter()
        answer = agent.predict(state, questions)["answers"]["next_action"]
        if args.device == "cuda":
            torch.cuda.synchronize()
        elapsed_ms = (time.perf_counter() - t0) * 1000
        probs = answer["probabilities"]
        ranked = sorted((float(p) for p in probs.values()), reverse=True)
        row.update(
            selected=answer["choice"], topScore=ranked[0],
            margin=ranked[0] - ranked[1], confidence=answer.get("confidence"),
            actProbability=answer.get("action", {}).get("act_probability"),
            probabilities=probs, decisionMs=elapsed_ms,
        )
        rows.append(row)
        if (i + 1) % 20 == 0:
            print(f"Laya progress {i + 1}/{len(cases)}", flush=True)
    calibration = [r for r in rows if r["split"] == "calibration"]
    heldout = [r for r in rows if r["split"] == "test"]
    top, margin = fit(calibration, args.target_precision)
    latency = sorted(r["decisionMs"] for r in rows if not r["policyBoundary"])
    report = {
        "engine": "laya", "source": str(args.source.resolve()), "sourceRevision": revision,
        "checkpoint": str(args.checkpoint.resolve()), "checkpointSha256": checkpoint_sha256,
        "dataset": str(args.dataset), "device": args.device, "modelLoadMs": load_ms,
        "partial": bool(args.limit or args.locale), "locales": args.locale or "all",
        "targetPrecision": args.target_precision,
        "fittedOnCalibration": {"minimumTopScore": top, "minimumMargin": margin},
        "calibration": evaluate(calibration, top, margin), "test": evaluate(heldout, top, margin),
        "latencyMs": {
            "p50": statistics.median(latency) if latency else None,
            "p95": latency[round((len(latency) - 1) * .95)] if latency else None,
        },
        "rows": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({k: report[k] for k in ("partial", "fittedOnCalibration", "calibration", "test", "latencyMs")}, indent=2))
    print(f"Report: {args.output}")


if __name__ == "__main__":
    main()
