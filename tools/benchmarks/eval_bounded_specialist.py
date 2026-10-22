#!/usr/bin/env python3
"""State-derived oracle-turn and self-routed episode evaluation; no side effects."""
import argparse
import ctypes
import hashlib
import json
import os
import sys
import time
from pathlib import Path

from bounded_specialist_contract import INSTRUCTIONS, choice_metrics, choice_result, decode_choice, rollout_episode
from specialist_metrics import needle_proposals


def presented_options(turn, reverse=False):
    """Change display order only; preserve host-owned IDs and bindings."""
    items=list(turn["options"].items())
    return dict(reversed(items) if reverse else items)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--backend", choices=("laya", "needle", "embedding"), required=True)
    for name in ("model", "data", "report"):
        p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--source", type=Path)
    p.add_argument("--library", type=Path)
    p.add_argument("--flat", action="store_true", help="State-matched flat control: expose every eligible leaf at the root")
    p.add_argument("--reverse-options", action="store_true", help="Reverse presentation while preserving every bound choice ID/effect")
    p.add_argument("--truncate-dim", type=int, choices=(128, 256, 512, 768), help="Embedding backend: Matryoshka dimension")
    p.add_argument("--device", default="cuda", help="Embedding backend: torch device")
    p.add_argument("--request-only", action="store_true", help="Embedding backend: embed the request without host status")
    args = p.parse_args()
    latencies = []
    if args.backend == "laya":
        import torch
        from transformers import AutoTokenizer
        from safetensors.torch import load_file
        if not args.source:
            p.error("Laya requires --source")
        sys.path.insert(0, str(args.source.resolve()))
        from laya.common import build_model, build_sequence, render_options
        torch.set_num_threads(4)
        config = json.loads((args.model/"rl_agent_config.json").read_text())
        tok = AutoTokenizer.from_pretrained(args.model/"tokenizer", local_files_only=True)
        model = build_model(config, encoder_dir=str(args.model/"encoder"))
        model.encoder.config.reference_compile = False
        model.load_state_dict(load_file(args.model/"model.safetensors"), strict=True)
        model.cuda().eval()

        def predict(turn):
            options=presented_options(turn,args.reverse_options)
            question = {"t": "choice", "ins": INSTRUCTIONS, "crit": {key: bound["label"] for key, bound in options.items()}}
            if any(len(tok.encode(" "+label, add_special_tokens=False)) > 48 for label in render_options(question)):
                raise ValueError("Clipped Laya option")
            ids, markers = build_sequence(tok, turn["query"], question, config["max_len"], config["head_max_len"])
            state = tok.encode(turn["query"], add_special_tokens=False)
            if ids[-len(state)-1:-1] != state or len(markers) != len(turn["options"]):
                raise ValueError("Clipped Laya status/options")
            tokens = torch.tensor([ids], device="cuda")
            with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
                logits, _ = model(tokens, torch.ones_like(tokens), torch.tensor([markers], device="cuda"), torch.ones((1,len(markers)),dtype=torch.bool,device="cuda"), torch.zeros(1,device="cuda",dtype=torch.long))
            return list(options)[logits.argmax(-1).item()], None
    elif args.backend == "embedding":
        import torch
        from bounded_embedding import embed, load_text_embedder, menu_texts
        torch.set_num_threads(4)
        dtype = torch.bfloat16 if args.device == "cuda" else torch.float32
        model = load_text_embedder(args.model, args.device, dtype).eval()
        documents = {}

        def predict(turn):
            # Option labels are host-owned constants, so their vectors are cached
            # as a phone would precompute them; timing covers the query encode.
            options = presented_options(turn, args.reverse_options)
            query, labels = menu_texts(turn, options, args.request_only)
            missing = [t for t in labels if t not in documents]
            with torch.no_grad():
                if missing:
                    documents.update(zip(missing, embed(model, missing, args.truncate_dim)))
                if args.device == "cuda":
                    torch.cuda.synchronize()
                started = time.perf_counter()
                vector = embed(model, [query], args.truncate_dim)[0]
                if args.device == "cuda":
                    torch.cuda.synchronize()
                latencies.append((time.perf_counter()-started)*1000)
                scores = torch.stack([documents[t] for t in labels]) @ vector
            return list(options)[scores.argmax().item()], {"cosine": [round(x, 5) for x in scores.tolist()]}
    else:
        if not args.library:
            p.error("Needle requires --library")
        from benchmark_needle_mobile_actions import load_engine
        os.environ["NEEDLE_TELEMETRY"] = "0"
        lib = load_engine(args.library.resolve())
        buffer = ctypes.create_string_buffer(args.model.read_bytes())
        if lib.needle_load(buffer, len(buffer)-1) != 0:
            raise RuntimeError("needle_load failed")

        def predict(turn):
            options=presented_options(turn,args.reverse_options)
            tools = [{"type": "function", "function": {"name": key, "description": bound["label"], "parameters": {"type": "object", "properties": {}, "required": []}}} for key, bound in options.items()]
            prefix = lib.needle_init(INSTRUCTIONS.encode(), json.dumps(tools,separators=(",", ":")).encode(), None)
            raw = {}
            if prefix >= 0:
                output = ctypes.create_string_buffer(65536)
                rc = lib.needle_complete(turn["query"].encode(), 192, output, len(output))
                if rc >= 0:
                    try:
                        raw = json.loads(output.value.decode())
                    except (ValueError, UnicodeDecodeError):
                        pass
            lib.needle_reset()
            return decode_choice({"turn": turn}, needle_proposals(raw)), raw

    canonical = [json.loads(s) for s in (args.data/"development.canonical.jsonl").read_text().splitlines()]
    episodes = [json.loads(s) for s in (args.data/"development.episodes.jsonl").read_text().splitlines()]
    contract=json.loads((args.data/"manifest.json").read_text())["contract"]
    episodes=[dict(ep,contract=contract) for ep in episodes]
    started = time.monotonic()
    oracle = []
    for row in canonical:
        selected, raw = predict(row["turn"])
        oracle.append(dict(choice_result(row, selected), raw=raw))
    rollouts = [rollout_episode(ep, predict, flat=args.flat) for ep in episodes]
    report = {"backend": args.backend, "model": str(args.model), "flat": args.flat, "oracleTurns": dict(choice_metrics(oracle), results=oracle),
              "reversedOptions": args.reverse_options,
              "contract": contract,
              "modelSha256": hashlib.sha256((args.model if args.backend=="needle" else args.model/"model.safetensors").read_bytes()).hexdigest(),
              "truncateDim": args.truncate_dim, "requestOnly": args.request_only, "device": args.device if args.backend == "embedding" else "cuda",
              "queryEncodeMs": {"p50": sorted(latencies)[len(latencies)//2], "p95": sorted(latencies)[int(len(latencies)*0.95)], "calls": len(latencies)} if latencies else None,
              "rolloutEpisodes": len(rollouts), "rolloutCorrect": sum(ep["correct"] for ep in rollouts),
              "rolloutAccuracy": sum(ep["correct"] for ep in rollouts)/max(1,len(rollouts)), "rollouts": rollouts,
              "wallSeconds": time.monotonic()-started, "calibrationOpened": False, "finalTestOpened": False,
              "hostVerifiedMutation": False,
              "limits": "Generated host-state simulation, no download/action/readback performed. Rollout success is exact proposed leaf/clarification, not verified setting change or phone completion."}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps({k:v for k,v in report.items() if k not in ("oracleTurns","rollouts")},indent=2),flush=True)
    print("oracle choice accuracy",report["oracleTurns"]["choiceAccuracy"],flush=True)


if __name__ == "__main__":
    main()
