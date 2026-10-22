#!/usr/bin/env python3
"""State-matched ablation: expose all legal terminal choices on a single turn."""
import argparse
import json
import random
from pathlib import Path

from bounded_specialist_contract import CONTROLS, VERSION, build_turn, choices, fingerprint, option
from make_bounded_specialist_training import generate, write_records


def flat_turn(snapshot, request, id, version=VERSION):
    root = build_turn(snapshot, request, [], id, shuffle=False, version=version)
    queue, leaves = [[]], []
    while queue:
        path = queue.pop(0)
        for bound in choices(snapshot, path):
            if "nextPath" in bound:
                queue.append(bound["nextPath"])
            else:
                leaves.append(bound)
    leaves.extend(option(key,label,effect={"kind":key}) for key,label in CONTROLS.items())
    random.Random(int(fingerprint(id)[:8],16)).shuffle(leaves)
    root["options"] = {f"option_{i}":bound for i,bound in enumerate(leaves)}
    root["view"] = "flat_state_matched"
    return root


def resolve_flat_choice(turn, selected, snapshot):
    if snapshot.get("fresh") is not True or fingerprint(snapshot) != turn["snapshotHash"]:
        raise ValueError("Stale flat menu")
    if selected not in turn["options"]:
        raise ValueError("Unlisted flat choice")
    request = json.loads(turn["query"])["request"]
    legal = flat_turn(snapshot, request, turn["turnId"], version=turn["contract"])
    if turn["options"] != legal["options"]:
        raise ValueError("Forged flat binding")
    return turn["options"][selected]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out",type=Path,required=True)
    args = p.parse_args()
    episodes, _ = generate()
    turns = []
    for ep in episodes:
        id = ep["episodeId"]+":0"
        turn = flat_turn(ep["snapshot"],ep["request"],id)
        matched = [key for key,bound in turn["options"].items() if bound["effect"] == ep["expectedEffect"]]
        if len(matched) != 1:
            raise ValueError((ep["episodeId"],"Flat/hierarchical outcome mismatch"))
        turns.append({"caseId":id,"episodeId":ep["episodeId"],"groupId":ep["groupId"],"split":ep["split"],"stage":"flat_state_matched","source":"generated_host_state_hierarchy","turn":turn,"expectedChoice":matched[0],"expectedEffect":ep["expectedEffect"],"requestedBranchRecall":True})
    write_records(args.out,episodes,turns,view="flat_state_matched")


if __name__ == "__main__":
    main()
