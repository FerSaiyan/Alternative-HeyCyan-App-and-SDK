#!/usr/bin/env python3
"""Audit verified real-emulator episode pools before any model fine-tuning.

Checks grounding, verification and repeated-screen leakage. Does not create a
train/test split from a single app's repeated home screen.
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path


def audit(rows: list[dict]) -> dict:
    if not rows:
        raise ValueError("No observed rows")
    episodes = defaultdict(list)
    for row in rows:
        if row.get("observation", {}).get("provider") != "ARTEMIS_UIAUTOMATOR2":
            raise ValueError("A row has unknown observation provenance")
        if not row.get("outcomeVerified") or "postObservation" not in row:
            raise ValueError("Unverified or missing post-action observation")
        obs, post = row["observation"], row["postObservation"]
        if obs["packageName"] != post["packageName"]:
            raise ValueError("A transition crossed app boundaries without an oracle")
        offered = obs["candidates"]
        if len({c["id"] for c in offered}) != len(offered):
            raise ValueError("Duplicate candidate IDs")
        if any("bounds" in candidate for candidate in offered):
            raise ValueError("Device coordinates leaked into the model row")
        op = row["expected"]["operation"]
        if op == "TAP":
            if len(offered) < 3:
                raise ValueError("A model action has fewer than three observed controls")
            if row["expected"]["candidateId"] not in {c["id"] for c in offered}:
                raise ValueError("Gold action was not offered")
            if obs["hierarchySha256"] == post["hierarchySha256"]:
                raise ValueError("A tap was called successful without a changed UI")
            if not post["pageTitle"]:
                raise ValueError("A tap has no independently observed target page")
        elif op == "TYPE_LITERAL":
            if row["expected"]["candidateId"] != obs.get("focusedFieldCandidateId"):
                raise ValueError("Typing was not bound to a focused observed field")
            literal = row["expected"].get("literal", "")
            if not literal or literal not in row["goal"] or post.get("focusedFieldText") != literal:
                raise ValueError("Typed value lacks an exact user-literal postcondition")
            if obs["hierarchySha256"] == post["hierarchySha256"]:
                raise ValueError("Typing did not change the observed UI")
        elif op == "DONE":
            verification = row.get("verification", {})
            if "observedFieldValue" in verification:
                if not verification["observedFieldValue"] or verification["observedFieldValue"] != obs.get("focusedFieldText"):
                    raise ValueError("DONE was not verified by a focused field value")
            elif verification.get("observedPageTitle") != obs["pageTitle"] or not obs["pageTitle"]:
                raise ValueError("DONE was not verified by a page title")
            if obs["hierarchySha256"] != post["hierarchySha256"]:
                raise ValueError("DONE changed the screen without an observed action")
        else:
            raise ValueError(f"Unsupported supervised operation: {op}")
        episodes[row["episodeId"]].append(row)

    screen_to_episodes = defaultdict(set)
    operations = Counter()
    for episode, group in episodes.items():
        ordered = sorted(group, key=lambda r: r["step"])
        if [r["step"] for r in ordered] != list(range(len(ordered))):
            raise ValueError(f"Invalid step order in {episode}")
        for previous, following in zip(ordered, ordered[1:]):
            if previous["postObservation"]["hierarchySha256"] != following["observation"]["hierarchySha256"]:
                raise ValueError(f"Broken observed transition in {episode}")
        for row in ordered:
            operations[row["expected"]["operation"]] += 1
            screen_to_episodes[row["observation"]["screenSignature"]].add(episode)

    # Episodes sharing an actual UI hierarchy cannot be counted as independent
    # screen families. Keep each connected component on one side of any split.
    parent = {episode: episode for episode in episodes}

    def root(value):
        while parent[value] != value:
            value = parent[value]
        return value

    for members in screen_to_episodes.values():
        origin = min(members)
        for member in members:
            parent[root(member)] = root(origin)
    components = defaultdict(list)
    for episode in episodes:
        components[root(episode)].append(episode)
    return {"episodes": len(episodes), "decisions": len(rows),
            "uniqueScreenSignatures": len(screen_to_episodes),
            "operations": dict(operations), "componentCount": len(components),
            "episodeComponents": [sorted(items) for items in components.values()],
            "repeatedScreenCount": sum(len(group) > 1 for group in screen_to_episodes.values()),
            "minimumTwoFamiliesPerSplit": len(components) >= 6,
            "readyForIndependentSplit": len(components) >= 6,
            "readyForTaskerTraining": False,
            "limitations": "Same app UI appearing in multiple goals is one screen family; Artemis is not Tasker."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.dataset.read_text().splitlines() if line.strip()]
    report = audit(rows)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in ("episodes", "decisions", "uniqueScreenSignatures",
                                              "operations", "componentCount", "repeatedScreenCount",
                                              "readyForIndependentSplit")}, indent=2))


if __name__ == "__main__":
    main()
