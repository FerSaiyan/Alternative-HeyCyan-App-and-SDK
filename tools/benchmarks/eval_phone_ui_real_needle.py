#!/usr/bin/env python3
"""Read-only Needle evaluation on six original Tasker states, four controls each.

Names are derived from *visible* labels, never from the gold option. This
conservative projection does not have Tasker role/clickability metadata.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import re
import time
from pathlib import Path

from benchmark_needle_mobile_actions import load_engine
from eval_phone_ui_real_laya import SUBGOAL_GOLD, load_trace


def label(description):
    quoted = re.search(r'"([^"\n]+)"', description)
    return quoted.group(1) if quoted else description


def name_for(candidate, counters):
    text = label(candidate["description"]).strip()
    lower = text.casefold()
    if candidate["key"] == "open_app":
        stem = "open_app"
    elif lower == "search":
        stem = "tap_search"
    elif lower.startswith("go to channel"):
        stem = "open_channel"
    elif lower.startswith("edit suggestion"):
        stem = "edit_suggestion"
    elif re.search(r"\b\d+\s*(?:mi|minutes?)\b", lower):
        stem = "open_video"
    else:
        stem = "tap_visible"
    counters[stem] = counters.get(stem, 0) + 1
    return stem if stem == "tap_search" else f"{stem}_{counters[stem]}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    rows = load_trace(args.trace)
    data = args.model.read_bytes()
    model_buf = ctypes.create_string_buffer(data)
    lib = load_engine(args.library.resolve())
    if lib.needle_load(model_buf, len(data)) != 0:
        raise RuntimeError("needle_load failed")
    results = []
    for observed in rows:
        step = observed["step"]
        subgoal, gold_letter = SUBGOAL_GOLD[step]
        candidates = [c for c in observed["candidates"] if c["key"] != "detailed_planner"][:4]
        if gold_letter != "G" and all(c["label"] != gold_letter for c in candidates):
            raise ValueError("Gold candidate clipped by the four-control budget")
        counters = {}
        offered = {name_for(c, counters): c for c in candidates}
        tools = [{"type": "function", "function": {"name": name,
                  "description": ("Open YouTube" if c["key"] == "open_app" else
                                  "Tap visible " + label(c["description"])),
                  "parameters": {"type": "object", "properties": {}, "required": []}}}
                 for name, c in offered.items()]
        started = time.perf_counter()
        prefix = lib.needle_init(b"device: phone; app: YouTube", json.dumps(tools).encode(), None)
        if prefix < 0:
            raise RuntimeError(f"needle_init failed on step {step}: {prefix}")
        output = ctypes.create_string_buffer(65536)
        rc = lib.needle_complete(subgoal.encode(), 192, output, len(output))
        elapsed_ms = (time.perf_counter() - started) * 1000
        if rc < 0:
            calls = None
        else:
            try:
                calls = json.loads(output.value.decode()).get("function_calls")
            except (UnicodeDecodeError, json.JSONDecodeError):
                calls = None
        chosen = calls[0]["name"] if isinstance(calls, list) and len(calls) == 1 else None
        chosen_letter = offered[chosen]["label"] if chosen in offered else None
        correct = (calls == [] if gold_letter == "G" else chosen_letter == gold_letter)
        results.append({"step": step, "gold": gold_letter, "chosen": chosen_letter,
                        "calls": len(calls) if isinstance(calls, list) else None,
                        "correct": correct, "prefixTokens": prefix,
                        "offered": len(offered), "omitted": len(observed["candidates"]) - 1 - len(offered),
                        "ms": elapsed_ms})
        lib.needle_reset()
    report = {"mode": "read_only_Tasker_shadow_four_visible_controls_no_execution",
              "modelSha256": hashlib.sha256(data).hexdigest(),
              "traceSha256": hashlib.sha256(args.trace.read_bytes()).hexdigest(),
              "correct": sum(r["correct"] for r in results), "cases": len(results),
              "limitations": "One episode; a first-four renderer omits other controls, and Tasker has no role flags",
              "rows": results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
