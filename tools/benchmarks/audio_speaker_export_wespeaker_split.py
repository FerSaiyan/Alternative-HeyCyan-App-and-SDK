#!/usr/bin/env python3
"""Split WeSpeaker into a CPU front end and an NPU-compilable ResNet head.

Qualcomm HTP computes float graphs in fp16. The Kaldi front end overflows fp16
(int16-scale power spectra), while the ResNet is fp16-safe (see
audio_speaker_fp16_sim.py: fp16 everywhere -> 100% non-finite, fp32 front end +
fp16 ResNet -> EER unchanged). This writes:
  wespeaker_frontend_2s.tflite : float32[1, 32000] PCM in [-1, 1] -> float32[1, 198, 80] (CPU)
  wespeaker_head_2s.tflite     : float32[1, 198, 80] -> float32[1, 256] (AOT-compile for the NPU)
and checks that front end -> head reproduces the single-graph model.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audio_speaker_export_wespeaker_litert import WespeakerWaveformModel, load_resnet34, run_tflite  # noqa: E402


class FrontEnd(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, wav):
        return self.model.features(wav)


class Head(torch.nn.Module):
    def __init__(self, resnet):
        super().__init__()
        self.resnet = resnet

    def forward(self, feats):
        return self.resnet(feats)[-1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--src", type=Path, required=True)
    p.add_argument("--model-dir", type=Path, required=True)
    p.add_argument("--fixture", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    import litert_torch
    from ai_edge_litert.interpreter import Interpreter
    model = WespeakerWaveformModel(load_resnet34(args.src, args.model_dir / "avg_model")).eval()
    manifest = json.loads((args.fixture / "manifest.json").read_text())
    wavs = [torch.from_numpy(np.frombuffer((args.fixture / c["file"]).read_bytes(), "<i2").astype(np.float32) / 32767.0)[None]
            for c in manifest["clips"]]
    with torch.no_grad():
        feats = model.features(wavs[0])
        full = [model(w)[0].numpy() for w in wavs]
    front_path, head_path = args.out / "wespeaker_frontend_2s.tflite", args.out / "wespeaker_head_2s.tflite"
    litert_torch.convert(FrontEnd(model).eval(), (wavs[0],)).export(str(front_path))
    litert_torch.convert(Head(model.resnet).eval(), (feats,)).export(str(head_path))

    front = Interpreter(model_path=str(front_path), num_threads=4)
    front.allocate_tensors()
    fi, fo = front.get_input_details()[0], front.get_output_details()[0]
    head = Interpreter(model_path=str(head_path), num_threads=4)
    head.allocate_tensors()
    hi, ho = head.get_input_details()[0], head.get_output_details()[0]
    composed = []
    for w in wavs:
        front.set_tensor(fi["index"], w.numpy())
        front.invoke()
        head.set_tensor(hi["index"], front.get_tensor(fo["index"]))
        head.invoke()
        composed.append(head.get_tensor(ho["index"])[0].copy())
    cos = lambda a, b: float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))
    report = {
        "frontend": {"bytes": front_path.stat().st_size, "sha256": hashlib.sha256(front_path.read_bytes()).hexdigest(),
                     "input": list(fi["shape"]), "output": list(fo["shape"])},
        "head": {"bytes": head_path.stat().st_size, "sha256": hashlib.sha256(head_path.read_bytes()).hexdigest(),
                 "input": list(hi["shape"]), "output": list(ho["shape"])},
        "composedMinCosVsTorch": min(cos(a, b) for a, b in zip(composed, full)),
    }
    (args.out / "split_report.json").write_text(json.dumps(report, indent=2, default=int) + "\n")
    print(json.dumps(report, indent=2, default=int))


if __name__ == "__main__":
    main()
