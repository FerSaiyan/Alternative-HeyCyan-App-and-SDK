#!/usr/bin/env python3
"""Simulate Qualcomm HTP fp16 execution of the WeSpeaker waveform model.

HTP runs float graphs in fp16 (max 65504). The in-graph Kaldi front end works at
int16 scale (power spectra up to ~1e14), so a fully offloaded float graph may
overflow. Compares, on the same test-clean trials as audio_speaker_npu_quantize.py:
fp32; everything fp16 (what an AOT float graph would compute); fp32 front end +
fp16 ResNet (front end kept on CPU); and the fp16-safe single graph
(KaldiFbankFp16Safe) in fp32 and fully in fp16.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audio_speaker_export_wespeaker_litert import WespeakerWaveformModel, load_resnet34  # noqa: E402
from audio_speaker_npu_quantize import crops, eer  # noqa: E402


def run(model, items, front_dtype, resnet_dtype, device):
    vectors, nonfinite = [], 0
    with torch.no_grad():
        for _, wav in items:
            x = torch.from_numpy(wav).to(device, front_dtype)
            feats = model.features(x)
            v = model.resnet(feats.to(resnet_dtype))[-1].float()[0].cpu().numpy().astype(np.float64)
            if not np.all(np.isfinite(v)):
                nonfinite += 1
                v = np.nan_to_num(v)
            vectors.append(v / (np.linalg.norm(v) + 1e-12))
    return np.stack(vectors), nonfinite


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--src", type=Path, required=True)
    p.add_argument("--model-dir", type=Path, required=True)
    p.add_argument("--trials", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    device = "cuda"
    enroll = crops(args.trials, lambda r: r["split"] == "test-clean" and r["kind"] == "enroll")
    test = crops(args.trials, lambda r: r["split"] == "test-clean" and r["kind"] == "test" and r["seconds"] == 2.0)
    report = {"enroll": len(enroll), "test": len(test)}
    base = WespeakerWaveformModel(load_resnet34(args.src, args.model_dir / "avg_model")).to(device)
    ref_t = None
    for name, front, resnet, safe in (("fp32", torch.float32, torch.float32, False),
                                      ("fp16_all", torch.float16, torch.float16, False),
                                      ("fp32_front_fp16_resnet", torch.float32, torch.float16, False),
                                      ("fp16safe_fp32", torch.float32, torch.float32, True),
                                      ("fp16safe_fp16_all", torch.float16, torch.float16, True)):
        model = WespeakerWaveformModel(base.resnet, fp16_safe=safe).to(device)
        model.fbank.to(front)
        model.resnet.to(resnet)
        e, bad_e = run(model, enroll, front, resnet, device)
        t, bad_t = run(model, test, front, resnet, device)
        if ref_t is None:
            ref_t = t
        report[name] = {"eerPercent": round(eer(enroll, e, test, t), 3), "nonFiniteEmbeddings": bad_e + bad_t,
                        "minCosVsFp32": float(np.min(np.sum(t * ref_t, 1))), "meanCosVsFp32": float(np.mean(np.sum(t * ref_t, 1)))}
        print(name, report[name], flush=True)
        base.resnet.float()
    args.out.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
