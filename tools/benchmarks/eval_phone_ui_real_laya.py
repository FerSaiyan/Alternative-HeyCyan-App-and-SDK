#!/usr/bin/env python3
"""Read-only six-state Tasker shadow evaluation; never executes an Android action."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import sys
import time
from pathlib import Path


# Hand-reviewed next steps from a successful, independently checked YouTube HIL.
# At step 7 the observed video was playing; application evidence (not a UI tap)
# owns completion, so the correct bounded model choice is defer.
SUBGOAL_GOLD = {
    2: ("Open YouTube", "A"),
    3: ("Search YouTube for Linus Tech Tips", "A"),
    4: ("Choose the exact search suggestion linus tech tips", "A"),
    5: ("Open the channel Linus Tech Tips", "D"),
    6: ("Open a video from the Linus Tech Tips channel", "A"),
    7: ("Verify whether the video is playing; no tap is required if playback is observed", "G"),
}


def load_trace(path):
    rows = [json.loads(line.split("JEV_SHADOW_STATE=", 1)[1]) for line in path.read_text().splitlines()
            if "JEV_SHADOW_STATE=" in line]
    if [r["step"] for r in rows] != sorted(SUBGOAL_GOLD):
        raise ValueError("Requires the original six Tasker-observed states in order")
    if any(row["candidates"][-1]["key"] != "detailed_planner" for row in rows):
        raise ValueError("Missing the planner fallback in Tasker candidate set")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--reverse-options", action="store_true", help="Stress the observed order without rebinding nodes")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    trace = load_trace(args.trace)
    sys.path.insert(0, str(args.source.resolve()))
    import laya  # noqa: PLC0415

    agent = laya.load(str(args.checkpoint.resolve()), device=args.device)
    results = []
    for row in trace:
        subgoal, gold = SUBGOAL_GOLD[row["step"]]
        candidates = list(reversed(row["candidates"])) if args.reverse_options else row["candidates"]
        criteria = {candidate["label"]: candidate["description"] for candidate in candidates}
        question = {"next_action": {
            "type": "choice", "instructions": "Which observed control advances this current step? Defer if none is grounded.",
            "criteria": criteria,
        }}
        state = {"current_step": subgoal, "app": "YouTube" if "youtube" in row["package"] else "Launcher"}
        started = time.perf_counter()
        answer = agent.predict(state, question)["answers"]["next_action"]
        elapsed = (time.perf_counter() - started) * 1000
        results.append({"step": row["step"], "expected": gold, "selected": answer["choice"],
                        "correct": answer["choice"] == gold,
                        "selectedProbability": float(answer["probabilities"][answer["choice"]]),
                        "ms": elapsed, "offeredCount": len(criteria)})
    report = {"mode": "read_only_single_episode_Tasker_shadow", "checkpoint": str(args.checkpoint),
              "reversedOptions": args.reverse_options,
              "traceSha256": hashlib.sha256(args.trace.read_bytes()).hexdigest(),
              "cases": len(results), "correct": sum(row["correct"] for row in results),
              "medianMs": statistics.median(row["ms"] for row in results),
              "limits": "Six sequential screens from one YouTube task; not independent phone episodes",
              "rows": results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("cases", "correct", "medianMs", "rows")}, indent=2))


if __name__ == "__main__":
    main()
