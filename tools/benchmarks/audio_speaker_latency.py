#!/usr/bin/env python3
"""Host latency per segment (batch 1) for the speaker-filter embedders, GPU and CPU.

Times the full per-segment path including feature extraction, using the same embedder
classes as audio_speaker_embed.py. Gemma models use bf16 on GPU and fp32 on CPU (never fp16).
Writes JSON lines to --out.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import sys
import time

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audio_speaker_embed import build  # noqa: E402

SR = 16000


def cpu_name() -> str:
    try:
        for line in open("/proc/cpuinfo", encoding="utf-8"):
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", required=True, choices=["ecapa", "wespeaker", "eg2", "gemma4"])
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--device", required=True)
    ap.add_argument("--dtype", default="bf16", choices=["bf16", "fp32"])
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--warmup", type=int, default=3)
    ap.add_argument("--runs", type=int, default=20)
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    torch.set_num_threads(args.threads)
    os.environ["ORT_THREADS"] = str(args.threads)
    t0 = time.time()
    emb = build(args.model, args)
    load_s = time.time() - t0
    rng = np.random.default_rng(0)
    cuda = args.device.startswith("cuda")
    for secs in (1.0, 2.0, 3.5):
        wav = (rng.standard_normal(int(secs * SR)) * 0.05).astype(np.float32)
        for _ in range(args.warmup):
            emb([wav])
        times = []
        for _ in range(args.runs):
            if cuda:
                torch.cuda.synchronize()
            s = time.perf_counter()
            emb([wav])
            if cuda:
                torch.cuda.synchronize()
            times.append((time.perf_counter() - s) * 1000)
        rec = {
            "model": args.model, "device": args.device, "dtype": args.dtype if args.model in ("eg2", "gemma4") else "fp32",
            "threads": args.threads, "segment_s": secs, "median_ms": float(np.median(times)),
            "p90_ms": float(np.percentile(times, 90)), "runs": args.runs, "load_s": load_s,
            "cpu": cpu_name(), "gpu": torch.cuda.get_device_name(0) if cuda else None, "torch": torch.__version__,
            "note": "includes feature extraction; eg2 = full ST pipeline (audio tower + text tower + pooling); "
                    "gemma4 = audio tower + soft-token projector only; host hooks add small overhead",
        }
        if cuda:
            rec["max_mem_mb"] = torch.cuda.max_memory_allocated() / 2**20
        print(json.dumps(rec), flush=True)
        with open(args.out, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec) + "\n")


if __name__ == "__main__":
    main()
