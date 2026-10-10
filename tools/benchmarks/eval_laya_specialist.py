#!/usr/bin/env python3
"""Read-only Laya development classifier + independent host argument binder."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from bounded_specialist_contract import choice_metrics, choice_result, laya_selected_calls
from specialist_metrics import summarize


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "model", "data", "report"):
        p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--opaque-names", action="store_true", help="Replace semantic choice names by fresh bound IDs, preserving criteria")
    args = p.parse_args()
    import torch
    from safetensors.torch import load_file
    from transformers import AutoTokenizer
    sys.path.insert(0, str(args.source.resolve()))
    from laya.common import build_model, build_sequence
    torch.set_num_threads(4)
    config = json.loads((args.model/"rl_agent_config.json").read_text())
    tok = AutoTokenizer.from_pretrained(args.model/"tokenizer", local_files_only=True)
    model = build_model(config, encoder_dir=str(args.model/"encoder"))
    model.encoder.config.reference_compile = False
    model.load_state_dict(load_file(args.model/"model.safetensors"), strict=True)
    model.cuda().eval()
    rows = [json.loads(s) for s in (args.data/"development.laya.jsonl").read_text().splitlines()]
    results = []
    with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
        for row in rows:
            q = row["questions"]["next_action"]
            binding = {}
            if args.opaque_names:
                criteria = {}
                for j, (key, description) in enumerate(q["criteria"].items()):
                    name = f"option_{len(results)}_{j}"
                    binding[name] = key
                    criteria[name] = description
                q = dict(q, criteria=criteria)
            labels = list(q["criteria"])
            ids, markers = build_sequence(tok, row["state"], {"t": "choice", "ins": q["instructions"], "crit": q["criteria"]}, config["max_len"], config["head_max_len"])
            assert len(labels) == len(markers)
            tokens = torch.tensor([ids], device="cuda")
            logits, _ = model(tokens, torch.ones_like(tokens), torch.tensor([markers], device="cuda"), torch.ones((1,len(markers)), device="cuda", dtype=torch.bool), torch.zeros(1,device="cuda",dtype=torch.long))
            selected = labels[logits.argmax(-1).item()]
            selected = binding.get(selected, selected)
            calls = laya_selected_calls(row, selected)
            result = {"caseId": row["caseId"], "stage": row["stage"], "expected": row["expectedCalls"], "calls": calls, "selected": selected}
            if row["specialist"] == "bounded_choice":
                result.update(choice_result(row, selected))
            results.append(result)
    report = dict(summarize(results), model=str(args.model), modelSha256=hashlib.sha256((args.model/"model.safetensors").read_bytes()).hexdigest(), opaqueNames=args.opaque_names, results=results, finalTestOpened=False, calibrationOpened=False, valueBinder="host lexical binder; not model-generated arguments")
    if rows[0]["specialist"] == "bounded_choice":
        report["boundedMetrics"] = choice_metrics(results)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps({k: v for k,v in report.items() if k != "results"}, indent=2))


if __name__ == "__main__":
    main()
