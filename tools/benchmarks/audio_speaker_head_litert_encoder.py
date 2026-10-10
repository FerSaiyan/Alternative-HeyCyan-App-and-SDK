#!/usr/bin/env python3
"""Run the STOCK Gemma 4 E2B LiteRT-LM audio encoder section on the benchmark trials.

The ``tf_lite_audio_encoder_hw`` section of ``gemma-4-E2B-it.litertlm`` is a plain TFLite
flatbuffer (input: 128-bin log-mel [1,1,816,128] + mask, output: encoder states
[1,204,1536] + mask; weights mixed INT2/INT4). This script feeds it exactly the trial items
(log-mel from the HF ``Gemma4AudioFeatureExtractor``, right-padded to 816 frames with the mask
cleared), and records:

* ``litert_out_mean``: mask-mean-pooled encoder output (what any app could get from the
  section with the ordinary Interpreter, no graph changes);
* optionally intermediate layer states (``--capture sub 0 1 ...``), which needs the reference
  kernels (``--no-delegate``) and ``experimental_preserve_all_tensors``; this is a host-only
  diagnostic, the Android Interpreter API cannot read intermediate tensors;
* embeddings from speaker heads trained by ``audio_speaker_head_train.py`` (``--heads
  name=path/head.pt``), applied to these quantized on-device features instead of the bf16
  host features they were trained on.

Output: ``<out>.npz`` index-aligned with items.jsonl, plus ``<out>.meta.json`` (timings).
"""

from __future__ import annotations

import argparse
import json
import multiprocessing as mp
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

FRAMES = 816
# Output tensor indices of each encoder layer's final RMSNorm ("layer_k.post_attn/final_ln")
# in the stock E2B section (sha256 eca6f5f0...ccfc); index 317 is the subsampling projection.
LAYER_TENSORS = {"sub": 317, **{str(k): v for k, v in enumerate(
    [503, 687, 871, 1055, 1239, 1423, 1607, 1791, 1975, 2159, 2343, 2527])}}

_G: dict = {}


def _init(tflite: str, model_dir: str, trials: str, capture: list[str], no_delegate: bool, heads: dict, threads: int):
    from ai_edge_litert.interpreter import Interpreter, OpResolverType
    from transformers import AutoFeatureExtractor

    kw = {"model_path": tflite, "num_threads": threads}
    if no_delegate:
        kw["experimental_op_resolver_type"] = OpResolverType.BUILTIN_WITHOUT_DEFAULT_DELEGATES
        kw["experimental_preserve_all_tensors"] = True
    it = Interpreter(**kw)
    it.allocate_tensors()
    _G["it"] = it
    ins = {d["name"]: d["index"] for d in it.get_input_details()}
    _G["in_mask"] = ins["serving_default_mask:0"]
    _G["in_x"] = ins["serving_default_src_inputs:0"]
    outs = {d["name"]: d["index"] for d in it.get_output_details()}
    _G["out_x"] = outs["StatefulPartitionedCall:0"]
    _G["out_m"] = outs["StatefulPartitionedCall:1"]
    _G["fe"] = AutoFeatureExtractor.from_pretrained(model_dir)
    _G["pcm"] = np.load(os.path.join(trials, "audio_pcm16.npy"), mmap_mode="r")
    _G["off"] = np.load(os.path.join(trials, "offsets.npy"))
    _G["capture"] = capture
    _G["heads"] = {}
    if heads:
        import torch

        import audio_speaker_head_train as H

        torch.set_num_threads(1)
        for name, path in heads.items():
            ck = torch.load(path, map_location="cpu")
            a = ck["args"]
            dims = {"out": 1536}
            dim = dims.get(a["reps"][0], 1024)
            head = H.SpeakerHead(len(a["reps"]), dim, a["head"])
            head.load_state_dict({k[5:]: v for k, v in ck["state"].items() if k.startswith("head.")})
            _G["heads"][name] = (head.eval(), a["reps"])


def _run(i: int):
    it, fe = _G["it"], _G["fe"]
    wav = np.asarray(_G["pcm"][_G["off"][i] : _G["off"][i + 1]], dtype=np.float32) / 32767.0
    f = fe([wav], sampling_rate=16000, return_tensors="np", padding=True)
    x, m = f["input_features"][0], f["input_features_mask"][0]
    t = min(x.shape[0], FRAMES)
    xp = np.zeros((1, 1, FRAMES, 128), np.float32)
    mp_ = np.zeros((1, 1, FRAMES), bool)
    xp[0, 0, :t] = x[:t]
    mp_[0, 0, :t] = m[:t]
    it.set_tensor(_G["in_x"], xp)
    it.set_tensor(_G["in_mask"], mp_)
    t0 = time.perf_counter()
    it.invoke()
    dt = time.perf_counter() - t0
    out = it.get_tensor(_G["out_x"])[0]
    om = it.get_tensor(_G["out_m"])[0].astype(bool)
    res = {"litert_out_mean": out[om].mean(0).astype(np.float32)}
    frames = {"out": out}
    for c in _G["capture"]:
        h = it.get_tensor(LAYER_TENSORS[c]).reshape(FRAMES // 4, -1)
        frames[c] = h
        res[f"litert_{'layer%02d' % int(c) if c.isdigit() else c}_mean"] = h[om].mean(0).astype(np.float16)
    if _G["heads"]:
        import torch

        with torch.no_grad():
            mask = torch.from_numpy(om)[None]
            for name, (head, reps) in _G["heads"].items():
                if all(r in frames for r in reps):
                    e = head([torch.from_numpy(np.ascontiguousarray(frames[r]))[None] for r in reps], mask)
                    res[f"litehead_{name}"] = e[0].numpy().astype(np.float32)
    return i, res, dt


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tflite", required=True)
    ap.add_argument("--model-dir", required=True, help="HF gemma-4-E2B-it dir (feature extractor config only)")
    ap.add_argument("--trials", required=True)
    ap.add_argument("--out", required=True, help="output path prefix")
    ap.add_argument("--capture", nargs="*", default=[])
    ap.add_argument("--no-delegate", action="store_true")
    ap.add_argument("--heads", nargs="*", default=[], help="name=path/head.pt")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    if args.capture and not args.no_delegate:
        ap.error("--capture needs --no-delegate (XNNPACK does not materialise intermediates)")

    off = np.load(os.path.join(args.trials, "offsets.npy"))
    n = len(off) - 1
    idx = list(range(args.limit or n))
    heads = dict(h.split("=", 1) for h in args.heads)
    store: dict[str, np.ndarray] = {}
    times = []
    t0 = time.time()
    ctx = mp.get_context("spawn")
    with ctx.Pool(args.workers, initializer=_init,
                  initargs=(args.tflite, args.model_dir, args.trials, args.capture, args.no_delegate, heads, args.threads)) as pool:
        for k, (i, res, dt) in enumerate(pool.imap_unordered(_run, idx, chunksize=8)):
            for key, v in res.items():
                if key not in store:
                    store[key] = np.zeros((len(idx), v.shape[0]), v.dtype)
                store[key][i] = v
            times.append(dt)
            if (k + 1) % 1000 == 0:
                print(f"{k + 1}/{len(idx)} {time.time() - t0:.0f}s median invoke {1000 * np.median(times):.0f} ms", flush=True)
    np.savez(args.out + ".npz", **store)
    meta = {"tflite": args.tflite, "n_items": len(idx), "capture": args.capture, "no_delegate": args.no_delegate,
            "heads": heads, "workers": args.workers, "threads": args.threads,
            "invoke_ms_median": 1000 * float(np.median(times)), "invoke_ms_p90": 1000 * float(np.percentile(times, 90)),
            "wall_s": time.time() - t0, "keys": {k: list(v.shape) for k, v in store.items()}}
    json.dump(meta, open(args.out + ".meta.json", "w"), indent=2)
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
