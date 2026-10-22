#!/usr/bin/env python3
"""Derive short supervised reasoning targets; preserve every cohort/input/answer."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path


def supervised_reasoning(row):
    if row.get("specialist")=="bounded_choice":
        if len(row["answers"])!=1 or row["answers"][0]["arguments"]!={}:
            raise ValueError("Bounded reasoning requires one offered zero-argument choice")
        name=row["answers"][0]["name"]
        if name not in row["turn"]["options"] or name!=row["expectedChoice"]:
            raise ValueError("Reasoning target disagrees with bound supervision")
        label=row["turn"]["options"][name]["label"]
        return f"At the current scope, choose {label} using {name}."
    if row["answers"]:
        call=row["answers"][0]
        return f"Request matches {call['name']}."+(" Grounded values: "+json.dumps(call["arguments"],separators=(",", ":")) if call["arguments"] else "")
    if row["stage"]=="negated":
        return "The user negates the requested action. No call."
    if row["stage"]=="unavailable":
        return "No available tool serves this request. No call."
    return "No supported unambiguous valid change. No call."


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        p.error("Refuse to overwrite an existing reasoning view")
    shutil.copytree(args.data, args.out)
    path = args.out/"train.needle.jsonl"
    rows = [json.loads(s) for s in path.read_text().splitlines()]
    for row in rows:
        if row.get("split")!="train":
            raise ValueError("Reasoning augmentation is training-only")
        row["reasoning"] = supervised_reasoning(row)
    content = "".join(json.dumps(r,ensure_ascii=False)+"\n" for r in rows)
    path.write_text(content)
    manifest = json.loads((args.out/"manifest.json").read_text())
    manifest["files"][path.name]["sha256"] = hashlib.sha256(content.encode()).hexdigest()
    manifest["reasoningAblation"] = {"parent": str(args.data), "onlyChangedFile": path.name, "inputAnswersGroupsUnchanged": True, "reasoningIsSupervisedTargetNotInferenceInput": True}
    (args.out/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n")
    print("Derived",len(rows),"training reasoning targets; development/calibration/sealed unchanged")


if __name__ == "__main__":
    main()
