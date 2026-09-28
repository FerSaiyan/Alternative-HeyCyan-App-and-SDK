#!/usr/bin/env python3
"""Create deterministic, entirely synthetic Laya/Needle UI-decision pilot data.

No private Tasker logs, credentials, benchmark rows, model answers, or device
actions enter this generator. The separate six-state Tasker trace stays held out.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from collections import Counter
from pathlib import Path


NAMES = {
    "train": ("Amber Circuit", "Blue Harbor", "Copper Finch", "Delta Workshop",
              "Eclipse Notes", "Fern Foundry", "Golden Lantern", "Harbor Signal",
              "Indigo Maker", "Jade Studio", "Kite Atlas", "Lunar Relay",
              "Maple Device", "Nova Workshop", "Olive Robotics", "Pine Tablet",
              "Quartz Review", "River Forge", "Silver Pilot", "Tidal Camera",
              "Umber Desk", "Velvet Build", "Willow Gadget", "Zebra Circuit"),
    "validation": ("Acorn Device", "Birch Pilot", "Cobalt Forge", "Drift Signal",
                   "Elm Circuit", "Frost Review", "Glacier Tablet", "Horizon Build"),
    "test": ("Iris Camera", "Juniper Relay", "Marble Gadget", "North Studio",
             "Opal Foundry", "Pebble Notes", "Ruby Maker", "Summit Workshop"),
}


def make_screen(stage: str, target: str, other: str):
    """Return subgoal, observed candidate (tool name, description) pairs, gold name."""
    if stage == "launch":
        return (f"Open the {target} app", [
            ("open_app_1", f"Open {target}"), ("open_app_2", f"Open {other}"),
            ("open_app_3", "Open Maps")], "open_app_1")
    if stage == "home":
        return (f"Search {target} for {other}", [
            ("tap_search", "Tap Search"), ("tap_voice_search", "Tap Voice search"),
            ("tap_notifications", "Tap Notifications")], "tap_search")
    if stage == "suggestion":
        return (f"Choose the exact search suggestion {target}", [
            ("choose_suggestion_1", f"Tap search suggestion {target}"),
            ("choose_suggestion_2", f"Tap search suggestion {other}"),
            ("edit_suggestion", f"Edit suggestion {target}")], "choose_suggestion_1")
    if stage == "filled_query":
        return (f"Submit the search for {target}; field already contains {target}", [
            ("tap_search", "Tap Search"), ("tap_clear", "Tap Clear"),
            ("tap_voice_search", "Tap Voice search")], "tap_search")
    if stage == "results":
        return (f"Open the {target} channel from search results", [
            ("open_channel_1", f"Open channel {target}"),
            ("open_channel_2", f"Open channel {other}"),
            ("open_video_1", f"Open video about {target} from {other}")], "open_channel_1")
    if stage == "video":
        return (f"Open a video from the {target} channel", [
            ("open_video_1", f"Open video {target} workshop tour, from {target}"),
            ("open_video_2", f"Open video {other} workshop tour, from {other}"),
            ("open_about", f"Open {target} channel description")], "open_video_1")
    if stage == "no_match":
        return (f"Open the {target} channel; its control is not visible", [
            ("open_channel_1", f"Open channel {other}"),
            ("tap_notifications", "Tap Notifications"),
            ("tap_voice_search", "Tap Voice search")], None)
    if stage == "ambiguous":
        return (f"Choose the search result from {target}; owner is not shown", [
            ("open_video_1", f"Open video {target} review"),
            ("open_video_2", f"Open video {target} review"),
            ("tap_back", "Navigate back")], None)
    raise ValueError(stage)


STAGES = ("launch", "home", "suggestion", "filled_query", "results", "video", "no_match", "ambiguous")


def rows_for(split: str):
    names = NAMES[split]
    variants = 3 if split == "train" else 2
    for target_index, target in enumerate(names):
        other = names[(target_index + 5) % len(names)]
        for stage in STAGES:
            for variant in range(variants):
                # Hold all permutations of an episode together. No model answer is
                # present in the candidate descriptions or the subgoal.
                episode_id = f"{split}:{target_index:02d}:{stage}"
                subgoal, candidates, expected_name = make_screen(stage, target, other)
                rng = random.Random(f"phone-ui-v1:{episode_id}:{variant}")
                rng.shuffle(candidates)
                # Number same-operation tools by their *shuffled* position, not
                # their gold role. Otherwise "choose_suggestion_1" is always
                # the answer and Needle can memorize an ID without reading UI.
                ordinals = Counter()
                renamed = []
                original_expected = expected_name
                for old_name, description in candidates:
                    family, _, suffix = old_name.rpartition("_")
                    if suffix.isdigit() and family in {"open_app", "choose_suggestion", "open_channel", "open_video"}:
                        ordinals[family] += 1
                        name = f"{family}_{ordinals[family]}"
                    else:
                        name = old_name
                    if old_name == original_expected:
                        expected_name = name
                    renamed.append((name, description))
                candidates = renamed
                if variant % 2:
                    subgoal = subgoal.replace("Open ", "Please open ", 1).replace("Choose ", "Select ", 1)
                app = "Launcher" if stage == "launch" else "YouTube"
                criteria = {chr(65 + i): description for i, (_, description) in enumerate(candidates)}
                # Planner must not always be in the final position.
                defer_position = rng.randrange(len(criteria) + 1)
                laya_criteria = list(criteria.items())
                laya_criteria.insert(defer_position, ("defer", "Ask the detailed planner: no safe observed action"))
                name_to_key = {name: key for key, (name, _) in zip(criteria, candidates)}
                case_id = f"{episode_id}:{variant}"
                state = {"current_step": subgoal, "app": app}
                laya = {"state": state, "questions": {"next_action": {
                    "type": "choice", "instructions": "Which observed control advances this current step? Defer if none is grounded.",
                    "criteria": dict(laya_criteria),
                }}, "expected": {"next_action": name_to_key.get(expected_name, "defer")},
                    "tags": [stage, split, "synthetic"], "language": "en",
                    "episodeId": episode_id, "caseId": case_id}
                tools = [{"name": name, "description": desc,
                          "parameters": {"type": "object", "properties": {}, "required": []}}
                         for name, desc in candidates]
                needle = {"query": subgoal, "system": f"device: phone; app: {app}; locale: en-US",
                          "tools": tools,
                          "answers": [{"name": expected_name, "arguments": {}}] if expected_name else [],
                          "reasoning": (f"'{target}' and the visible control -> {expected_name}"
                                        if expected_name else "No uniquely matching visible control"),
                          "episodeId": episode_id, "caseId": case_id, "stage": stage}
                yield laya, needle


def generate(output: Path):
    output.mkdir(parents=True, exist_ok=True)
    manifest = {"version": "synthetic-phone-ui-v1", "seed": "phone-ui-v1",
                "provenance": "programmatically generated; no Tasker observations or frozen benchmark cases",
                "use": "research pilot only; never measure real-device accuracy from these rows",
                "splits": {}}
    seen_cases, seen_episodes = set(), {}
    for split in NAMES:
        rows = list(rows_for(split))
        seen_episodes[split] = {laya["episodeId"] for laya, _ in rows}
        counts = Counter()
        for laya, needle in rows:
            assert laya["caseId"] == needle["caseId"] and laya["caseId"] not in seen_cases
            seen_cases.add(laya["caseId"])
            assert needle["answers"] == [] or needle["answers"][0]["name"] in {t["name"] for t in needle["tools"]}
            assert laya["expected"]["next_action"] in laya["questions"]["next_action"]["criteria"]
            counts[laya["tags"][0]] += 1
        for name, selected in (("laya", [a for a, _ in rows]), ("needle", [b for _, b in rows])):
            path = output / f"{split}.{name}.jsonl"
            content = "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n" for r in selected)
            path.write_text(content)
            manifest["splits"].setdefault(split, {})[name] = {
                "file": path.name, "rows": len(selected), "sha256": hashlib.sha256(content.encode()).hexdigest()}
        manifest["splits"][split]["episodes"] = len(seen_episodes[split])
        manifest["splits"][split]["stages"] = dict(counts)
    for left in NAMES:
        for right in NAMES:
            if left != right:
                assert seen_episodes[left].isdisjoint(seen_episodes[right])
                assert set(NAMES[left]).isdisjoint(NAMES[right])
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("android/CyanBridge/training/phone_ui_synthetic_v1"))
    args = parser.parse_args()
    print(json.dumps(generate(args.output), indent=2))
