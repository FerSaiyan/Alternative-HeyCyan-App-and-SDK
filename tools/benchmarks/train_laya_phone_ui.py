#!/usr/bin/env python3
"""Opt-in head-only supervised Laya pilot on *synthetic* phone UI decisions.

Uses local model files, freezes the encoder, saves a normal Laya checkpoint and
reports held-out synthetic accuracy. This does NOT establish live-phone safety.
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import sys
from collections import Counter
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--epochs", type=int, default=4)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    args = parser.parse_args()
    if args.epochs < 1 or args.batch < 1 or not (args.base / "model.safetensors").is_file():
        parser.error("Require a local base checkpoint, positive --epochs and --batch")
    import torch
    from safetensors.torch import load_file, save_file
    from transformers import AutoTokenizer

    sys.path.insert(0, str(args.source.resolve()))
    from laya.common import build_model, build_sequence  # noqa: PLC0415

    torch.manual_seed(20260927)
    random.seed(20260927)
    torch.set_num_threads(4)
    config = json.loads((args.base / "rl_agent_config.json").read_text())
    config.update(max_len=512, head_max_len=256)
    tokenizer = AutoTokenizer.from_pretrained(args.base / "tokenizer")

    def load_rows(split):
        cases = [json.loads(line) for line in (args.data / f"{split}.laya.jsonl").read_text().splitlines()]
        items = []
        for case in cases:
            q = case["questions"]["next_action"]
            labels = list(q["criteria"])
            gold = case["expected"]["next_action"]
            if gold not in labels:
                raise ValueError(f"{case['caseId']}: gold option was clipped")
            ids, markers = build_sequence(tokenizer, case["state"],
                                          {"t": "choice", "ins": q["instructions"], "crit": q["criteria"]},
                                          config["max_len"], config["head_max_len"])
            if len(markers) != len(labels):
                raise ValueError(f"{case['caseId']}: head or state truncated an option")
            items.append((ids, markers, labels.index(gold), case["tags"][0]))
        return items

    train, validation, test = (load_rows(name) for name in ("train", "validation", "test"))

    def collate(batch):
        n = len(batch)
        length = max(len(row[0]) for row in batch)
        k = max(len(row[1]) for row in batch)
        ids = torch.full((n, length), tokenizer.pad_token_id, dtype=torch.long)
        attn = torch.zeros_like(ids)
        positions = torch.zeros((n, k), dtype=torch.long)
        mask = torch.zeros((n, k), dtype=torch.bool)
        labels = torch.tensor([row[2] for row in batch], dtype=torch.long)
        for i, (tokens, markers, _, _) in enumerate(batch):
            ids[i, :len(tokens)] = torch.tensor(tokens)
            attn[i, :len(tokens)] = 1
            positions[i, :len(markers)] = torch.tensor(markers)
            mask[i, :len(markers)] = True
        return [x.to(args.device) for x in (ids, attn, positions, mask, labels)]

    model = build_model(config, encoder_dir=str(args.base / "encoder"))
    model.load_state_dict(load_file(args.base / "model.safetensors"), strict=True)
    model.to(args.device)
    for param in model.encoder.parameters():
        param.requires_grad_(False)
    optimizer = torch.optim.AdamW((p for p in model.parameters() if p.requires_grad), lr=0.00012)

    def evaluate(items):
        model.eval()
        counts, correct = Counter(), Counter()
        with torch.no_grad():
            for i in range(0, len(items), args.batch):
                chunk = items[i:i + args.batch]
                ids, attn, positions, mask, labels = collate(chunk)
                with torch.autocast(args.device, dtype=torch.bfloat16, enabled=args.device == "cuda"):
                    logits, _ = model(ids, attn, positions, mask,
                                      torch.zeros(len(chunk), device=args.device, dtype=torch.long))
                for row, predicted, gold in zip(chunk, logits.argmax(-1).tolist(), labels.tolist()):
                    counts[row[3]] += 1
                    correct[row[3]] += predicted == gold
        return {"accuracy": sum(correct.values()) / len(items), "cases": len(items),
                "stages": {stage: {"correct": correct[stage], "cases": n} for stage, n in counts.items()}}

    before = {"validation": evaluate(validation), "test": evaluate(test)}
    print("before", json.dumps(before), flush=True)
    best = (-1.0, 0)
    best_state = None
    for epoch in range(args.epochs):
        model.train()
        model.encoder.eval()
        shuffled = train.copy()
        random.Random(20260927 + epoch).shuffle(shuffled)
        losses = []
        for i in range(0, len(shuffled), args.batch):
            chunk = shuffled[i:i + args.batch]
            ids, attn, positions, mask, labels = collate(chunk)
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(args.device, dtype=torch.bfloat16, enabled=args.device == "cuda"):
                logits, _ = model(ids, attn, positions, mask,
                                  torch.zeros(len(chunk), device=args.device, dtype=torch.long))
                loss = torch.nn.functional.cross_entropy(logits.float(), labels)
            loss.backward()
            torch.nn.utils.clip_grad_norm_((p for p in model.parameters() if p.requires_grad), 1.0)
            optimizer.step()
            losses.append(loss.detach().item())
        val = evaluate(validation)
        print(f"epoch {epoch + 1}/{args.epochs}: loss {sum(losses) / len(losses):.4f} "
              f"val {val['accuracy']:.4f}", flush=True)
        if val["accuracy"] > best[0]:
            best = (val["accuracy"], epoch + 1)
            best_state = {k: value.detach().cpu().clone() for k, value in model.state_dict().items()}
    model.load_state_dict(best_state)
    after = {"validation": evaluate(validation), "test": evaluate(test)}
    args.out.mkdir(parents=True, exist_ok=True)
    save_file({k: v.half().contiguous().cpu() if v.is_floating_point() else v.cpu().contiguous()
               for k, v in model.state_dict().items()}, args.out / "model.safetensors")
    shutil.copytree(args.base / "encoder", args.out / "encoder", dirs_exist_ok=True)
    shutil.copytree(args.base / "tokenizer", args.out / "tokenizer", dirs_exist_ok=True)
    config.update(model_name="phone-ui-synthetic-head-v1", fine_tuned=True,
                  head_max_len_train=256, temperature_by_options={},
                  temperature=[1.0, 1.0, 1.0])
    (args.out / "rl_agent_config.json").write_text(json.dumps(config, indent=2) + "\n")
    report = {"mode": "synthetic_pilot_head_only_frozen_encoder_ce", "base": str(args.base),
              "data": str(args.data), "epochs": args.epochs, "bestEpoch": best[1],
              "before": before, "after": after,
              "limits": "Synthetic templated data only; six real Tasker observations reserved for shadow evaluation."}
    (args.out / "training_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("after", json.dumps(after), "saved", args.out, flush=True)


if __name__ == "__main__":
    main()
