#!/usr/bin/env python3
"""Fine-tune a bi-encoder on bounded menus with the Laya specialist's budget.

Loss is cross-entropy over cosine(query, offered option)/temperature within each
turn's own menu, the bi-encoder analogue of Laya's marker head. Updates, batch,
dev-snapshot selection and seed match train_laya_specialist.py defaults used for
the v3 campaign (180 updates, batch 16, evaluate every 60). Calibration and
sealed cohorts are never read.
"""
import argparse
import hashlib
import json
import random
import time
from pathlib import Path

from bounded_embedding import embed, load_text_embedder, menu_texts
from bounded_specialist_contract import choice_metrics, choice_result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ("base", "data", "out"):
        p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--updates", type=int, default=180)
    p.add_argument("--batch", type=int, default=16)
    p.add_argument("--microbatch", type=int, default=4)
    p.add_argument("--eval-every", type=int, default=60)
    p.add_argument("--lr", type=float, default=0.00002)
    p.add_argument("--temperature", type=float, default=0.05)
    p.add_argument("--truncate-dim", type=int, choices=(128, 256, 512, 768))
    p.add_argument("--request-only", action="store_true")
    p.add_argument("--seed", type=int, default=20261003)
    args = p.parse_args()
    if min(args.batch, args.updates, args.eval_every, args.microbatch) <= 0 or args.batch % args.microbatch:
        p.error("Positive updates/eval/microbatch and divisible effective batch required")
    if (args.out / "model.safetensors").exists():
        p.error("Refuse to overwrite an existing trained checkpoint; use a fresh --out")
    import torch
    torch.manual_seed(args.seed)
    random.seed(args.seed)
    torch.backends.cuda.matmul.allow_tf32 = True

    def load(split):
        return [json.loads(s) for s in (args.data / f"{split}.canonical.jsonl").read_text().splitlines()]

    train, dev = load("train"), load("development")
    assert not ({x["groupId"] for x in train} & {x["groupId"] for x in dev})
    model = load_text_embedder(args.base, "cuda", torch.float32)
    model[0].auto_model.gradient_checkpointing_enable()
    optimizer = torch.optim.AdamW([x for x in model.parameters() if x.requires_grad], lr=args.lr)

    def menu_logits(rows):
        queries, documents, spans = [], [], []
        for row in rows:
            query, labels = menu_texts(row["turn"], request_only=args.request_only)
            queries.append(query)
            spans.append((len(documents), len(documents)+len(labels)))
            documents.extend(labels)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            q, d = embed(model, queries, args.truncate_dim), embed(model, documents, args.truncate_dim)
        return [d[a:b] @ q[i] / args.temperature for i, (a, b) in enumerate(spans)]

    def evaluate():
        model.eval()
        results = []
        with torch.no_grad():
            for start in range(0, len(dev), args.microbatch):
                chunk = dev[start:start+args.microbatch]
                for row, logits in zip(chunk, menu_logits(chunk)):
                    results.append(choice_result(row, list(row["turn"]["options"])[logits.argmax().item()]))
        return dict(choice_metrics(results), results=results)

    args.out.mkdir(parents=True, exist_ok=True)
    before = evaluate()
    print("base", before["correct"], "/", before["cases"], flush=True)
    best, best_step, history, shuffled, cursor = -1, 0, [], [], 0
    started = time.monotonic()
    for step in range(1, args.updates+1):
        batch = []
        while len(batch) < args.batch:
            if cursor >= len(shuffled):
                shuffled, cursor = train.copy(), 0
                random.shuffle(shuffled)
            take = min(args.batch-len(batch), len(shuffled)-cursor)
            batch.extend(shuffled[cursor:cursor+take])
            cursor += take
        model.train()
        optimizer.zero_grad(set_to_none=True)
        losses = []
        for start in range(0, len(batch), args.microbatch):
            chunk = batch[start:start+args.microbatch]
            loss = sum(torch.nn.functional.cross_entropy(logits[None], torch.tensor([list(row["turn"]["options"]).index(row["expectedChoice"])], device="cuda"))
                       for row, logits in zip(chunk, menu_logits(chunk))) / len(batch)
            loss.backward()
            losses.append(loss.item())
        torch.nn.utils.clip_grad_norm_([x for x in model.parameters() if x.requires_grad], 1.0)
        optimizer.step()
        if step % args.eval_every == 0 or step == args.updates:
            result = evaluate()
            history.append({"update": step, "loss": sum(losses), "development": {k: v for k, v in result.items() if k != "results"}})
            print(f"update {step}/{args.updates} loss={sum(losses):.4f} dev={result['correct']}/{result['cases']}", flush=True)
            if result["correct"] > best:
                best, best_step = result["correct"], step
                model.save(str(args.out), safe_serialization=True)
    model = load_text_embedder(args.out, "cuda", torch.float32)
    after = evaluate()
    report = {"settings": {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()}, "bestUpdate": best_step,
              "selection": "best development snapshot, as in train_laya_specialist.py", "wallSeconds": time.monotonic()-started,
              "modelSha256": hashlib.sha256((args.out / "model.safetensors").read_bytes()).hexdigest(),
              "before": before, "after": after, "history": history, "calibrationOpened": False, "finalTestOpened": False}
    (args.out / "report.json").write_text(json.dumps(report, indent=2)+"\n")
    print("saved", args.out, "development", after["correct"], "/", after["cases"], flush=True)


if __name__ == "__main__":
    main()
