#!/usr/bin/env python3
"""Static (NPU-friendly) quantization of a waveform speaker-embedding .tflite.

Qualcomm HTP rejects weight-only int8 (explicit per-channel DEQUANTIZE), so the
NPU needs static quantization: integer weights and activations with calibrated
ranges. Calibration uses the trial set's test-other dev items and MUSAN
non-speech only; evaluation uses test-clean: cosine parity to the fp32 model and
a speaker-verification EER (mean template over each draw's 5 enrollment crops,
2.0 s test items, scored against both draws' templates and pooled, as in
audio_speaker_eval.py).
"""
import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy as np

SAMPLES = 32000


def crops(trials: Path, predicate, limit=None):
    audio = np.load(trials / "audio_pcm16.npy", mmap_mode="r")
    offsets = np.load(trials / "offsets.npy")
    out = []
    for row in map(json.loads, (trials / "items.jsonl").read_text().splitlines()):
        if not predicate(row):
            continue
        start, end = int(offsets[row["idx"]]), int(offsets[row["idx"] + 1])
        clip = np.asarray(audio[start:end], dtype=np.float32) / 32767.0
        if clip.size < SAMPLES:
            clip = np.pad(clip, (0, SAMPLES - clip.size))
        out.append((row, clip[:SAMPLES][None]))
        if limit and len(out) >= limit:
            break
    return out


def embed(path: Path, items):
    from ai_edge_litert.interpreter import Interpreter
    interp = Interpreter(model_path=str(path), num_threads=8)
    interp.allocate_tensors()
    inp, outp = interp.get_input_details()[0], interp.get_output_details()[0]
    vectors = []
    in_scale, in_zero = inp["quantization"]
    out_scale, out_zero = outp["quantization"]
    for _, wav in items:
        if inp["dtype"] != np.float32:  # fully static graphs take quantized PCM
            info = np.iinfo(inp["dtype"])
            wav = np.clip(np.round(wav / in_scale) + in_zero, info.min, info.max).astype(inp["dtype"])
        interp.set_tensor(inp["index"], wav)
        interp.invoke()
        v = interp.get_tensor(outp["index"])[0].astype(np.float64)
        if outp["dtype"] != np.float32:
            v = (v - out_zero) * out_scale
        vectors.append(v / (np.linalg.norm(v) + 1e-12))
    return np.stack(vectors)


def eer(enroll_items, enroll_vecs, test_items, test_vecs):
    templates = {}
    for (row, _), v in zip(enroll_items, enroll_vecs):
        templates.setdefault((row["draw"], row["speaker"]), []).append(v)
    keys = sorted(templates)
    t = np.stack([np.mean(templates[k], 0) / np.linalg.norm(np.mean(templates[k], 0)) for k in keys])
    target, nontarget = [], []
    for (row, _), v in zip(test_items, test_vecs):
        scores = t @ v
        for k, s in zip(keys, scores):
            (target if k[1] == row["speaker"] else nontarget).append(s)
    target, nontarget = np.array(target), np.array(nontarget)
    thresholds = np.sort(np.concatenate([target, nontarget]))
    frr = np.searchsorted(np.sort(target), thresholds, side="left") / target.size
    far = 1 - np.searchsorted(np.sort(nontarget), thresholds, side="left") / nontarget.size
    i = int(np.argmin(np.abs(frr - far)))
    return float((frr[i] + far[i]) / 2 * 100)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--fp32", type=Path, required=True)
    p.add_argument("--trials", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--calibration-items", type=int, default=128)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    from ai_edge_litert.interpreter import Interpreter
    from ai_edge_quantizer import quantizer, recipe_manager
    from ai_edge_quantizer.algorithm_manager import AlgorithmName
    from ai_edge_quantizer.qtyping import TFLOperationName

    calib = crops(args.trials, lambda r: r["split"] == "test-other", args.calibration_items - 16)
    calib += crops(args.trials, lambda r: r["kind"] == "nonspeech", 16)
    enroll = crops(args.trials, lambda r: r["split"] == "test-clean" and r["kind"] == "enroll")
    test = crops(args.trials, lambda r: r["split"] == "test-clean" and r["kind"] == "test" and r["seconds"] == 2.0)
    signature = Interpreter(model_path=str(args.fp32)).get_signature_list()
    sig_name = next(iter(signature))
    input_name = signature[sig_name]["inputs"][0]
    calibration_data = {sig_name: [{input_name: wav} for _, wav in calib]}

    ref_test = embed(args.fp32, test)
    report = {"fp32": {"eerPercent": eer(enroll, embed(args.fp32, enroll), test, ref_test), "bytes": args.fp32.stat().st_size},
              "calibration": {"items": len(calib), "splits": "test-other dev + MUSAN nonspeech"},
              "evaluation": {"enroll": len(enroll), "test": len(test), "split": "test-clean", "crop_s": SAMPLES / 16000}}
    variants = {
        "w8a16_all": (".*", 16), "w8a16_resnet": (".*ResNet_resnet.*", 16),
        "w8a8_all": (".*", 8), "w8a8_resnet": (".*ResNet_resnet.*", 8),
    }
    for name, (regex, act_bits) in variants.items():
        started = time.time()
        manager = recipe_manager.RecipeManager()
        manager.add_static_config(regex=regex, operation_name=TFLOperationName.ALL_SUPPORTED,
                                  activation_num_bits=act_bits, weight_num_bits=8,
                                  algorithm_key=AlgorithmName.MIN_MAX_UNIFORM_QUANT)
        q = quantizer.Quantizer(str(args.fp32))
        q.load_quantization_recipe(manager.get_quantization_recipe())
        calibration = q.calibrate(calibration_data)
        out = args.out / f"{args.fp32.stem}_{name}.tflite"
        q.quantize(calibration).export_model(str(out), overwrite=True)
        test_vecs = embed(out, test)
        report[name] = {"bytes": out.stat().st_size, "sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
                        "eerPercent": eer(enroll, embed(out, enroll), test, test_vecs),
                        "minCosVsFp32": float(np.min(np.sum(test_vecs * ref_test, 1))),
                        "meanCosVsFp32": float(np.mean(np.sum(test_vecs * ref_test, 1))),
                        "seconds": round(time.time() - started, 1)}
        print(name, json.dumps(report[name]), flush=True)
    (args.out / "npu_quantize_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k in ("fp32", "calibration", "evaluation")}))


if __name__ == "__main__":
    main()
