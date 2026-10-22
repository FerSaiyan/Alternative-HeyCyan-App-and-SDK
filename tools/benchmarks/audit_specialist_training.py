#!/usr/bin/env python3
"""Integrity/split/schema/token audit; audits sealed metadata, never evaluates it."""
import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

from make_specialist_training import equivalent_names


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, required=True)
    p.add_argument("--needle-source", type=Path)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    tokenizer = renderer = None
    if args.needle_source:
        sys.path.insert(0, str(args.needle_source.resolve()))
        from needle.model.finetune import render_example
        from needle.model.tokenizer import get_tokenizer
        tokenizer, renderer = get_tokenizer(), render_example
    report = {"datasets": {}, "sealedEvaluated": False, "limits": "Approximate lexical grouping; generated product cohorts are not independent real-voice validation."}
    for directory in sorted(args.data.iterdir()):
        if not directory.is_dir():
            continue
        manifest = json.loads((directory/"manifest.json").read_text())
        groups, raw_goals, counts, maximum = {}, {}, {}, 0
        sources = Counter()
        for split in ("train", "development", "calibration", "sealed"):
            files = []
            for format in ("canonical", "laya", "needle"):
                name = f"{split}.{format}.jsonl"
                content = (directory/name).read_bytes()
                assert hashlib.sha256(content).hexdigest() == manifest["files"][name]["sha256"], name
                rows = [json.loads(s) for s in content.splitlines()]
                assert len(rows) == manifest["files"][name]["rows"], name
                files.append(rows)
            canonical, laya, needle = files
            counts[split] = len(canonical)
            for c, l, n in zip(canonical, laya, needle):
                assert c["caseId"] == l["caseId"] == n["caseId"]
                assert c["groupId"] == l["groupId"] == n["groupId"]
                assert groups.setdefault(c["groupId"], split) == split, (directory, c["groupId"])
                if c["specialist"] == "voice_capability":
                    # Same query under different menus remains one source goal.
                    key = " ".join(c["query"].lower().split())
                    assert raw_goals.setdefault(key, split) == split, (directory, "raw goal leakage")
                assert c["answers"] == n["answers"] == l["expectedCalls"]
                assert c["query"] == n["query"] == l["state"]
                schemas = {t["name"]: t for t in n["tools"]}
                assert len(schemas) == len(n["tools"]) <= 5
                for call in n["answers"]:
                    assert call["name"] in schemas, c["caseId"]
                    schema = schemas[call["name"]]["parameters"]
                    assert set(schema["required"]) <= set(call["arguments"])
                    assert set(call["arguments"]) <= set(schema["properties"])
                    for key, value in call["arguments"].items():
                        field = schema["properties"][key]
                        if field["type"] == "integer":
                            assert type(value) is int and field.get("minimum", value) <= value <= field.get("maximum", value)
                        if "enum" in field:
                            assert value in field["enum"]
                if n["answers"] and c["specialist"] == "voice_capability":
                    name = n["answers"][0]["name"]
                    assert set(schemas) & equivalent_names(name) == {name}
                if split == "train":
                    sources[c["source"]] += 1
                if tokenizer:
                    length = sum(len(tokenizer.encode(part)) for part in renderer(n)) + 2
                    assert length <= 512, (directory, c["caseId"], length)
                    maximum = max(maximum, length)
        report["datasets"][directory.name] = {"counts": counts, "groups": len(groups), "trainSources": dict(sources), "needleMaxTokens": maximum if tokenizer else None}
    for prefix in ("voice", "config"):
        for split in ("development", "calibration", "sealed"):
            for model in ("canonical", "laya", "needle"):
                name = f"{split}.{model}.jsonl"
                assert (args.data/f"{prefix}_positive"/name).read_bytes() == (args.data/f"{prefix}_with_negatives"/name).read_bytes(), (prefix, split, "ablation cohort mismatch")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
