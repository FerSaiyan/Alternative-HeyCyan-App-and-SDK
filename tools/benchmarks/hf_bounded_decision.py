#!/usr/bin/env python3
"""Host replica of SharedGemmaDecisionEngine for prompt and fine-tune iteration.

Mirrors the Android path: model chat template with thinking disabled, assistant
prefix, then the U+22B2 delimiter; label log p from the next-token distribution;
normal and reversed menus with fresh position labels, softmax over labels and
aligned averaging. Writes the device JSONL format read by
score_litert_bounded_eval.py. Expected choices are never read here.
"""
import argparse
import json
import time
from pathlib import Path

from bounded_specialist_contract import INSTRUCTIONS
from gemma_decision_prompts import PROMPTS, option_descriptions, render_user_prompt


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--model", type=Path, required=True)
    p.add_argument("--adapter", type=Path, help="Optional PEFT LoRA adapter directory")
    p.add_argument("--cases", type=Path, required=True, help="*.canonical.jsonl")
    p.add_argument("--prompt", choices=sorted(PROMPTS), default="baseline")
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(args.model, dtype=torch.bfloat16).cuda().eval()
    if args.adapter:
        from peft import PeftModel
        model = PeftModel.from_pretrained(model, args.adapter).eval()
    label_ids = {chr(ord("A") + i): tok(chr(ord("A") + i), add_special_tokens=False).input_ids for i in range(26)}
    assert all(len(v) == 1 for v in label_ids.values()), "labels must be single tokens"

    def chat(user):
        rendered = tok.apply_chat_template([{"role": "user", "content": user}], tokenize=False,
                                           add_generation_prompt=True, enable_thinking=False)
        return rendered + "⊲"

    def label_logprobs(user, labels):
        ids = tok(chat(user), add_special_tokens=False, return_tensors="pt").input_ids.cuda()
        with torch.no_grad():
            logp = torch.log_softmax(model(ids).logits[0, -1].float(), -1)
        return [logp[label_ids[label][0]].item() for label in labels]

    rows = [json.loads(s) for s in args.cases.read_text().splitlines()]
    probe = chat("PROBE_USER_TEXT")
    out = [{"kind": "header", "model": str(args.model), "adapter": str(args.adapter) if args.adapter else None,
            "prompt": args.prompt, "renderedProbe": probe, "activeBackend": "HF-bf16-cuda"}]
    for row in rows:
        options = row["turn"]["options"]
        keys = list(options)
        descriptions = option_descriptions(args.prompt, options, json.loads(row["turn"]["query"])["status"])
        state = INSTRUCTIONS + "\n\n" + row["turn"]["query"]
        probs = [0.0] * len(keys)
        masses = []
        started = time.perf_counter()
        for order in (list(range(len(keys))), list(reversed(range(len(keys))))):
            labels = [chr(ord("A") + i) for i in range(len(order))]
            user = render_user_prompt(args.prompt, state, [descriptions[i] for i in order], labels)
            logps = torch.tensor(label_logprobs(user, labels))
            masses.append(logps.exp().sum().item())
            for position, original in enumerate(order):
                probs[original] += torch.softmax(logps, 0)[position].item() / 2
        winner = max(range(len(keys)), key=probs.__getitem__)
        runner_up = max(probs[i] for i in range(len(keys)) if i != winner)
        out.append({"kind": "case", "caseId": row["caseId"], "optionIds": keys, "selected": keys[winner],
                    "probabilities": probs, "margin": probs[winner] - runner_up,
                    "raw": f"hf-replica labelMass={masses[0]:.6g},{masses[1]:.6g} variants=2",
                    "decisionMs": round((time.perf_counter() - started) * 1000)})
    out.append({"kind": "footer", "cases": len(rows)})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(r) + "\n" for r in out))
    print("wrote", args.output, len(rows), "cases; probe", repr(probe[-60:]))


if __name__ == "__main__":
    main()
