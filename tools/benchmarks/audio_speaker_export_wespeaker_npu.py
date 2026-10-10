#!/usr/bin/env python3
"""Export the single-graph WeSpeaker for Qualcomm NPU AOT compilation.

Same I/O as the CPU export (float32[1, 32000] PCM in [-1, 1] -> float32[1, 256]),
with the KaldiFbankNpu front end: fp16-safe scaling (HTP computes float graphs in
fp16; audio_speaker_fp16_sim.py: EER unchanged) and block framing, because QNN HTP
emulation computes the 400-tap stride-160 DFT conv1d wrongly
(android/CyanBridge/tools/litert-npu/htp_emulator_parity.py). Writes the .tflite, a
fixture manifest with host embeddings of this variant (enrollment and verification
must use the same variant), and a report. AOT-compile the result for each SoC and
check it with htp_emulator_parity.py before any device run.
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


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--src", type=Path, required=True)
    p.add_argument("--model-dir", type=Path, required=True)
    p.add_argument("--fixture", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    import litert_torch
    model = WespeakerWaveformModel(load_resnet34(args.src, args.model_dir / "avg_model"), npu=True).eval()
    manifest = json.loads((args.fixture / "manifest.json").read_text())
    clips = manifest["clips"]
    wavs = [torch.from_numpy(np.frombuffer((args.fixture / c["file"]).read_bytes(), "<i2").astype(np.float32) / 32767.0)[None]
            for c in clips]
    with torch.no_grad():
        torch_emb = [model(w)[0].numpy() for w in wavs]
        half = model.half()
        half_emb = [half(w.half())[0].float().numpy() for w in wavs] if torch.cuda.is_available() else None
        model.float()
    path = args.out / "wespeaker_resnet34_lm_2s_npu.tflite"
    litert_torch.convert(model, (wavs[0],)).export(str(path))
    emb, ms = run_tflite(path, wavs)
    cos = lambda a, b: float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))
    fixture = args.out / "fixture-npu"
    fixture.mkdir(exist_ok=True)
    rows = []
    for clip, e in zip(clips, emb):
        (fixture / clip["file"]).write_bytes((args.fixture / clip["file"]).read_bytes())
        rows.append({k: clip[k] for k in ("name", "file", "role", "speaker")} | {"host_embedding": [round(float(x), 6) for x in e]})
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    (fixture / "manifest.json").write_text(json.dumps({"sample_rate": 16000, "samples": manifest["samples"], "format": "pcm_s16le_mono",
                                                       "tflite_sha256": digest, "speakers": manifest["speakers"], "clips": rows}))
    report = {"bytes": path.stat().st_size, "sha256": digest, "hostMedianMs": round(ms, 2),
              "tfliteMinCosVsTorch": min(cos(a, b) for a, b in zip(emb, torch_emb)),
              "torchHalfMinCos": min(cos(a, b) for a, b in zip(half_emb, torch_emb)) if half_emb else None}
    (args.out / "npu_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
