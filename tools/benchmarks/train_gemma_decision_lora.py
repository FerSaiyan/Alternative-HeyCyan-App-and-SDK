#!/usr/bin/env python3
"""LoRA-tune an ordinary chat LLM for the shared next-token decision prompt.

Uses exactly the decision prompt the Android path scores (gemma_decision_prompts
+ contract instructions + chat template + U+22B2) and trains full-vocabulary
cross-entropy on the gold position label, under freshly shuffled option order
each step. Budget and development-snapshot selection match the Laya/EmbeddingGemma
campaign (180 updates, batch 16, evaluate every 60). Calibration and sealed
cohorts are never read. Evaluation mirrors SharedGemmaDecisionEngine (normal +
reversed menus, aligned average).
"""
import argparse
import hashlib
import json
import random
import time
from pathlib import Path

from bounded_specialist_contract import INSTRUCTIONS, choice_metrics, choice_result
from gemma_decision_prompts import PROMPTS, option_descriptions, render_user_prompt

LANGUAGE_LORA_TARGETS = r".*language_model\.layers\.\d+\.(self_attn\.(q|k|v|o)_proj|mlp\.(gate|up|down)_proj)"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("model", "data", "out"):
        p.add_argument("--" + name, type=Path, required=True)
    p.add_argument("--prompt", choices=sorted(PROMPTS), default="baseline")
    p.add_argument("--updates", type=int, default=180)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--eval-every", type=int, default=60)
    p.add_argument("--lr", type=float, default=1e-4)
    p.add_argument("--rank", type=int, default=16)
    p.add_argument("--seed", type=int, default=20261003)
    args = p.parse_args()
    if (args.out / "adapter_model.safetensors").exists():
        p.error("Refuse to overwrite an existing adapter; use a fresh --out")
    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer
    random.seed(args.seed)
    torch.manual_seed(args.seed)
    tok = AutoTokenizer.from_pretrained(args.model)
    model = AutoModelForCausalLM.from_pretrained(args.model, dtype=torch.bfloat16).cuda()
    model.gradient_checkpointing_enable()
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(r=args.rank, lora_alpha=2 * args.rank, lora_dropout=0.05,
                                             target_modules=LANGUAGE_LORA_TARGETS, task_type="CAUSAL_LM"))
    letters = [chr(ord("A") + i) for i in range(26)]
    letter_ids = [tok(x, add_special_tokens=False).input_ids[0] for x in letters]

    def load(split):
        return [json.loads(s) for s in (args.data / f"{split}.canonical.jsonl").read_text().splitlines()]

    train, dev = load("train"), load("development")
    assert not ({r["groupId"] for r in train} & {r["groupId"] for r in dev})

    def prompt_ids(row, order):
        options = row["turn"]["options"]
        descriptions = option_descriptions(args.prompt, options, json.loads(row["turn"]["query"])["status"])
        user = render_user_prompt(args.prompt, INSTRUCTIONS + "\n\n" + row["turn"]["query"],
                                  [descriptions[i] for i in order], letters[:len(order)])
        text = tok.apply_chat_template([{"role": "user", "content": user}], tokenize=False,
                                       add_generation_prompt=True, enable_thinking=False) + "⊲"
        return tok(text, add_special_tokens=False, return_tensors="pt").input_ids.cuda()

    def last_logits(ids):
        return model(input_ids=ids, logits_to_keep=1).logits[0, -1].float()

    def evaluate():
        model.eval()
        results = []
        with torch.no_grad():
            for row in dev:
                keys = list(row["turn"]["options"])
                probs = torch.zeros(len(keys))
                for order in (list(range(len(keys))), list(reversed(range(len(keys))))):
                    logp = torch.log_softmax(last_logits(prompt_ids(row, order)), -1)[letter_ids[:len(keys)]].cpu()
                    probs[torch.tensor(order)] += torch.softmax(logp, 0) / 2
                results.append(choice_result(row, keys[int(probs.argmax())]))
        model.train()
        return dict(choice_metrics(results), results=results)

    args.out.mkdir(parents=True, exist_ok=True)
    best, best_step, history = -1, 0, []
    started = time.monotonic()
    model.train()
    optimizer = torch.optim.AdamW([x for x in model.parameters() if x.requires_grad], lr=args.lr)
    for step in range(1, args.updates + 1):
        optimizer.zero_grad(set_to_none=True)
        total = 0.0
        for row in random.sample(train, args.batch):
            keys = list(row["turn"]["options"])
            order = random.sample(range(len(keys)), len(keys))
            gold = letter_ids[order.index(keys.index(row["expectedChoice"]))]
            loss = torch.nn.functional.cross_entropy(last_logits(prompt_ids(row, order))[None], torch.tensor([gold], device="cuda")) / args.batch
            loss.backward()
            total += loss.item()
        torch.nn.utils.clip_grad_norm_([x for x in model.parameters() if x.requires_grad], 1.0)
        optimizer.step()
        if step % 10 == 0:
            print(f"update {step} loss={total:.4f}", flush=True)
        if step % args.eval_every == 0 or step == args.updates:
            result = evaluate()
            history.append({"update": step, "loss": total, "development": {k: v for k, v in result.items() if k != "results"}})
            print(f"eval {step}: {result['correct']}/{result['cases']} controlFalse={result['controlFalseActionRate']:.3f}", flush=True)
            if result["correct"] > best:
                best, best_step = result["correct"], step
                model.save_pretrained(args.out)
    report = {"settings": {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
              "bestUpdate": best_step, "bestDevelopmentCorrect": best, "history": history,
              "selection": "best development snapshot, as in the Laya/EmbeddingGemma campaign",
              "adapterSha256": hashlib.sha256((args.out / "adapter_model.safetensors").read_bytes()).hexdigest(),
              "wallSeconds": time.monotonic() - started, "calibrationOpened": False, "finalTestOpened": False}
    (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("saved", args.out, "best", best, "at", best_step, flush=True)


if __name__ == "__main__":
    main()
