#!/usr/bin/env python3
"""Replay opt-in Tasker-observed YouTube choices through real Laya and Needle.

The models are read-only: this script has no ADB, CyanBridge, or Tasker execute
API. Weights and the report live outside Git and default CI.
"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import statistics
import sys
import time
from pathlib import Path

from benchmark_needle_mobile_actions import load_engine
from phone_accessibility_frame import phone_frame


def read_trace(path: Path) -> list[dict]:
    marker = "JEV_SHADOW_STATE="
    records = []
    for line in path.read_text().splitlines():
        if marker in line:
            record = json.loads(line.split(marker, 1)[1])
            if "Linus Tech Tips" not in record.get("goal", ""):
                raise ValueError("Only the explicitly opted-in YouTube HIL may be replayed")
            records.append(record)
    if not records:
        raise ValueError(f"No opt-in Tasker-observed shadow states in {path}")
    return records


def eligible_options(record: dict, max_actions: int = 3) -> list[dict]:
    """Use <=3 bounded actions and an explicit escalation option for both models."""
    candidate_list = record["candidates"]
    actions = [option for option in candidate_list if option["key"] in
               {"open_app", "click_node", "type_text", "scroll", "press_back"}][:max_actions]
    return actions + [{"label": "?", "key": "detailed_planner",
                       "description": "Defer this step to the detailed planner"}]


def tool_name(option: dict) -> str:
    return "defer_to_planner" if option["key"] == "detailed_planner" else "action_" + option["label"].lower()


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * fraction)] if ordered else 0.0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--source", type=Path, default=Path("/tmp/opencode/laya-current"))
    parser.add_argument("--checkpoint", type=Path, default=Path("/tmp/opencode/laya-multilingual"))
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--needle-library", type=Path, default=Path("/tmp/opencode/needle3/wheel/needle/libneedle3.so"))
    parser.add_argument("--needle-model", type=Path, default=Path("/tmp/opencode/needle3/needle3.cact"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0, help="Explicit smoke-test cap")
    parser.add_argument("--max-actions", type=int, choices=(2, 3), default=3,
                        help="Offer two or three legal actions plus planner escalation")
    parser.add_argument("--laya-all", action="store_true",
                        help="Give Laya up to seven actions plus escalation (Needle stays capped)")
    parser.add_argument("--frame", choices=("legacy", "compact"), default="legacy",
                        help="Compare existing presentation to documentation-aligned one-step UI tools")
    parser.add_argument("--step", type=int, help="Inspect one observed step; not an accuracy estimate")
    parser.add_argument("--reverse-options", action="store_true",
                        help="Order-sensitivity probe; keep original tool names and node bindings")
    args = parser.parse_args()
    if args.frame == "compact" and args.laya_all:
        parser.error("--laya-all is only meaningful with --frame legacy")

    os.environ["NEEDLE_TELEMETRY"] = "0"
    os.environ["DO_NOT_TRACK"] = "1"
    records = read_trace(args.trace)
    if args.step is not None:
        records = [record for record in records if record["step"] == args.step]
        if len(records) != 1:
            parser.error("--step must uniquely identify one Tasker observation")
    if args.limit:
        records = records[:args.limit]

    sys.path.insert(0, str(args.source.resolve()))
    import laya  # noqa: PLC0415 - caller pins the local source checkout
    import torch  # noqa: PLC0415

    start = time.perf_counter()
    laya_agent = laya.load(str(args.checkpoint.resolve()), device=args.device)
    laya_load_ms = (time.perf_counter() - start) * 1000

    needle_lib = load_engine(args.needle_library.resolve())
    needle_bytes = args.needle_model.read_bytes()
    needle_buffer = ctypes.create_string_buffer(needle_bytes)
    start = time.perf_counter()
    rc = needle_lib.needle_load(needle_buffer, len(needle_bytes))
    needle_load_ms = (time.perf_counter() - start) * 1000
    if rc != 0:
        raise RuntimeError(f"needle_load failed: {rc}")

    rows = []
    for record in records:
        frame = phone_frame(record, args.max_actions) if args.frame == "compact" else None
        options = (frame["tools"] + [{"label": "?", "key": "detailed_planner",
                                      "name": "defer_to_planner",
                                      "description": "Defer to the detailed planner"}]
                   if frame else eligible_options(record, args.max_actions))
        if args.reverse_options and frame:
            options = list(reversed(options[:-1])) + options[-1:]
        if frame and not frame["tools"]:
            rows.append({"step": record["step"], "package": record["package"],
                         "phase": frame["phase"], "offered": {}, "layaOffered": {},
                         "omittedActionCount": len(record["candidates"]),
                         "laya": {"choice": "defer_to_planner", "topScore": 0.0,
                                  "margin": 0.0, "ms": 0.0},
                         "needle": {"choice": None, "callCount": 0,
                                    "confidence": None, "ms": 0.0}})
            continue
        keys = {(option["name"] if frame else tool_name(option)): option for option in options}
        laya_keys = ({tool_name(option): option for option in eligible_options(record, 7)}
                     if args.laya_all else keys)
        criteria = {name: option["description"] for name, option in laya_keys.items()}
        state = ({"current_step": frame["request"], "app": frame["app"],
                  "screen_phase": frame["phase"]} if frame else
                 {"goal": record["goal"], "current_app": record["package"],
                  "screen": record["state"]})

        start = time.perf_counter()
        laya_answer = laya_agent.predict(state, {"next_action": {
            "type": "choice",
            "instructions": ("Which observed control advances the current step? Defer if none."
                             if frame else "Which one action should the phone agent take next? Defer if uncertain."),
            "criteria": criteria,
        }})["answers"]["next_action"]
        if args.device == "cuda":
            torch.cuda.synchronize()
        laya_ms = (time.perf_counter() - start) * 1000
        probabilities = laya_answer["probabilities"]
        ranked = sorted((float(score) for score in probabilities.values()), reverse=True)

        needle_keys = ({name: option for name, option in keys.items()
                        if name != "defer_to_planner"} if frame else keys)
        schemas = [{"type": "function", "function": {
            "name": name, "description": option["description"],
            "parameters": {"type": "object", "properties": {}, "required": []},
        }} for name, option in needle_keys.items()]
        start = time.perf_counter()
        system_facts = (f"device: phone; app: {frame['app']}".encode() if frame else None)
        init_rc = needle_lib.needle_init(system_facts, json.dumps(schemas).encode(), None)
        init_ms = (time.perf_counter() - start) * 1000
        if init_rc < 0:
            raise RuntimeError(f"needle_init failed at step {record['step']}: {init_rc}")
        response = ctypes.create_string_buffer(65536)
        start = time.perf_counter()
        prompt = (frame["request"] if frame else
                  f"User goal: {record['goal']}\nCurrent app: {record['package']}\n"
                  f"Observed UI: {record['state']}\nChoose exactly one next action.")
        complete_rc = needle_lib.needle_complete(prompt.encode(), 256, response, len(response))
        complete_ms = (time.perf_counter() - start) * 1000
        envelope = json.loads(response.value) if complete_rc >= 0 else {}
        calls = envelope.get("function_calls") or []
        needle_choice = (calls[0]["name"] if len(calls) == 1 and
                          calls[0].get("name") in needle_keys and
                         not (calls[0].get("arguments") or {}) else None)
        needle_lib.needle_reset()
        rows.append({
            "step": record["step"], "package": record["package"],
            "phase": frame["phase"] if frame else None,
            "offered": {name: option["description"] for name, option in needle_keys.items()},
            "layaOffered": {name: option["description"] for name, option in laya_keys.items()},
            "omittedActionCount": max(0, len(record["candidates"]) - len(options)),
            "laya": {"choice": laya_answer["choice"], "topScore": ranked[0],
                     "margin": ranked[0] - ranked[1], "ms": laya_ms},
            "needle": {"choice": needle_choice, "callCount": len(calls),
                       "confidence": envelope.get("confidence"),
                       "ms": init_ms + complete_ms, "toolPrefixTokens": init_rc,
                       "promptChars": len(prompt)},
        })
        print(f"step={record['step']} Laya={laya_answer['choice']} {laya_ms:.1f}ms "
              f"Needle={needle_choice} {init_ms + complete_ms:.1f}ms", flush=True)

    laya_timings = [row["laya"]["ms"] for row in rows if row["laya"]["ms"] > 0]
    needle_timings = [row["needle"]["ms"] for row in rows if row["needle"]["ms"] > 0]
    report = {"mode": "read_only_host_shadow_of_real_tasker_youtube_states",
              "frame": args.frame,
              "reversedOptions": args.reverse_options,
              "maxActions": args.max_actions,
              "layaAllOptions": args.laya_all,
              "taskCompletionIsNotMeasured": True,
              "modelsAreNotAuthorizedToExecute": True,
              "trace": str(args.trace), "steps": len(rows),
              "inferenceSteps": len(laya_timings),
              "laya": {"loadMs": laya_load_ms,
                       "p50Ms": statistics.median(laya_timings) if laya_timings else 0.0,
                       "p95Ms": percentile(laya_timings, .95)},
              "needle": {"loadMs": needle_load_ms,
                         "p50Ms": statistics.median(needle_timings) if needle_timings else 0.0,
                         "p95Ms": percentile(needle_timings, .95)},
              "rows": rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Report: {args.output}")


if __name__ == "__main__":
    main()
