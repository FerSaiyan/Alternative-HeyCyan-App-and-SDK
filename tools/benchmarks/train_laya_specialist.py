#!/usr/bin/env python3
"""Matched-update Laya head/full-encoder pilot, development-only selection."""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import time
from pathlib import Path

from bounded_specialist_contract import choice_metrics, choice_result, laya_selected_calls
from specialist_metrics import summarize


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "base", "data", "out"):
        p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--encoder", choices=("frozen", "full"), default="frozen")
    p.add_argument("--updates", type=int, default=300)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--microbatch", type=int, default=4)
    p.add_argument("--eval-every", type=int, default=100)
    p.add_argument("--lr", type=float, default=0.00012)
    p.add_argument("--encoder-lr", type=float, default=0.00001)
    p.add_argument("--seed", type=int, default=20261003)
    p.add_argument("--max-len", type=int, default=512)
    args = p.parse_args()
    if min(args.batch, args.updates, args.eval_every, args.microbatch) <= 0 or args.batch % args.microbatch:
        p.error("Positive updates/eval/microbatch and divisible effective batch required")
    if (args.out/"model.safetensors").exists():
        p.error("Refuse to overwrite an existing trained checkpoint; use a fresh --out")
    import torch
    from safetensors.torch import load_file, save_file
    from transformers import AutoTokenizer
    sys.path.insert(0, str(args.source.resolve()))
    from laya.common import build_model, build_sequence, render_options, serialize_state
    torch.manual_seed(args.seed)
    random.seed(args.seed)
    torch.set_num_threads(4)
    torch.backends.cuda.matmul.allow_tf32 = True
    config = json.loads((args.base / "rl_agent_config.json").read_text())
    config.update(max_len=args.max_len, head_max_len=256)
    tokenizer = AutoTokenizer.from_pretrained(args.base / "tokenizer", local_files_only=True)
    max_tokens = {}

    def load(split):
        cases = [json.loads(s) for s in (args.data / f"{split}.laya.jsonl").read_text().splitlines()]
        items = []
        for case in cases:
            q = case["questions"]["next_action"]
            question = {"t": "choice", "ins": q["instructions"], "crit": q["criteria"]}
            labels = list(q["criteria"])
            for option in render_options(question):
                if len(tokenizer.encode(" " + option, add_special_tokens=False)) > 48:
                    raise ValueError(f"Option clipped: {case['caseId']}: {option}")
            ids, markers = build_sequence(tokenizer, case["state"], question, config["max_len"], config["head_max_len"])
            state_ids = tokenizer.encode(serialize_state(case["state"]), add_special_tokens=False)
            assert len(markers) == len(labels), case["caseId"]
            assert ids[-len(state_ids)-1:-1] == state_ids, f"State clipped: {case['caseId']}"
            items.append((ids, markers, labels.index(case["expected"]["next_action"]), case, labels))
        max_tokens[split] = max(len(x[0]) for x in items)
        return items

    train, dev = load("train"), load("development")
    assert not ({x[3]["groupId"] for x in train} & {x[3]["groupId"] for x in dev})
    model = build_model(config, encoder_dir=str(args.base / "encoder"))
    # Eager embeddings avoid ModernBERT's per-shape torch.compile startup and
    # external cache defaults; attention still uses SDPA on CUDA.
    model.encoder.config.reference_compile = False
    model.load_state_dict(load_file(args.base / "model.safetensors"), strict=True)
    model.cuda()
    for param in model.encoder.parameters():
        param.requires_grad_(args.encoder == "full")
    if args.encoder == "full":
        model.encoder.gradient_checkpointing_enable()
        model.head_checkpointing = True
    encoder_ids = {id(param) for param in model.encoder.parameters()}
    head = [param for param in model.parameters() if param.requires_grad and id(param) not in encoder_ids]
    groups = [{"params": head, "lr": args.lr}]
    if args.encoder == "full":
        groups.append({"params": list(model.encoder.parameters()), "lr": args.encoder_lr})
    optimizer = torch.optim.AdamW(groups)

    def collate(items, reverse=False):
        n, k, length = len(items), max(len(x[1]) for x in items), max(len(x[0]) for x in items)
        ids = torch.full((n, length), tokenizer.pad_token_id, dtype=torch.long)
        attn = torch.zeros_like(ids)
        positions = torch.zeros((n, k), dtype=torch.long)
        mask = torch.zeros((n, k), dtype=torch.bool)
        gold = torch.tensor([x[2] for x in items], dtype=torch.long)
        for i, (tokens, markers, _, case, labels) in enumerate(items):
            if reverse:
                q = case["questions"]["next_action"]
                tokens, markers = build_sequence(tokenizer, case["state"], {"t": "choice", "ins": q["instructions"], "crit": dict(reversed(list(q["criteria"].items())))}, config["max_len"], config["head_max_len"])
            ids[i, :len(tokens)] = torch.tensor(tokens)
            attn[i, :len(tokens)] = 1
            positions[i, :len(markers)] = torch.tensor(markers)
            mask[i, :len(markers)] = True
        return [x.cuda() for x in (ids, attn, positions, mask, gold)]

    def evaluate(reverse=False):
        model.eval()
        results = []
        with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
            for start in range(0, len(dev), args.microbatch):
                chunk = dev[start:start+args.microbatch]
                ids, attn, positions, mask, gold = collate(chunk, reverse)
                logits, _ = model(ids, attn, positions, mask, torch.zeros(len(chunk), device="cuda", dtype=torch.long))
                for item, choice in zip(chunk, logits.argmax(-1).tolist()):
                    case, labels = item[3], item[4]
                    selected = (list(reversed(labels)) if reverse else labels)[choice]
                    calls = laya_selected_calls(case, selected)
                    result = {"caseId": case["caseId"], "stage": case["stage"], "expected": case["expectedCalls"], "calls": calls, "selected": selected}
                    if case["specialist"] == "bounded_choice":
                        result.update(choice_result(case, selected))
                    results.append(result)
        report = dict(summarize(results), results=results)
        if dev[0][3]["specialist"] == "bounded_choice":
            report["boundedMetrics"] = choice_metrics(results)
            report["limits"] = "Per-turn choice accuracy under gold scope; see independent rollout for full-path accuracy. Explicit control choices are not native actions."
        return report

    args.out.mkdir(parents=True, exist_ok=True)
    before = evaluate()
    print("base", json.dumps({k: v for k, v in before.items() if k not in ("results", "stages")}), flush=True)
    best, best_step, history = -1.0, 0, []
    shuffled, cursor = [], 0
    started = time.monotonic()
    for step in range(1, args.updates+1):
        batch = []
        while len(batch) < args.batch:
            if cursor >= len(shuffled):
                shuffled = train.copy()
                random.shuffle(shuffled)
                cursor = 0
            take = min(args.batch-len(batch), len(shuffled)-cursor)
            batch.extend(shuffled[cursor:cursor+take])
            cursor += take
        model.train()
        if args.encoder == "frozen":
            model.encoder.eval()
        optimizer.zero_grad(set_to_none=True)
        losses = []
        for start in range(0, len(batch), args.microbatch):
            chunk = batch[start:start+args.microbatch]
            ids, attn, positions, mask, gold = collate(chunk)
            with torch.autocast("cuda", dtype=torch.bfloat16):
                logits, _ = model(ids, attn, positions, mask, torch.zeros(len(chunk), device="cuda", dtype=torch.long))
                loss = torch.nn.functional.cross_entropy(logits.float(), gold) * len(chunk) / len(batch)
            loss.backward()
            losses.append(loss.item())
        torch.nn.utils.clip_grad_norm_([x for x in model.parameters() if x.requires_grad], 1.0)
        optimizer.step()
        if step % args.eval_every == 0 or step == args.updates:
            result = evaluate()
            score = result["exactCallAccuracy"]
            history.append({"update": step, "loss": sum(losses), "development": {k: v for k, v in result.items() if k != "results"}})
            print(f"update {step}/{args.updates} loss={sum(losses):.4f} dev={score:.4f}", flush=True)
            if score > best:
                best, best_step = score, step
                save_file({k: v.detach().half().contiguous().cpu() if v.is_floating_point() else v.detach().contiguous().cpu() for k, v in model.state_dict().items()}, args.out / "model.safetensors")
    model.load_state_dict(load_file(args.out / "model.safetensors"), strict=True)
    after, reversed_order = evaluate(), evaluate(reverse=True)
    for folder in ("encoder", "tokenizer"):
        target = args.out / folder
        if not target.exists():
            target.symlink_to((args.base / folder).resolve(), target_is_directory=True)
    config.update(model_name=args.out.name, temperature_by_options={}, temperature=[1., 1., 1.])
    (args.out / "rl_agent_config.json").write_text(json.dumps(config, indent=2)+"\n")
    report = {"settings": {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()}, "bestUpdate": best_step, "matchedOptimizerUpdates": args.updates, "effectiveBatch": args.batch, "maxTokens": max_tokens, "wallSeconds": time.monotonic()-started, "modelSha256": hashlib.sha256((args.out / "model.safetensors").read_bytes()).hexdigest(), "before": before, "after": after, "reversedOrder": reversed_order, "history": history, "calibrationOpened": False, "finalTestOpened": False, "valueBinder": "host lexical binder for Laya configuration arguments; classifier does not generate values"}
    (args.out / "report.json").write_text(json.dumps(report, indent=2)+"\n")
    print("saved", args.out, "accuracy", after["exactCallAccuracy"], flush=True)


if __name__ == "__main__":
    main()
