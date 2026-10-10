#!/usr/bin/env python3
"""Predeclared lexical baseline on development data, not learned embeddings."""
import argparse
import json
import re
from pathlib import Path

from make_specialist_training import bind_config
from specialist_metrics import summarize


def words(text):
    return set(re.findall(r"[a-z]+", text.lower().replace("_", " ")))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    args = p.parse_args()
    rows = [json.loads(s) for s in (args.data/"development.needle.jsonl").read_text().splitlines()]
    results = []
    for row in rows:
        query = row["query"]
        calls = []
        if not re.search(r"\b(?:do not|don't|not to)\b", query.lower()):
            q = words(query)
            scores = sorted(((len(q & words(t["name"] + " " + t["description"])) / max(1, len(words(t["name"] + " " + t["description"]))), t["name"]) for t in row["tools"]), reverse=True)
            if scores and scores[0][0] > 0 and (len(scores) == 1 or scores[0][0] > scores[1][0]):
                name = scores[0][1]
                arguments = bind_config(query, name) if row["specialist"] == "configuration" else {}
                calls = [{"name": name, "arguments": arguments}] if arguments is not None else None
        results.append({"caseId": row["caseId"], "stage": row["stage"], "expected": row["answers"], "calls": calls})
    report = dict(summarize(results), results=results, baseline="lexical_overlap_with_explicit_negation", finalTestOpened=False, calibrationOpened=False)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps({k: v for k, v in report.items() if k != "results"}, indent=2))


if __name__ == "__main__":
    main()
