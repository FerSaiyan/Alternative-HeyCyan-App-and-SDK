#!/usr/bin/env python3
"""Export WeSpeaker ResNet34-LM (Wespeaker/wespeaker-voxceleb-resnet34-LM) to LiteRT.

Same contract as the ECAPA export (audio_speaker_export_litert.py): input
float32[1, N] mono 16 kHz PCM in [-1, 1]; output float32[1, 256] raw embedding.
The WeSpeaker inference front end runs inside the graph: int16 scaling, then
torchaudio-compatible Kaldi fbank (80 mel, 25/10 ms, Hamming, pre-emphasis 0.97,
DC removal, 512-point FFT, snip_edges, log floor at float eps), then
per-utterance mean normalisation. The per-frame linear chain (DC removal,
pre-emphasis, window, zero padding, real DFT) is folded into one fixed conv1d.

Checks: fbank vs torchaudio.compliance.kaldi.fbank; embeddings vs the official
ONNX export; LiteRT fp32 and int8 vs PyTorch. The int8 variant quantizes only
ResNet weights (weight-only, float compute): quantizing the front end's DFT/mel
constants or its int16-scale activations (dynamic int8) destroys the features. Uses LiteRT Torch
(`litert_torch.convert`); no TensorFlow. Run in an isolated venv.
"""
import argparse
import hashlib
import importlib.util
import json
import sys
import time
import types
from pathlib import Path

import numpy as np
import torch

SAMPLE_RATE = 16000
FRAME = 400
HOP = 160
NFFT = 512
PREEMPH = 0.97


def load_resnet34(src: Path, checkpoint: Path):
    """Import wespeaker.models.resnet without running the package __init__ (CLI/VAD deps)."""
    for name, path in (("wespeaker", src / "wespeaker"), ("wespeaker.models", src / "wespeaker" / "models")):
        module = types.ModuleType(name)
        module.__path__ = [str(path)]
        sys.modules[name] = module
    for name in ("pooling_layers", "resnet"):
        spec = importlib.util.spec_from_file_location(f"wespeaker.models.{name}", src / "wespeaker" / "models" / f"{name}.py")
        module = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
    model = sys.modules["wespeaker.models.resnet"].ResNet34(feat_dim=80, embed_dim=256, pooling_func="TSTP", two_emb_layer=False)
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    missing, unexpected = model.load_state_dict(state, strict=False)
    assert not missing, missing
    # Only the training-time margin projection head may be unused.
    assert all(k.startswith("projection.") for k in unexpected), unexpected
    return model.eval()


class KaldiFbank(torch.nn.Module):
    """torchaudio.compliance.kaldi.fbank defaults used by WeSpeaker, as fixed ops."""

    def __init__(self):
        super().__init__()
        import torchaudio.compliance.kaldi as kaldi
        n = torch.arange(FRAME, dtype=torch.float64)
        window = 0.54 - 0.46 * torch.cos(2 * torch.pi * n / (FRAME - 1))  # Kaldi symmetric Hamming
        dc = torch.eye(FRAME, dtype=torch.float64) - 1.0 / FRAME
        pre = torch.eye(FRAME, dtype=torch.float64)
        pre[torch.arange(1, FRAME), torch.arange(FRAME - 1)] = -PREEMPH
        pre[0, 0] = 1 - PREEMPH  # Kaldi replicates the first sample
        k = torch.arange(NFFT // 2 + 1, dtype=torch.float64)
        ang = 2 * torch.pi * k[:, None] * n[None, :] / NFFT  # zero padding to 512 adds nothing
        frame_op = torch.diag(window) @ pre @ dc
        basis = torch.cat([torch.cos(ang) @ frame_op, -torch.sin(ang) @ frame_op], 0)
        self.register_buffer("basis", basis.float().unsqueeze(1))  # (514, 1, 400)
        mel, _ = kaldi.get_mel_banks(80, NFFT, float(SAMPLE_RATE), 20.0, 0.0, 100.0, -500.0, 1.0)
        mel = torch.nn.functional.pad(mel, (0, 1))  # Nyquist bin, as torchaudio does
        self.register_buffer("mel", mel.float().T.contiguous())  # (257, 80)
        self.eps = float(torch.finfo(torch.float32).eps)

    def forward(self, wav_int16_scale):  # (1, N) -> (1, T, 80)
        spec = torch.nn.functional.conv1d(wav_int16_scale.unsqueeze(1), self.basis, stride=HOP)
        half = spec.shape[1] // 2
        power = spec[:, :half] ** 2 + spec[:, half:] ** 2  # (1, 257, T)
        fbank = torch.log(torch.clamp(power.transpose(1, 2) @ self.mel, min=self.eps))
        return fbank


class KaldiFbankFp16Safe(KaldiFbank):
    """Same features, but every intermediate fits fp16 (Qualcomm HTP runs float graphs in fp16).

    Takes [-1, 1] PCM. The DFT basis carries gain FP16_GAIN instead of 32768, so a
    full-scale tone peaks near 1e4 in power/mel. Mel energies are floored at the
    smallest normal fp16 value (no reliance on subnormals; affects only bands below
    roughly -70 dBFS) and saturated below fp16 max. The log offset restores the
    int16-scale values; per-utterance mean normalisation cancels it anyway.
    """

    FP16_GAIN = 0.5
    FP16_MIN_NORMAL = 6.104e-05
    FP16_SAFE_MAX = 65000.0

    def __init__(self):
        super().__init__()
        self.basis.mul_(self.FP16_GAIN)
        self.offset = float(torch.log(torch.tensor((32768.0 / self.FP16_GAIN) ** 2, dtype=torch.float64)))

    def spectrum(self, wav_unit):  # (1, N) -> (1, 514, T)
        return torch.nn.functional.conv1d(wav_unit.unsqueeze(1), self.basis, stride=HOP)

    def forward(self, wav_unit):  # (1, N) in [-1, 1] -> (1, T, 80)
        spec = torch.clamp(self.spectrum(wav_unit), -250.0, 250.0)  # squares stay below fp16 max
        half = spec.shape[1] // 2
        power = spec[:, :half] ** 2 + spec[:, half:] ** 2
        mel = torch.clamp(power.transpose(1, 2) @ self.mel, self.FP16_MIN_NORMAL, self.FP16_SAFE_MAX)
        return torch.log(mel) + self.offset


class KaldiFbankNpu(KaldiFbankFp16Safe):
    """KaldiFbankFp16Safe with framing that Qualcomm HTP computes correctly.

    In QNN HTP emulation (android/CyanBridge/tools/litert-npu/htp_emulator_parity.py) the
    400-tap, stride-160 DFT conv1d produces wrong values. Here the waveform is split into
    160-sample blocks; frame t is blocks t..t+2 (480 samples, the last 80 weights zero),
    i.e. a stride-1, kernel-3 conv over 160 channels. Same features as the parent.
    """

    def __init__(self):
        super().__init__()
        weights = torch.zeros(self.basis.shape[0], 3 * HOP)
        weights[:, :FRAME] = self.basis[:, 0, :]
        # [out, block offset k, sample c] -> conv1d layout [out, c, k].
        self.register_buffer("block_basis", weights.view(-1, 3, HOP).transpose(1, 2).contiguous())

    def spectrum(self, wav_unit):
        blocks = wav_unit.view(1, -1, HOP).transpose(1, 2)  # (1, 160, N / 160)
        return torch.nn.functional.conv1d(blocks, self.block_basis)


class WespeakerWaveformModel(torch.nn.Module):
    def __init__(self, resnet, fp16_safe=False, npu=False):
        super().__init__()
        self.fp16_safe = fp16_safe or npu
        self.fbank = KaldiFbankNpu() if npu else KaldiFbankFp16Safe() if fp16_safe else KaldiFbank()
        self.resnet = resnet

    def features(self, wav):
        feats = self.fbank(wav) if self.fp16_safe else self.fbank(wav * 32768.0)
        return feats - feats.mean(dim=1, keepdim=True)

    def forward(self, wav):
        return self.resnet(self.features(wav))[-1]


def reference_features(wav: torch.Tensor) -> torch.Tensor:
    import torchaudio.compliance.kaldi as kaldi
    feat = kaldi.fbank(wav * 32768.0, num_mel_bins=80, frame_length=25, frame_shift=10,
                       sample_frequency=SAMPLE_RATE, window_type="hamming", dither=0.0)
    return (feat - feat.mean(dim=0)).unsqueeze(0)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cos(a, b):
    a, b = np.asarray(a, np.float64).ravel(), np.asarray(b, np.float64).ravel()
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


def run_tflite(path: Path, wavs):
    from ai_edge_litert.interpreter import Interpreter
    interp = Interpreter(model_path=str(path), num_threads=4)
    interp.allocate_tensors()
    inp, out = interp.get_input_details()[0], interp.get_output_details()[0]
    result, times = [], []
    for w in wavs:
        interp.set_tensor(inp["index"], w.numpy().astype(np.float32))
        t0 = time.perf_counter()
        interp.invoke()
        times.append((time.perf_counter() - t0) * 1000)
        result.append(interp.get_tensor(out["index"])[0].copy())
    return result, sorted(times)[len(times) // 2]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--src", type=Path, required=True, help="wenet-e2e/wespeaker checkout")
    p.add_argument("--model-dir", type=Path, required=True, help="HF snapshot with avg_model and the ONNX file")
    p.add_argument("--fixture", type=Path, required=True, help="ECAPA fixture dir (manifest.json + *.pcm16)")
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(8)
    manifest = json.loads((args.fixture / "manifest.json").read_text())
    samples = manifest["samples"]
    clips = manifest["clips"]
    wavs = [torch.from_numpy(np.frombuffer((args.fixture / c["file"]).read_bytes(), "<i2").astype(np.float32) / 32767.0)[None]
            for c in clips]
    model = WespeakerWaveformModel(load_resnet34(args.src, args.model_dir / "avg_model"))
    report = {"source": "Wespeaker/wespeaker-voxceleb-resnet34-LM", "samples": samples}

    with torch.no_grad():
        ref_feats = [reference_features(w[0][None]) for w in wavs]
        our_feats = [model.features(w) for w in wavs]
        report["fbankMaxAbsDiff"] = max(float((a - b).abs().max()) for a, b in zip(ref_feats, our_feats))
        torch_emb = [model(w)[0].numpy() for w in wavs]
    import onnxruntime as ort
    onnx = ort.InferenceSession(str(args.model_dir / "voxceleb_resnet34_LM.onnx"), providers=["CPUExecutionProvider"])
    onnx_in = onnx.get_inputs()[0].name
    onnx_emb = [onnx.run(None, {onnx_in: f.numpy()})[0][0] for f in ref_feats]
    report["torchVsOnnxMinCos"] = min(cos(a, b) for a, b in zip(torch_emb, onnx_emb))

    import litert_torch
    started = time.time()
    edge = litert_torch.convert(model, (wavs[0],))
    fp32 = args.out / "wespeaker_resnet34_lm_2s.tflite"
    edge.export(str(fp32))
    report["convertSeconds"] = round(time.time() - started, 1)
    fp32_emb, fp32_ms = run_tflite(fp32, wavs)
    report["fp32"] = {"bytes": fp32.stat().st_size, "sha256": sha256(fp32), "minCosVsTorch": min(cos(a, b) for a, b in zip(fp32_emb, torch_emb)),
                      "hostMedianMs": round(fp32_ms, 2)}

    from ai_edge_quantizer import quantizer, recipe_manager
    from ai_edge_quantizer.algorithm_manager import AlgorithmName
    from ai_edge_quantizer.qtyping import TFLOperationName
    manager = recipe_manager.RecipeManager()
    manager.add_weight_only_config(regex=".*ResNet_resnet.*", operation_name=TFLOperationName.ALL_SUPPORTED,
                                   num_bits=8, algorithm_key=AlgorithmName.MIN_MAX_UNIFORM_QUANT)
    q = quantizer.Quantizer(str(fp32))
    q.load_quantization_recipe(manager.get_quantization_recipe())
    int8 = args.out / "wespeaker_resnet34_lm_2s_int8w.tflite"
    q.quantize().export_model(str(int8), overwrite=True)
    int8_emb, int8_ms = run_tflite(int8, wavs)
    report["int8"] = {"bytes": int8.stat().st_size, "sha256": sha256(int8), "minCosVsTorch": min(cos(a, b) for a, b in zip(int8_emb, torch_emb)),
                      "hostMedianMs": round(int8_ms, 2)}

    # Device fixtures: same clips, host LiteRT embeddings of each variant.
    for variant, path, embeddings in (("fp32", fp32, fp32_emb), ("int8", int8, int8_emb)):
        fixture = args.out / f"fixture-{variant}"
        fixture.mkdir(exist_ok=True)
        rows = []
        for clip, emb in zip(clips, embeddings):
            (fixture / clip["file"]).write_bytes((args.fixture / clip["file"]).read_bytes())
            rows.append({k: clip[k] for k in ("name", "file", "role", "speaker")} | {"host_embedding": [round(float(x), 6) for x in emb]})
        (fixture / "manifest.json").write_text(json.dumps({"sample_rate": SAMPLE_RATE, "samples": samples, "format": "pcm_s16le_mono",
                                                           "tflite_sha256": sha256(path), "speakers": manifest["speakers"], "clips": rows}))

    # Same scoring as the device probe: mean enrollment template per speaker.
    def scores(embeddings):
        named = {c["name"]: (c, e / np.linalg.norm(e)) for c, e in zip(clips, embeddings)}
        min_target, max_other = 1.0, -1.0
        for spk in manifest["speakers"]:
            template = sum(e for c, e in named.values() if c["role"] == "enroll" and c["speaker"] == spk)
            template /= np.linalg.norm(template)
            for c, e in named.values():
                if c["role"] == "enroll":
                    continue
                s = float(e @ template)
                if c["speaker"] == spk and c["role"] == "target":
                    min_target = min(min_target, s)
                if c["speaker"] != spk:
                    max_other = max(max_other, s)
        return {"minTargetCos": round(min_target, 4), "maxOtherCos": round(max_other, 4)}
    report["fp32"]["fixtureScores"] = scores(fp32_emb)
    report["int8"]["fixtureScores"] = scores(int8_emb)
    report["versions"] = {"torch": torch.__version__, "litert_torch": getattr(litert_torch, "__version__", "?"), "onnxruntime": ort.__version__}
    (args.out / "export_report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
