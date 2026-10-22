#!/usr/bin/env python3
"""Train a lightweight speaker head on the FROZEN Gemma 4 E2B / EmbeddingGemma 2 audio encoder.

The audio tower is byte-identical in both models, so one head serves both. The tower's weights
are never modified: features are taken from a frozen bf16 copy under ``torch.no_grad`` (head
variants) or, for ``--lora-layers K``, from a *separate branch* that re-runs a frozen copy of
encoder layers 0..K-1 with low-rank adapters (the shared tower's own outputs are unchanged).

Head = learned softmax layer weights over the selected representations (each normalised with a
non-affine LayerNorm) -> optional frame network -> attentive statistics pooling -> linear
192-d embedding. Trained with AAM-softmax on LibriSpeech train-clean-100/360 speakers
(``audio_speaker_head_prepare_train.py``), which are disjoint from the benchmark's test-clean
targets and test-other dev speakers. Checkpoint selection uses the dev split of the benchmark
trials only. Augmentation uses babble from other training speakers and synthetic coloured
noise; MUSAN is never used in training because it is a test negative class.

Representations (``--reps``): ``sub`` (subsampling conv output), ``0``..``11`` (encoder layer
outputs), ``out`` (encoder output after output_proj, 1536-d; what the LiteRT-LM
``tf_lite_audio_encoder_hw`` section returns), ``mel`` (the 128-bin log-mel input, a
no-Gemma control; frame rate 10 ms).

Outputs in ``--out``: ``head.pt`` (best-dev state dict + config), ``train_log.jsonl``,
``<name>.npz`` (embeddings for every trial item, key ``head_<name>``, plus an int8-weight
variant ``head_<name>_w8``) and ``<name>.meta.json``.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_speaker_metrics as M  # noqa: E402

SR = 16000


# ----------------------------------------------------------------------------------------------
# Front end: exact torch port of Gemma4AudioFeatureExtractor (no dither/preemphasis/per-bin norm)
class GemmaMel(nn.Module):
    def __init__(self, fe):
        super().__init__()
        assert fe.preemphasis == 0.0 and fe.dither == 0.0 and fe.per_bin_mean is None and fe.per_bin_stddev is None
        self.frame_length, self.hop, self.n_fft = fe.frame_length, fe.hop_length, fe.fft_length
        self.register_buffer("window", torch.from_numpy(np.asarray(fe.window, dtype=np.float32)))
        self.register_buffer("mel", torch.from_numpy(np.asarray(fe.mel_filters, dtype=np.float32)))
        self.mel_floor = float(fe.mel_floor)

    def forward(self, wav: torch.Tensor):
        """wav (B, N) float32, all valid. Returns log-mel (B, T, 128) and frame mask (B, T)."""
        b, n = wav.shape
        padded = int(math.ceil(n / 128) * 128)  # pad_to_multiple_of=128 as in the HF extractor
        amask = torch.zeros(b, padded, dtype=torch.bool, device=wav.device)
        amask[:, :n] = True
        wav = F.pad(wav, (0, padded - n))
        pad_left = self.frame_length // 2
        wav = F.pad(wav, (pad_left, 0))
        amask = F.pad(amask, (pad_left, 0), value=False)
        size = self.frame_length + 1
        frames = wav.unfold(-1, size, self.hop)[..., :-1] * self.window
        spec = torch.fft.rfft(frames.double(), n=self.n_fft, dim=-1).abs()
        logmel = torch.log(spec @ self.mel.double() + self.mel_floor).float()
        t = logmel.shape[1]
        ends = torch.arange(t, device=wav.device) * self.hop + size - 1
        fmask = amask[:, ends]
        return logmel * fmask[..., None], fmask


def load_tower(model_dir: str, device: str):
    from safetensors import safe_open
    from transformers import AutoConfig, AutoFeatureExtractor
    from transformers.models.gemma4.modeling_gemma4 import Gemma4AudioModel

    cfg = AutoConfig.from_pretrained(model_dir)
    fe = AutoFeatureExtractor.from_pretrained(model_dir)
    tower = Gemma4AudioModel(cfg.audio_config)
    sd = {}
    with safe_open(os.path.join(model_dir, "model.safetensors"), framework="pt") as f:
        for k in f.keys():
            if k.startswith("model.audio_tower."):
                sd[k[len("model.audio_tower."):]] = f.get_tensor(k)
    missing, unexpected = tower.load_state_dict(sd, strict=False)
    missing = [m for m in missing if "rel_pos_enc" not in m and "inv_timescales" not in m]
    assert not missing and not unexpected, (missing[:5], unexpected[:5])
    tower = tower.to(device=device, dtype=torch.bfloat16).eval()
    for p in tower.parameters():
        p.requires_grad_(False)
    return tower, GemmaMel(fe).to(device), fe


def encoder_states(tower, feats, fmask, upto: int, want_out: bool, branch=None):
    """Run the frozen tower up to layer ``upto`` (exclusive). Returns dict rep->(B,T,D) and mask.

    ``branch`` (optional ModuleList) replaces layers 0..len(branch)-1 with adapted copies; the
    shared tower itself is never modified.
    """
    from transformers.models.gemma4.modeling_gemma4 import create_bidirectional_mask, sliding_window_mask_function

    cfg = tower.config
    h, m = tower.subsample_conv_projection(feats.to(torch.bfloat16), fmask)
    pos = tower.rel_pos_enc(h)
    am = create_bidirectional_mask(
        config=cfg, inputs_embeds=h, attention_mask=m,
        and_mask_function=sliding_window_mask_function((cfg.attention_context_left - 1, cfg.attention_context_right)),
    )
    if am is not None:
        am = tower._convert_4d_mask_to_blocked_5d(am)
    out = {"sub": h}
    for i in range(upto):
        layer = branch[i] if (branch is not None and i < len(branch)) else tower.layers[i]
        h = layer(h, attention_mask=am, position_embeddings=pos)
        out[str(i)] = h
    if want_out:
        out["out"] = tower.output_proj(h)
    return out, m


# ----------------------------------------------------------------------------------------------
class LoRALinear(nn.Module):
    def __init__(self, base: nn.Linear, r: int, alpha: float):
        super().__init__()
        self.base = base
        self.a = nn.Parameter(torch.randn(r, base.in_features) / math.sqrt(base.in_features))
        self.b = nn.Parameter(torch.zeros(base.out_features, r))
        self.scale = alpha / r

    def forward(self, x):
        return self.base(x) + (x.float() @ self.a.t() @ self.b.t() * self.scale).to(x.dtype)


def make_branch(tower, k: int, r: int, alpha: float) -> nn.ModuleList:
    branch = nn.ModuleList([copy.deepcopy(tower.layers[i]) for i in range(k)])
    for p in branch.parameters():
        p.requires_grad_(False)
    for layer in branch:
        for name, mod in list(layer.named_modules()):
            for cname, child in list(mod.named_children()):
                if isinstance(child, nn.Linear):
                    setattr(mod, cname, LoRALinear(child, r, alpha))
    return branch


class ASP(nn.Module):
    """Attentive statistics pooling with global context (ECAPA style), masked."""

    def __init__(self, c: int, att: int = 128):
        super().__init__()
        self.att = nn.Sequential(nn.Conv1d(3 * c, att, 1), nn.Tanh(), nn.Conv1d(att, c, 1))

    def forward(self, x, mask):  # x (B, C, T), mask (B, T) bool
        mf = mask[:, None, :].float()
        n = mf.sum(-1, keepdim=True).clamp_min(1.0)
        mu = (x * mf).sum(-1, keepdim=True) / n
        sd = (((x - mu) ** 2 * mf).sum(-1, keepdim=True) / n).clamp_min(1e-6).sqrt()
        g = torch.cat([x, mu.expand_as(x), sd.expand_as(x)], 1)
        w = self.att(g).masked_fill(~mask[:, None, :], -1e4).softmax(-1)
        mu = (x * w).sum(-1)
        sd = ((x**2 * w).sum(-1) - mu**2).clamp_min(1e-6).sqrt()
        return torch.cat([mu, sd], 1)


class SpeakerHead(nn.Module):
    def __init__(self, n_reps: int, dim: int, kind: str = "asp", channels: int = 512, emb: int = 192):
        super().__init__()
        self.layer_logits = nn.Parameter(torch.zeros(n_reps))
        self.kind = kind
        if kind == "asp":
            self.frame = nn.Identity()
            c = dim
        elif kind == "tdnn":
            self.frame = nn.Sequential(
                nn.Conv1d(dim, channels, 5, padding=2), nn.ReLU(), nn.BatchNorm1d(channels),
                nn.Conv1d(channels, channels, 3, padding=2, dilation=2), nn.ReLU(), nn.BatchNorm1d(channels),
                nn.Conv1d(channels, channels, 3, padding=3, dilation=3), nn.ReLU(), nn.BatchNorm1d(channels),
            )
            c = channels
        else:
            raise ValueError(kind)
        self.pool = ASP(c)
        self.bn = nn.BatchNorm1d(2 * c)
        self.fc = nn.Linear(2 * c, emb)
        self.bn_out = nn.BatchNorm1d(emb)

    def forward(self, reps: list[torch.Tensor], mask: torch.Tensor):
        w = self.layer_logits.softmax(0)
        x = sum(wi * F.layer_norm(r.float(), r.shape[-1:]) for wi, r in zip(w, reps))  # (B, T, D)
        x = x * mask[..., None]
        x = self.frame(x.transpose(1, 2))
        return self.bn_out(self.fc(self.bn(self.pool(x, mask))))


class AAMSoftmax(nn.Module):
    def __init__(self, emb: int, n_cls: int, s: float = 30.0):
        super().__init__()
        self.w = nn.Parameter(torch.randn(n_cls, emb) * 0.01)
        self.s = s

    def forward(self, x, y, m: float):
        cos = F.linear(F.normalize(x), F.normalize(self.w)).clamp(-1 + 1e-7, 1 - 1e-7)
        target = torch.cos(torch.acos(cos.gather(1, y[:, None])) + m)
        logits = cos.scatter(1, y[:, None], target) * self.s
        return F.cross_entropy(logits, y), (cos.argmax(1) == y).float().mean()


# ----------------------------------------------------------------------------------------------
class Model(nn.Module):
    """Frozen tower (not a submodule: never saved, never trained) + optional branch + head."""

    def __init__(self, tower, mel, reps: list[str], head_kind: str, lora_layers: int, lora_r: int):
        super().__init__()
        self.tower_ref = [tower]
        self.mel = mel
        self.reps = reps
        self.upto = max([int(r) + 1 for r in reps if r.isdigit()] + [12 if "out" in reps else 0])
        self.need_tower = any(r != "mel" for r in reps)
        dims = {"mel": 128, "out": tower.config.output_proj_dims}
        dim = {dims.get(r, tower.config.hidden_size) for r in reps}
        assert len(dim) == 1, "all selected reps must share a width"
        self.branch = make_branch(tower, lora_layers, lora_r, 2.0 * lora_r) if lora_layers else None
        self.head = SpeakerHead(len(reps), dim.pop(), head_kind)

    def forward(self, wav):
        feats, fmask = self.mel(wav)
        if not self.need_tower:
            return self.head([feats], fmask)
        tower = self.tower_ref[0]
        if self.branch is None:
            with torch.no_grad():
                st, m = encoder_states(tower, feats, fmask, self.upto, "out" in self.reps)
        else:
            with torch.autocast("cuda", dtype=torch.bfloat16):
                st, m = encoder_states(tower, feats, fmask, self.upto, "out" in self.reps, self.branch)
        return self.head([st[r] for r in self.reps], m)

    def trainable_state(self):
        sd = {f"head.{k}": v for k, v in self.head.state_dict().items()}
        if self.branch is not None:
            sd.update({f"branch.{k}": v for k, v in self.branch.state_dict().items() if k.endswith((".a", ".b"))})
        return sd


def n_params(mod: nn.Module | None, lora_only: bool = False) -> int:
    if mod is None:
        return 0
    return sum(p.numel() for n, p in mod.named_parameters() if (not lora_only) or n.endswith((".a", ".b")))


# ----------------------------------------------------------------------------------------------
def load_trials(trials: str):
    items = [json.loads(line) for line in open(os.path.join(trials, "items.jsonl"), encoding="utf-8")]
    pcm = np.load(os.path.join(trials, "audio_pcm16.npy"), mmap_mode="r")
    offsets = np.load(os.path.join(trials, "offsets.npy"))
    return items, pcm, offsets


@torch.no_grad()
def embed_items(model: Model, pcm, offsets, idx: list[int], device: str, bs: int = 64) -> np.ndarray:
    model.eval()
    groups: dict[int, list[int]] = {}
    for i in idx:
        groups.setdefault(int(offsets[i + 1] - offsets[i]), []).append(i)
    out = {}
    for n, g in sorted(groups.items()):
        for s in range(0, len(g), bs):
            b = g[s : s + bs]
            wav = torch.from_numpy(np.stack([np.asarray(pcm[offsets[i] : offsets[i + 1]], dtype=np.float32) / 32767.0 for i in b])).to(device)
            e = model(wav).float().cpu().numpy()
            for i, v in zip(b, e):
                out[i] = v
    return np.stack([out[i] for i in idx])


def dev_eer(model, items, pcm, offsets, device) -> float:
    enroll, test = {}, {}
    for it in items:
        if it["kind"] == "dev_enroll":
            enroll.setdefault(it["speaker"], []).append(it["idx"])
        elif it["kind"] == "dev_test":
            test.setdefault(it["speaker"], []).append(it["idx"])
    spks = sorted(enroll)
    allidx = sorted(i for s in spks for i in enroll[s] + test[s])
    E = embed_items(model, pcm, offsets, allidx, device)
    pos = {i: k for k, i in enumerate(allidx)}
    tmpl = np.stack([M.enrollment_template(E[[pos[i] for i in enroll[s]]]) for s in spks])
    T = M.l2_normalize(np.stack([E[pos[i]] for s in spks for i in test[s]]))
    rows = np.array([s for s in spks for _ in test[s]])
    S = T @ tmpl.T
    lab = rows[:, None] == np.array(spks)[None, :]
    return M.eer(S[lab], S[~lab])


def quantize_int8_(module: nn.Module) -> int:
    """Simulate int8 weights (symmetric, per output channel) in place; returns int8 byte count."""
    nbytes = 0
    for name, p in module.named_parameters():
        if p.dim() >= 2:
            w = p.data.float()
            flat = w.reshape(w.shape[0], -1)
            scale = flat.abs().amax(1, keepdim=True).clamp_min(1e-12) / 127.0
            q = torch.round(flat / scale).clamp(-127, 127)
            p.data.copy_((q * scale).reshape(w.shape).to(p.dtype))
            nbytes += q.numel() + 4 * scale.numel()
        else:
            nbytes += 4 * p.numel()
    return nbytes


# ----------------------------------------------------------------------------------------------
class Augment:
    def __init__(self, pool: torch.Tensor, spk: torch.Tensor, seed: int, p_babble: float, p_noise: float, device: str):
        self.pool, self.spk = pool, spk  # pool and spk stay on the CPU
        self.device = device
        self.g = torch.Generator(device=device).manual_seed(seed)
        self.gc = torch.Generator().manual_seed(seed + 7)
        self.p_babble, self.p_noise = p_babble, p_noise

    def _rand(self, *shape):
        return torch.rand(*shape, generator=self.g, device=self.device)

    def coloured(self, b, n):
        white = torch.randn(b, n, generator=self.g, device=self.device)
        spec = torch.fft.rfft(white)
        f = torch.arange(spec.shape[1], device=white.device).clamp_min(1).float()
        beta = torch.randint(0, 3, (b, 1), generator=self.g, device=white.device).float()  # white/pink/brown
        return torch.fft.irfft(spec / f[None] ** (beta / 2), n=n)

    def __call__(self, x: torch.Tensor, y: torch.Tensor, start: torch.Tensor) -> torch.Tensor:
        b, n = x.shape
        rms = lambda z: z.pow(2).mean(1, keepdim=True).add(1e-8).sqrt()  # noqa: E731
        sp = rms(x)
        out = x.clone()
        use_b = self._rand(b) < self.p_babble
        if use_b.any():
            bab = torch.zeros_like(x)
            k = torch.randint(1, 4, (b,), generator=self.g, device=x.device)
            ycpu = y.cpu()
            for j in range(3):
                idx = torch.randint(0, self.pool.shape[0], (b,), generator=self.gc)
                same = self.spk[idx] == ycpu
                idx = torch.where(same, (idx + 997) % self.pool.shape[0], idx)
                st = torch.randint(0, self.pool.shape[1] - n + 1, (b,), generator=self.gc)
                seg = self.pool[idx[:, None], st[:, None] + torch.arange(n)].to(x.device).float() / 32767.0
                bab += seg / rms(seg) * (j < k)[:, None]
            snr = self._rand(b, 1) * 20.0  # 0..20 dB
            out = out + torch.where(use_b[:, None], bab / rms(bab) * sp / 10 ** (snr / 20), torch.zeros_like(x))
        use_n = self._rand(b) < self.p_noise
        if use_n.any():
            nz = self.coloured(b, n)
            snr = 5.0 + self._rand(b, 1) * 25.0
            out = out + torch.where(use_n[:, None], nz / rms(nz) * sp / 10 ** (snr / 20), torch.zeros_like(x))
        gain = 10 ** ((self._rand(b, 1) * 12.0 - 6.0) / 20)
        out = out * gain
        return out.clamp(-1.0, 1.0)


def load_trained(model: Model, path: str, dev: str) -> None:
    ck = torch.load(path, map_location=dev)
    model.head.load_state_dict({k[5:]: v for k, v in ck["state"].items() if k.startswith("head.")})
    if model.branch is not None:
        bs = {k[7:]: v for k, v in ck["state"].items() if k.startswith("branch.")}
        missing, unexpected = model.branch.load_state_dict(bs, strict=False)
        assert not unexpected and all(not m.endswith((".a", ".b")) for m in missing), (missing[:3], unexpected[:3])


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", required=True)
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--pool", required=True)
    ap.add_argument("--trials", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--reps", nargs="+", required=True)
    ap.add_argument("--head", default="asp", choices=["asp", "tdnn"])
    ap.add_argument("--lora-layers", type=int, default=0)
    ap.add_argument("--lora-r", type=int, default=8)
    ap.add_argument("--steps", type=int, default=6000)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--lora-lr", type=float, default=5e-4)
    ap.add_argument("--margin", type=float, default=0.2)
    ap.add_argument("--eval-every", type=int, default=500)
    ap.add_argument("--p-babble", type=float, default=0.4)
    ap.add_argument("--p-noise", type=float, default=0.3)
    ap.add_argument("--seed", type=int, default=20261007)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--embed-only", default="", help="skip training: load <out>/head.pt, embed --trials, write this .npz")
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    dev = args.device
    os.makedirs(args.out, exist_ok=True)
    tower, mel, _ = load_tower(args.model_dir, dev)
    model = Model(tower, mel, args.reps, args.head, args.lora_layers, args.lora_r).to(dev)
    if args.embed_only:
        load_trained(model, os.path.join(args.out, "head.pt"), dev)
        _, tpcm, toff = load_trials(args.trials)
        E = embed_items(model, tpcm, toff, list(range(len(toff) - 1)), dev)
        np.savez(args.embed_only, **{f"head_{args.name}": E.astype(np.float32)})
        print("wrote", args.embed_only, E.shape)
        return

    meta = [json.loads(line) for line in open(os.path.join(args.pool, "pool_meta.jsonl"))]
    spk_ids = sorted({m["speaker"] for m in meta})
    spk_map = {s: i for i, s in enumerate(spk_ids)}
    pool = torch.from_numpy(np.load(os.path.join(args.pool, "pool_pcm16.npy")))  # CPU
    spk = torch.tensor([spk_map[m["speaker"]] for m in meta])
    valid_len = torch.tensor([int(m["seconds"] * SR) for m in meta])
    aug = Augment(pool, spk, args.seed, args.p_babble, args.p_noise, dev)
    loss_fn = AAMSoftmax(192, len(spk_ids)).to(dev)

    head_params = list(model.head.parameters()) + list(loss_fn.parameters())
    groups = [{"params": head_params, "lr": args.lr}]
    if model.branch is not None:
        groups.append({"params": [p for n, p in model.branch.named_parameters() if n.endswith((".a", ".b"))],
                       "lr": args.lora_lr, "weight_decay": 0.0})
        for n, p in model.branch.named_parameters():
            p.requires_grad_(n.endswith((".a", ".b")))
    opt = torch.optim.AdamW(groups, weight_decay=2e-5)
    base_lrs = [g["lr"] for g in groups]
    warm = 300

    items, tpcm, toff = load_trials(args.trials)
    g = torch.Generator().manual_seed(args.seed + 1)
    log = open(os.path.join(args.out, "train_log.jsonl"), "w")
    best, best_step = float("inf"), -1
    t0 = time.time()
    for step in range(1, args.steps + 1):
        model.train()
        frac = min(1.0, step / warm) if step <= warm else 0.5 * (1 + math.cos(math.pi * (step - warm) / (args.steps - warm)))
        for grp, lr in zip(opt.param_groups, base_lrs):
            grp["lr"] = lr * max(frac, 0.01)
        n = 640 * int(torch.randint(40, 76, (1,), generator=g))  # 1.6-3.0 s, 40 ms grid
        idx = torch.randint(0, pool.shape[0], (args.batch,), generator=g)
        maxst = (valid_len[idx] - n).clamp_min(0)
        st = (torch.rand(args.batch, generator=g) * (maxst + 1)).long()
        x = (pool[idx[:, None], st[:, None] + torch.arange(n)].to(dev).float() / 32767.0)
        y = spk[idx].to(dev)
        x = aug(x, y, st)
        m = args.margin * min(1.0, step / 1500)
        emb = model(x)
        loss, acc = loss_fn(emb.float(), y, m)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_([p for grp in opt.param_groups for p in grp["params"]], 5.0)
        opt.step()
        if step % 100 == 0:
            rec = {"step": step, "loss": float(loss), "acc": float(acc), "margin": m, "len_s": n / SR,
                   "layer_w": [round(float(v), 4) for v in model.head.layer_logits.softmax(0)], "t": round(time.time() - t0, 1)}
            if step % args.eval_every == 0 or step == args.steps:
                rec["dev_eer"] = dev_eer(model, items, tpcm, toff, dev)
                if rec["dev_eer"] < best:
                    best, best_step = rec["dev_eer"], step
                    torch.save({"state": model.trainable_state(), "args": vars(args)}, os.path.join(args.out, "head.pt"))
            log.write(json.dumps(rec) + "\n")
            log.flush()
            print(json.dumps(rec), flush=True)

    load_trained(model, os.path.join(args.out, "head.pt"), dev)
    all_idx = list(range(len(toff) - 1))
    E = embed_items(model, tpcm, toff, all_idx, dev)
    head_bytes_fp32 = 4 * n_params(model.head)
    w8_bytes = quantize_int8_(model.head)
    E8 = embed_items(model, tpcm, toff, all_idx, dev)
    np.savez(os.path.join(args.out, f"{args.name}.npz"), **{f"head_{args.name}": E.astype(np.float32),
                                                               f"head_{args.name}_w8": E8.astype(np.float32)})
    info = {
        "name": args.name, "reps": args.reps, "head": args.head, "best_dev_eer": best, "best_step": best_step,
        "head_params": n_params(model.head), "head_fp32_bytes": head_bytes_fp32, "head_int8_bytes": w8_bytes,
        "lora_layers": args.lora_layers, "lora_r": args.lora_r, "lora_params": n_params(model.branch, lora_only=True),
        "encoder_layers_needed": model.upto, "n_train_speakers": len(spk_ids), "n_train_rows": int(pool.shape[0]),
        "train_seconds": round(time.time() - t0, 1), "torch": torch.__version__, "args": vars(args),
    }
    json.dump(info, open(os.path.join(args.out, f"{args.name}.meta.json"), "w"), indent=2)
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()
