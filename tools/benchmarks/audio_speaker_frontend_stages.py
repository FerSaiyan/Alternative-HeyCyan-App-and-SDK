#!/usr/bin/env python3
"""Export cumulative stages of the WeSpeaker waveform model to bisect NPU numerics.

Each stage is the graph from PCM up to that point (conv1d DFT, power, mel, log,
mean-normalised features, full model), exported with LiteRT Torch so that
android/CyanBridge/tools/litert-npu/htp_emulator_parity.py can compare QNN HTP
emulation against LiteRT CPU stage by stage. Also writes the fixture clips as one
.npy input batch.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audio_speaker_export_wespeaker_litert import WespeakerWaveformModel, load_resnet34  # noqa: E402


class Stage(torch.nn.Module):
    def __init__(self, model, stage):
        super().__init__()
        self.model, self.stage = model, stage

    def forward(self, wav):
        fb = self.model.fbank
        if self.stage == "full":
            return self.model(wav)
        if self.stage == "features":
            return self.model.features(wav)
        if self.model.fp16_safe:
            spec = torch.clamp(fb.spectrum(wav), -250.0, 250.0)
        else:
            spec = torch.nn.functional.conv1d(wav.unsqueeze(1) * 32768.0, fb.basis, stride=160)
        if self.stage == "spec":
            return spec
        half = spec.shape[1] // 2
        power = spec[:, :half] ** 2 + spec[:, half:] ** 2
        if self.stage == "power":
            return power
        lo, hi = (fb.FP16_MIN_NORMAL, fb.FP16_SAFE_MAX) if self.model.fp16_safe else (fb.eps, None)
        mel = torch.clamp(power.transpose(1, 2) @ fb.mel, lo, hi)
        if self.stage == "mel":
            return mel
        return torch.log(mel) + (fb.offset if self.model.fp16_safe else 0.0)  # "logmel"


STAGES = ("spec", "power", "mel", "logmel", "features", "full")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--src", type=Path, required=True)
    p.add_argument("--model-dir", type=Path, required=True)
    p.add_argument("--fixture", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--clips", type=int, default=8)
    p.add_argument("--original", action="store_true", help="int16-scale front end instead of the fp16-safe one")
    p.add_argument("--npu", action="store_true", help="KaldiFbankNpu (block framing) instead of KaldiFbankFp16Safe")
    args = p.parse_args()
    import litert_torch
    args.out.mkdir(parents=True, exist_ok=True)
    model = WespeakerWaveformModel(load_resnet34(args.src, args.model_dir / "avg_model"), fp16_safe=not args.original, npu=args.npu).eval()
    clips = json.loads((args.fixture / "manifest.json").read_text())["clips"][:args.clips]
    wavs = np.stack([np.frombuffer((args.fixture / c["file"]).read_bytes(), "<i2").astype(np.float32)[None] / 32767.0
                     for c in clips])
    np.save(args.out / "inputs.npy", wavs)
    sample = (torch.from_numpy(wavs[0]),)
    for stage in STAGES:
        litert_torch.convert(Stage(model, stage).eval(), sample).export(str(args.out / f"stage_{stage}.tflite"))
        print("exported", stage, flush=True)


if __name__ == "__main__":
    main()
