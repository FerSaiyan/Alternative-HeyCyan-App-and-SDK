#!/usr/bin/env python3
"""Out-of-domain trial set for the audio speaker-filter benchmark: VCTK (CC BY 4.0).

Same item schema and protocol as ``audio_speaker_build_trials.py`` (LibriSpeech), so the same
``audio_speaker_eval.py`` scores it. Purpose: the Gemma speaker heads are trained on
LibriSpeech train speakers, so LibriSpeech test-clean is in-domain for them but not for
ECAPA/WeSpeaker (VoxCeleb). VCTK (studio, British/other accents, different microphones and
texts) is out of domain for all of them and was not used to train any of the models compared.

Source: Hugging Face parquet mirror ``sanchit-gandhi/vctk`` of VCTK 0.92
(``wav48_silence_trimmed``), mic1 only, resampled 48 -> 16 kHz. Shards are downloaded one at a
time and deleted. The first ``--n-test`` qualifying speakers are targets/impostors, the next
``--n-dev`` speakers are the dev split (centering choice) and the babble source.
Non-speech negatives and noise mixtures use the same MUSAN noise files as the LibriSpeech set.
Segment lengths 1.0 / 2.0 / 3.5 s; VCTK utterances are short, so 3.5 s crops are often padded
with trailing silence (recorded per item as ``extra.padded``).
"""

from __future__ import annotations

import argparse
import glob
import io
import json
import os
import shutil
import sys
from dataclasses import asdict

import numpy as np
import pyarrow.parquet as pq
import scipy.signal as ss
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audio_speaker_build_trials import (  # noqa: E402
    SEGMENT_SECONDS, SNRS_DB, SR, Item, best_crop, mix_at_snr, rms, sha256_file, tile_crop, to_pcm16,
)

REPO = "sanchit-gandhi/vctk"
REV = "73ef4ee7d49a6fed4ea1efd65f82b4c95faeb9de"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--musan-noise-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=20261007)
    ap.add_argument("--n-test", type=int, default=40)
    ap.add_argument("--n-dev", type=int, default=12)
    ap.add_argument("--n-draws", type=int, default=2)
    ap.add_argument("--n-enroll", type=int, default=5)
    ap.add_argument("--n-test-utts", type=int, default=20)
    ap.add_argument("--n-noisy-utts", type=int, default=10)
    ap.add_argument("--n-noise", type=int, default=300)
    ap.add_argument("--dev-test-utts", type=int, default=10)
    ap.add_argument("--babble-talkers", type=int, default=4)
    args = ap.parse_args()

    from huggingface_hub import HfApi, hf_hub_download

    os.makedirs(args.out, exist_ok=True)
    tmp = os.path.join(args.out, "_shard_tmp")
    files = sorted(f.path for f in HfApi().list_repo_tree(REPO, repo_type="dataset", revision=REV, recursive=True)
                   if f.path.endswith(".parquet"))
    need = args.n_test + args.n_dev
    spk_utts: dict[str, list[tuple[str, np.ndarray]]] = {}
    gender: dict[str, str] = {}
    shard_hashes = {}
    min_utts = args.n_draws * args.n_enroll + args.n_test_utts
    for path in files:
        local = hf_hub_download(REPO, path, repo_type="dataset", revision=REV, local_dir=tmp)
        shard_hashes[path] = sha256_file(local)
        t = pq.read_table(local, columns=["speaker_id", "audio", "gender"])
        for row in t.to_pylist():
            name = row["audio"]["path"]
            if not name.endswith("_mic1.flac"):
                continue
            wav, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32")
            if wav.ndim > 1:
                wav = wav.mean(1)
            wav = ss.resample_poly(wav, SR, sr).astype(np.float32)
            spk_utts.setdefault(row["speaker_id"], []).append((name[:-5], wav))
            gender[row["speaker_id"]] = row["gender"]
        shutil.rmtree(tmp, ignore_errors=True)
        ok = [s for s, u in spk_utts.items() if sum(w.size >= 2.0 * SR for _, w in u) >= min_utts]
        print(f"{path}: speakers={len(spk_utts)} qualifying={len(ok)}", flush=True)
        if len(ok) >= need:
            break
    qual = sorted(s for s, u in spk_utts.items() if sum(w.size >= 2.0 * SR for _, w in u) >= min_utts)[:need]
    test_spk, dev_spk = qual[: args.n_test], qual[args.n_test :]
    spk_num = {s: int(s.lstrip("p").lstrip("s") or 0) for s in qual}

    items: list[Item] = []
    chunks: list[np.ndarray] = []

    def add(item: Item, wav: np.ndarray) -> None:
        item.idx = len(items)
        item.seconds = round(wav.size / SR, 4)
        items.append(item)
        chunks.append(to_pcm16(wav))

    test_utts_by_spk = {}
    for s in test_spk:
        rng = np.random.default_rng(np.random.SeedSequence([args.seed, spk_num[s]]))
        utts = sorted(spk_utts[s], key=lambda u: u[0])
        utts = [utts[i] for i in rng.permutation(len(utts)) if utts[i][1].size >= 2.0 * SR]
        enroll = utts[: args.n_draws * args.n_enroll]
        rest = sorted(utts[len(enroll):], key=lambda u: -u[1].size)[: args.n_test_utts]  # longest remaining
        for k, (uid, wav) in enumerate(enroll):
            seg, start = best_crop(wav, min(4.0, wav.size / SR), rng)
            add(Item(0, "enroll", "vctk", spk_num[s], gender[s], uid, draw=k // args.n_enroll, slot=k % args.n_enroll,
                     extra={"start": start}), seg)
        test_utts_by_spk[s] = rest
        for t_i, (uid, wav) in enumerate(rest):
            for secs in SEGMENT_SECONDS:
                seg, start = best_crop(wav, secs, rng)
                add(Item(0, "test", "vctk", spk_num[s], gender[s], uid, test_slot=t_i,
                         extra={"start": start, "len": secs, "padded": bool(wav.size < secs * SR)}), seg)

    noise_files = sorted(glob.glob(os.path.join(args.musan_noise_dir, "**", "*.wav"), recursive=True))
    rng = np.random.default_rng(np.random.SeedSequence([args.seed, 1]))
    perm = rng.permutation(len(noise_files))
    neg_noise = [noise_files[i] for i in perm[: args.n_noise]]
    mix_noise = [noise_files[i] for i in perm[args.n_noise :]]

    def load_any(path: str) -> np.ndarray:
        wav, sr = sf.read(path, dtype="float32", always_2d=True)
        wav = wav.mean(axis=1)
        return wav if sr == SR else ss.resample_poly(wav, SR, sr).astype(np.float32)

    for path in neg_noise:
        wav = load_any(path)
        for secs in SEGMENT_SECONDS:
            seg = tile_crop(wav, int(secs * SR), rng)
            seg = seg * (10 ** (rng.uniform(-32.0, -20.0) / 20.0) / rms(seg))
            add(Item(0, "nonspeech", "musan", noise_type="noise", source=os.path.relpath(path, args.musan_noise_dir),
                     extra={"len": secs}), np.clip(seg, -0.99, 0.99))

    dev_utts = [(s, uid, wav) for s in dev_spk for uid, wav in spk_utts[s]]
    rng = np.random.default_rng(np.random.SeedSequence([args.seed, 3]))
    for s, rest in test_utts_by_spk.items():
        for t_i, (uid, wav) in enumerate(rest[: args.n_noisy_utts]):
            for secs in SEGMENT_SECONDS:
                n = int(secs * SR)
                clean_item = next(it for it in items if it.kind == "test" and it.utt == uid and it.extra["len"] == secs)
                speech = chunks[clean_item.idx].astype(np.float32) / 32767.0
                for snr in SNRS_DB:
                    npath = mix_noise[int(rng.integers(len(mix_noise)))]
                    noise = tile_crop(load_any(npath), n, rng)
                    add(Item(0, "noisy", "vctk", spk_num[s], gender[s], uid, test_slot=t_i, noise_type="noise",
                             snr_db=float(snr), source=os.path.relpath(npath, args.musan_noise_dir),
                             extra={"len": secs, "clean_idx": clean_item.idx}), mix_at_snr(speech, noise, snr))
                    picks = rng.choice(len(dev_utts), size=args.babble_talkers * 4, replace=False)
                    talkers, seen = [], set()
                    for p in picks:
                        if dev_utts[p][0] not in seen:
                            seen.add(dev_utts[p][0])
                            talkers.append(dev_utts[p])
                        if len(talkers) == args.babble_talkers:
                            break
                    babble = np.zeros(n, dtype=np.float32)
                    for _, _, bw in talkers:
                        piece = tile_crop(bw, n, rng)
                        babble += piece / rms(piece)
                    add(Item(0, "noisy", "vctk", spk_num[s], gender[s], uid, test_slot=t_i, noise_type="babble",
                             snr_db=float(snr), source="+".join(u for _, u, _ in talkers),
                             extra={"len": secs, "clean_idx": clean_item.idx}), mix_at_snr(speech, babble, snr))

    for s in dev_spk:
        rng = np.random.default_rng(np.random.SeedSequence([args.seed, 7, spk_num[s]]))
        utts = sorted(spk_utts[s], key=lambda u: u[0])
        utts = [utts[i] for i in rng.permutation(len(utts)) if utts[i][1].size >= 2.0 * SR]
        for k, (uid, wav) in enumerate(utts[: args.n_enroll]):
            seg, _ = best_crop(wav, min(4.0, wav.size / SR), rng)
            add(Item(0, "dev_enroll", "vctk", spk_num[s], gender[s], uid, draw=0, slot=k), seg)
        for t_i, (uid, wav) in enumerate(utts[args.n_enroll : args.n_enroll + args.dev_test_utts]):
            seg, _ = best_crop(wav, 2.0, rng)
            add(Item(0, "dev_test", "vctk", spk_num[s], gender[s], uid, test_slot=t_i, extra={"len": 2.0}), seg)

    offsets = np.zeros(len(chunks) + 1, dtype=np.int64)
    offsets[1:] = np.cumsum([c.size for c in chunks])
    np.save(os.path.join(args.out, "audio_pcm16.npy"), np.concatenate(chunks))
    np.save(os.path.join(args.out, "offsets.npy"), offsets)
    with open(os.path.join(args.out, "items.jsonl"), "w", encoding="utf-8") as f:
        for it in items:
            d = asdict(it)
            if d["snr_db"] != d["snr_db"]:
                d["snr_db"] = None
            f.write(json.dumps(d) + "\n")
    counts: dict[str, int] = {}
    for it in items:
        key = it.kind + (f":{it.noise_type}" if it.noise_type else "")
        counts[key] = counts.get(key, 0) + 1
    n_pad = sum(1 for it in items if it.kind == "test" and it.extra.get("padded"))
    info = {"seed": args.seed, "args": vars(args), "counts": counts, "n_items": len(items),
            "test_speakers": {s: gender[s] for s in test_spk}, "dev_speakers": {s: gender[s] for s in dev_spk},
            "padded_test_items": n_pad,
            "sources": {"vctk": {"hf_repo": REPO, "revision": REV, "license": "CC BY 4.0 (VCTK 0.92)", "mic": "mic1",
                                 "shard_sha256": shard_hashes},
                        "musan_noise": {"dir": args.musan_noise_dir, "license": "CC BY 4.0 (MUSAN)"}},
            "audio_pcm16_sha256": sha256_file(os.path.join(args.out, "audio_pcm16.npy")),
            "items_sha256": sha256_file(os.path.join(args.out, "items.jsonl"))}
    json.dump(info, open(os.path.join(args.out, "build_info.json"), "w"), indent=2)
    print(json.dumps({k: info[k] for k in ("n_items", "counts", "padded_test_items")}, indent=2))


if __name__ == "__main__":
    main()
