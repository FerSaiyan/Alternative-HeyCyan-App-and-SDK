#!/usr/bin/env python3
"""Embed the matched trial items (from audio_speaker_build_trials.py) with one model.

Models (``--model``):
  ecapa      SpeechBrain ECAPA-TDNN ``speechbrain/spkrec-ecapa-voxceleb`` (192-d).
  wespeaker  WeSpeaker ResNet34-LM ONNX ``Wespeaker/wespeaker-voxceleb-resnet34-LM`` (256-d).
  eg2        EmbeddingGemma 2 via sentence-transformers (audio+text towers, vision dropped).
             Saves the official pooled/normalised 768-d embedding plus diagnostic audio-tower
             representations captured with forward hooks.
  gemma4     Gemma 4 E2B audio encoder only (weights read from the full checkpoint), saving
             mean-pooled per-layer states, the encoder output and the projected soft tokens.

Representations are saved to ``<out>/<model>.npz`` as float32 (final) / float16 (diagnostic)
arrays of shape (n_items, dim), index-aligned with items.jsonl.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from collections import defaultdict

import numpy as np
import torch

SR = 16000


def load_items(trials_dir: str):
    items = [json.loads(line) for line in open(os.path.join(trials_dir, "items.jsonl"), encoding="utf-8")]
    pcm = np.load(os.path.join(trials_dir, "audio_pcm16.npy"), mmap_mode="r")
    offsets = np.load(os.path.join(trials_dir, "offsets.npy"))
    return items, pcm, offsets


def get_wav(pcm, offsets, i: int) -> np.ndarray:
    return np.asarray(pcm[offsets[i] : offsets[i + 1]], dtype=np.float32) / 32767.0


def length_batches(offsets: np.ndarray, batch_size: int):
    """Group item indices by exact sample length so no padding is ever needed."""
    groups: dict[int, list[int]] = defaultdict(list)
    for i in range(len(offsets) - 1):
        groups[int(offsets[i + 1] - offsets[i])].append(i)
    for n in sorted(groups):
        idx = groups[n]
        for s in range(0, len(idx), batch_size):
            yield idx[s : s + batch_size]


def masked_mean(h: torch.Tensor, mask: torch.Tensor | None) -> torch.Tensor:
    h = h.float()
    if mask is None:
        return h.mean(dim=1)
    m = mask.to(h.device).float().unsqueeze(-1)
    return (h * m).sum(dim=1) / m.sum(dim=1).clamp_min(1.0)


# --------------------------------------------------------------------------------------------
class EcapaEmbedder:
    name = "ecapa"

    def __init__(self, model_dir: str, device: str, work: str):
        from speechbrain.inference.speaker import EncoderClassifier

        self.model = EncoderClassifier.from_hparams(
            source=model_dir, savedir=os.path.join(work, "sb_ecapa_cache"), run_opts={"device": device},
            overrides={"pretrained_path": model_dir},
        )
        self.model.eval()
        self.device = device

    @torch.inference_mode()
    def __call__(self, wavs: list[np.ndarray]) -> dict[str, np.ndarray]:
        x = torch.from_numpy(np.stack(wavs)).to(self.device)
        e = self.model.encode_batch(x).squeeze(1)
        return {"ecapa_192": e.float().cpu().numpy()}


class WespeakerEmbedder:
    name = "wespeaker"

    def __init__(self, model_dir: str, device: str, work: str):
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.intra_op_num_threads = int(os.environ.get("ORT_THREADS", "8"))
        self.sess = ort.InferenceSession(
            os.path.join(model_dir, "voxceleb_resnet34_LM.onnx"), so, providers=["CPUExecutionProvider"]
        )
        self.inp = self.sess.get_inputs()[0].name

    def feats(self, wav: np.ndarray) -> np.ndarray:
        import torchaudio.compliance.kaldi as kaldi

        w = torch.from_numpy(wav).unsqueeze(0) * (1 << 15)
        f = kaldi.fbank(w, num_mel_bins=80, frame_length=25, frame_shift=10, dither=0.0,
                        sample_frequency=SR, window_type="hamming", use_energy=False)
        f = f - f.mean(dim=0, keepdim=True)
        return f.numpy().astype(np.float32)

    def __call__(self, wavs: list[np.ndarray]) -> dict[str, np.ndarray]:
        x = np.stack([self.feats(w) for w in wavs])
        out = self.sess.run(None, {self.inp: x})[0]
        return {"wespeaker_256": out.astype(np.float32)}


class _AudioTowerHooks:
    """Capture per-layer hidden states, tower output and projected soft tokens."""

    def __init__(self, tower: torch.nn.Module, embedder: torch.nn.Module | None):
        self.layers: list[torch.Tensor] = []
        self.tower_out: torch.Tensor | None = None
        self.mask: torch.Tensor | None = None
        self.proj: torch.Tensor | None = None
        self.subsample: torch.Tensor | None = None
        self.handles = []
        self.handles.append(tower.subsample_conv_projection.register_forward_hook(self._subsample_hook))
        for layer in tower.layers:
            self.handles.append(layer.register_forward_hook(self._layer_hook))
        self.handles.append(tower.register_forward_hook(self._tower_hook))
        if embedder is not None:
            self.handles.append(embedder.register_forward_hook(self._proj_hook))

    def reset(self):
        self.layers, self.tower_out, self.mask, self.proj, self.subsample = [], None, None, None, None

    def _subsample_hook(self, mod, inp, out):
        self.subsample = out[0] if isinstance(out, tuple) else out

    def _layer_hook(self, mod, inp, out):
        self.layers.append(out[0] if isinstance(out, tuple) else out)

    def _tower_hook(self, mod, inp, out):
        self.tower_out = out.last_hidden_state
        self.mask = out.attention_mask

    def _proj_hook(self, mod, inp, out):
        self.proj = out

    def pooled(self, prefix: str) -> dict[str, np.ndarray]:
        res = {}
        if self.subsample is not None:
            res[f"{prefix}_subsample"] = masked_mean(self.subsample, self.mask).cpu().numpy().astype(np.float16)
        for i, h in enumerate(self.layers):
            res[f"{prefix}_layer{i:02d}"] = masked_mean(h, self.mask).cpu().numpy().astype(np.float16)
        res[f"{prefix}_tower_out"] = masked_mean(self.tower_out, self.mask).cpu().numpy().astype(np.float32)
        if self.proj is not None:
            res[f"{prefix}_soft_tokens"] = masked_mean(self.proj, self.mask).cpu().numpy().astype(np.float32)
        return res


class EG2Embedder:
    name = "eg2"

    def __init__(self, model_dir: str, device: str, work: str, dtype: str = "bf16"):
        from sentence_transformers import SentenceTransformer

        td = torch.bfloat16 if dtype == "bf16" else torch.float32
        self.model = SentenceTransformer(
            model_dir, device=device, model_kwargs={"dtype": td}, config_kwargs={"vision_config": None}
        )
        self.model.eval()
        hf = self.model[0].model
        self.hooks = _AudioTowerHooks(hf.audio_tower, hf.embed_audio)

    @torch.inference_mode()
    def __call__(self, wavs: list[np.ndarray]) -> dict[str, np.ndarray]:
        self.hooks.reset()
        emb = self.model.encode(
            [{"array": w, "sampling_rate": SR} for w in wavs],
            batch_size=len(wavs), convert_to_numpy=True, normalize_embeddings=True, show_progress_bar=False,
        )
        out = {"eg2_final_768": emb.astype(np.float32)}
        out.update(self.hooks.pooled("eg2_audio"))
        return out


class Gemma4AudioEmbedder:
    name = "gemma4"

    def __init__(self, model_dir: str, device: str, work: str, dtype: str = "bf16"):
        from safetensors import safe_open
        from transformers import AutoConfig, AutoFeatureExtractor
        from transformers.models.gemma4.modeling_gemma4 import Gemma4AudioModel, Gemma4MultimodalEmbedder

        cfg = AutoConfig.from_pretrained(model_dir)
        td = torch.bfloat16 if dtype == "bf16" else torch.float32
        self.fe = AutoFeatureExtractor.from_pretrained(model_dir)
        tower = Gemma4AudioModel(cfg.audio_config)
        embedder = Gemma4MultimodalEmbedder(cfg.audio_config, cfg.text_config)
        tsd, esd = {}, {}
        with safe_open(os.path.join(model_dir, "model.safetensors"), framework="pt") as f:
            for k in f.keys():
                if k.startswith("model.audio_tower."):
                    tsd[k[len("model.audio_tower."):]] = f.get_tensor(k)
                elif k.startswith("model.embed_audio."):
                    esd[k[len("model.embed_audio."):]] = f.get_tensor(k)
        missing, unexpected = tower.load_state_dict(tsd, strict=False)
        missing = [m for m in missing if "rel_pos_enc" not in m and "inv_timescales" not in m]
        assert not missing and not unexpected, (missing[:5], unexpected[:5])
        m2, u2 = embedder.load_state_dict(esd, strict=False)
        assert not m2 and not u2, (m2, u2)
        self.tower = tower.to(device=device, dtype=td).eval()
        self.embedder = embedder.to(device=device, dtype=td).eval()
        self.device, self.dtype = device, td
        self.hooks = _AudioTowerHooks(self.tower, None)
        self.n_params = sum(p.numel() for p in tower.parameters()) + sum(p.numel() for p in embedder.parameters())

    @torch.inference_mode()
    def __call__(self, wavs: list[np.ndarray]) -> dict[str, np.ndarray]:
        self.hooks.reset()
        feats = self.fe(wavs, sampling_rate=SR, return_tensors="pt", padding=True)
        x = feats["input_features"].to(self.device, self.dtype)
        mask = feats.get("input_features_mask", feats.get("attention_mask")).to(self.device)
        out = self.tower(x, mask, return_dict=True)
        proj = self.embedder(out.last_hidden_state)
        res = self.hooks.pooled("g4_audio")
        res["g4_audio_soft_tokens"] = masked_mean(proj, out.attention_mask).cpu().numpy().astype(np.float32)
        # model-free reference: mean+std of the same 128-bin log-mel features the encoder sees
        fm = mask.float().unsqueeze(-1)
        xf = feats["input_features"].float().to(self.device)
        mu = (xf * fm).sum(1) / fm.sum(1).clamp_min(1.0)
        sd = (((xf - mu.unsqueeze(1)) ** 2 * fm).sum(1) / fm.sum(1).clamp_min(1.0)).sqrt()
        res["logmel_meanstd_256"] = torch.cat([mu, sd], dim=1).cpu().numpy().astype(np.float32)
        return res


def build(model: str, args):
    if model == "ecapa":
        return EcapaEmbedder(args.model_dir, args.device, args.work)
    if model == "wespeaker":
        return WespeakerEmbedder(args.model_dir, args.device, args.work)
    if model == "eg2":
        return EG2Embedder(args.model_dir, args.device, args.work, args.dtype)
    if model == "gemma4":
        return Gemma4AudioEmbedder(args.model_dir, args.device, args.work, args.dtype)
    raise ValueError(model)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, choices=["ecapa", "wespeaker", "eg2", "gemma4"])
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--trials", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--dtype", default="bf16", choices=["bf16", "fp32"], help="never fp16 for Gemma models")
    ap.add_argument("--batch-size", type=int, default=32)
    ap.add_argument("--limit", type=int, default=0, help="debug: only first N items")
    args = ap.parse_args()

    items, pcm, offsets = load_items(args.trials)
    if args.limit:
        offsets = offsets[: args.limit + 1]
    n = len(offsets) - 1
    emb = build(args.model, args)
    store: dict[str, np.ndarray] = {}
    t0 = time.time()
    done = 0
    for batch in length_batches(offsets, args.batch_size):
        wavs = [get_wav(pcm, offsets, i) for i in batch]
        out = emb(wavs)
        for k, v in out.items():
            if k not in store:
                store[k] = np.zeros((n, v.shape[1]), dtype=v.dtype)
            store[k][batch] = v
        done += len(batch)
        if done % 1000 < len(batch):
            print(f"{done}/{n} items {time.time() - t0:.1f}s", flush=True)
    for k, v in store.items():
        if not np.all(np.isfinite(v.astype(np.float32))):
            raise SystemExit(f"non-finite values in {k}")
    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, f"{args.model}.npz")
    np.savez(path, **store)
    meta = {"model": args.model, "model_dir": args.model_dir, "dtype": args.dtype, "device": args.device,
            "n_items": n, "seconds": time.time() - t0, "reps": {k: list(v.shape) for k, v in store.items()},
            "torch": torch.__version__}
    if hasattr(emb, "n_params"):
        meta["n_params"] = emb.n_params
    with open(os.path.join(args.out, f"{args.model}.meta.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
