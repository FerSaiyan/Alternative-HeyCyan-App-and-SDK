#!/usr/bin/env python3
"""Export SpeechBrain ECAPA-TDNN (speechbrain/spkrec-ecapa-voxceleb) to a LiteRT .tflite.

The exported graph takes a fixed-length mono 16 kHz float32 waveform ``(1, N)`` in [-1, 1]
and returns the raw (un-normalised) 192-d speaker embedding ``(1, 192)``, i.e. exactly what
``EncoderClassifier.encode_batch`` returns. Feature extraction is inside the graph:
the STFT is a fixed conv1d DFT basis (Hamming 400 / hop 160 / n_fft 400, centre-padded with
zeros like torch.stft(center=True, pad_mode="constant")), followed by SpeechBrain's own
Filterbank module (80 mel, 10*log10, top_db 80) and sentence mean normalisation.

Parity is checked against SpeechBrain on real trial items (cosine between embeddings).
Requires litert-torch (CPU venv); no TensorFlow.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import time

import numpy as np
import torch


class EcapaWaveformModel(torch.nn.Module):
    def __init__(self, classifier):
        super().__init__()
        fb = classifier.mods.compute_features if hasattr(classifier.mods, "compute_features") else classifier.hparams.compute_features
        stft = fb.compute_STFT
        self.n_fft, self.hop, self.win = stft.n_fft, stft.hop_length, stft.win_length
        assert stft.center and stft.pad_mode == "constant", (stft.center, stft.pad_mode)
        window = stft.window.detach().float()
        assert window.numel() == self.win == self.n_fft, (window.numel(), self.win, self.n_fft)
        n = torch.arange(self.n_fft, dtype=torch.float64)
        k = torch.arange(self.n_fft // 2 + 1, dtype=torch.float64)
        ang = 2 * torch.pi * k[:, None] * n[None, :] / self.n_fft
        w = window.double()[None, :]
        basis = torch.cat([torch.cos(ang) * w, -torch.sin(ang) * w], dim=0).float().unsqueeze(1)
        self.register_buffer("basis", basis)  # (2F, 1, n_fft)
        self.n_bins = self.n_fft // 2 + 1
        self.fbanks = fb.compute_fbanks
        self.embedding_model = classifier.mods.embedding_model

    def features(self, wav: torch.Tensor) -> torch.Tensor:
        x = torch.nn.functional.pad(wav.unsqueeze(1), (self.n_fft // 2, self.n_fft // 2))
        spec = torch.nn.functional.conv1d(x, self.basis, stride=self.hop)  # (1, 2F, T)
        re, im = spec[:, : self.n_bins], spec[:, self.n_bins :]
        power = (re * re + im * im).transpose(1, 2)  # (1, T, F)
        f = self.fbanks(power)
        return f - f.mean(dim=1, keepdim=True)

    def forward(self, wav: torch.Tensor) -> torch.Tensor:
        return self.embedding_model(self.features(wav)).squeeze(1)


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--trials", required=True, help="trial dir for parity items")
    ap.add_argument("--work", required=True)
    ap.add_argument("--out", required=True, help="output .tflite path")
    ap.add_argument("--seconds", type=float, default=2.0)
    ap.add_argument("--n-parity", type=int, default=200)
    args = ap.parse_args()

    import litert_torch
    from speechbrain.inference.speaker import EncoderClassifier

    torch.set_num_threads(4)
    clf = EncoderClassifier.from_hparams(source=args.model_dir, savedir=os.path.join(args.work, "sb_ecapa_cache_cpu"),
                                         run_opts={"device": "cpu"}, overrides={"pretrained_path": args.model_dir})
    clf.eval()
    model = EcapaWaveformModel(clf).eval()
    n = int(round(args.seconds * 16000))

    items = [json.loads(line) for line in open(os.path.join(args.trials, "items.jsonl"), encoding="utf-8")]
    pcm = np.load(os.path.join(args.trials, "audio_pcm16.npy"), mmap_mode="r")
    offsets = np.load(os.path.join(args.trials, "offsets.npy"))
    picks = [it["idx"] for it in items if it["kind"] in ("test", "noisy") and abs(it["extra"].get("len", 0) - args.seconds) < 1e-6]
    rng = np.random.default_rng(0)
    picks = sorted(rng.choice(picks, size=min(args.n_parity, len(picks)), replace=False).tolist())
    wavs = [np.asarray(pcm[offsets[i]: offsets[i + 1]], dtype=np.float32) / 32767.0 for i in picks]

    # 1) torch re-implementation vs SpeechBrain reference
    with torch.inference_mode():
        ref = np.concatenate([clf.encode_batch(torch.from_numpy(w)[None]).squeeze(1).numpy() for w in wavs])
        mine = np.concatenate([model(torch.from_numpy(w)[None]).numpy() for w in wavs])

    def cos(a, b):
        return np.sum(a * b, 1) / (np.linalg.norm(a, axis=1) * np.linalg.norm(b, axis=1))

    c_torch = cos(ref, mine)
    print(f"torch-frontend vs speechbrain: min cos {c_torch.min():.6f} mean {c_torch.mean():.6f}", flush=True)

    # 2) convert
    sample = (torch.from_numpy(wavs[0])[None],)
    t0 = time.time()
    edge = litert_torch.convert(model, sample)
    edge.export(args.out)
    conv_s = time.time() - t0
    lite = np.concatenate([np.asarray(edge(torch.from_numpy(w)[None])) for w in wavs])
    c_lite = cos(ref, lite)
    lat = []
    for w in wavs[:30]:
        s = time.perf_counter()
        edge(torch.from_numpy(w)[None])
        lat.append((time.perf_counter() - s) * 1000)
    info = {
        "out": args.out, "bytes": os.path.getsize(args.out), "sha256": sha256(args.out),
        "input": [1, n], "output": [1, 192], "seconds": args.seconds, "convert_s": conv_s,
        "parity_items": len(wavs),
        "cos_torchfrontend_vs_speechbrain": {"min": float(c_torch.min()), "mean": float(c_torch.mean())},
        "cos_tflite_vs_speechbrain": {"min": float(c_lite.min()), "mean": float(c_lite.mean())},
        "host_tflite_cpu_ms_median": float(np.median(lat)),
        "torch": torch.__version__, "litert_torch": getattr(litert_torch, "__version__", "unknown"),
        "source_model": "speechbrain/spkrec-ecapa-voxceleb@0f99f2d0ebe89ac095bcc5903c4dd8f72b367286 (Apache-2.0)",
    }
    with open(args.out + ".json", "w") as f:
        json.dump(info, f, indent=2)
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()
