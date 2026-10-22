#!/usr/bin/env python3
"""Speaker-verification EER of an AOT-compiled WeSpeaker under QNN HTP emulation.

Runs the AOT model's QNN context with the QAIRT x86 HTP backend (the DSP's fp16
numerics) and the plain .tflite with LiteRT CPU on the same LibriSpeech test-clean
trials as audio_speaker_npu_quantize.py (2 s crops, pooled 5-phrase templates), and
reports EER for both plus per-crop cosine between them. Run with the NPU venv whose
QAIRT matches --qairt-root.
"""
import argparse
import json
import sys
import tempfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1] / "android/CyanBridge/tools/litert-npu"))
from audio_speaker_npu_quantize import crops, eer  # noqa: E402
from htp_emulator_parity import contexts, cpu_reference, emulate  # noqa: E402


def unit(vectors):
    v = np.stack([x.ravel().astype(np.float64) for x in vectors])
    return v / (np.linalg.norm(v, axis=1, keepdims=True) + 1e-12)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--plain", type=Path, required=True, help="plain .tflite (CPU reference)")
    p.add_argument("--aot", type=Path, required=True, help="AOT-compiled .tflite of the same model")
    p.add_argument("--trials", type=Path, required=True)
    p.add_argument("--qairt-root", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    enroll = crops(args.trials, lambda r: r["split"] == "test-clean" and r["kind"] == "enroll")
    test = crops(args.trials, lambda r: r["split"] == "test-clean" and r["kind"] == "test" and r["seconds"] == 2.0)
    items = enroll + test
    samples = [wav for _, wav in items]
    ctx = contexts(args.aot)
    assert len(ctx) == 1, f"expected one context, got {len(ctx)}"
    with tempfile.TemporaryDirectory() as tmp:
        htp = unit(emulate(ctx[0], samples, args.qairt_root, Path(tmp)))
    cpu = unit(cpu_reference(args.plain, samples))
    n = len(enroll)
    cos = np.sum(htp * cpu, axis=1)
    report = {"enroll": n, "test": len(test),
              "cpuEerPercent": round(eer(enroll, cpu[:n], test, cpu[n:]), 3),
              "htpEmulatedEerPercent": round(eer(enroll, htp[:n], test, htp[n:]), 3),
              "htpVsCpuMinCos": float(cos.min()), "htpVsCpuMeanCos": float(cos.mean()),
              "nonFinite": int((~np.isfinite(htp)).any(axis=1).sum())}
    args.out.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
