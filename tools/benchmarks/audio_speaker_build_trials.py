#!/usr/bin/env python3
"""Build the matched, deterministic trial set for the audio speaker-filter benchmark.

Public data only:
  * LibriSpeech test-clean (targets/impostors) and test-other (dev + babble), CC BY 4.0,
    via the Hugging Face parquet mirror ``openslr/librispeech_asr``.
  * MUSAN noise (CC BY 4.0) and, optionally, MUSAN music subsets (per-file licences in MUSAN).

Every model in the benchmark embeds exactly the same int16 PCM items written here, so all
trials (speakers, segments, enrollment sets, mixtures, seeds) are matched across models.

Output directory layout:
  items.jsonl     one JSON object per item (see ``Item`` fields below)
  audio_pcm16.npy concatenated int16 mono 16 kHz samples
  offsets.npy     int64 (n_items + 1) sample offsets into audio_pcm16.npy
  build_info.json sources, seed, counts
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import io
import json
import os
from dataclasses import asdict, dataclass, field

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

SR = 16000
SEGMENT_SECONDS = (1.0, 2.0, 3.5)
SNRS_DB = (0, 5, 10)


@dataclass
class Item:
    idx: int
    kind: str  # enroll | test | nonspeech | noisy | dev_enroll | dev_test
    split: str  # test-clean | test-other
    speaker: int = -1
    gender: str = ""
    utt: str = ""
    seconds: float = 0.0
    draw: int = -1  # enrollment draw index (enroll items)
    slot: int = -1  # position within the enrollment draw (0..4)
    test_slot: int = -1  # index of the test utterance for the speaker
    source: str = ""  # noise/music file or babble utterance ids
    noise_type: str = ""  # noise | music | babble
    snr_db: float = float("nan")
    extra: dict = field(default_factory=dict)


def read_speakers(path: str) -> dict[int, str]:
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith(";") or "|" not in line:
                continue
            parts = [p.strip() for p in line.split("|")]
            try:
                out[int(parts[0])] = parts[1]
            except ValueError:
                continue
    return out


def load_split(parquet_path: str) -> dict[int, list[tuple[str, np.ndarray]]]:
    table = pq.read_table(parquet_path, columns=["audio", "speaker_id", "id"])
    by_spk: dict[int, list[tuple[str, np.ndarray]]] = {}
    for row in table.to_pylist():
        wav, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32")
        assert sr == SR, sr
        by_spk.setdefault(int(row["speaker_id"]), []).append((row["id"], wav))
    for v in by_spk.values():
        v.sort(key=lambda x: x[0])
    return dict(sorted(by_spk.items()))


def frame_activity(wav: np.ndarray, frame: int = 400, hop: int = 160) -> np.ndarray:
    """Boolean per-hop speech-activity estimate from frame energy (simple, model-free)."""
    if wav.size < frame:
        wav = np.pad(wav, (0, frame - wav.size))
    n = 1 + (wav.size - frame) // hop
    idx = np.arange(frame)[None, :] + hop * np.arange(n)[:, None]
    e = 10 * np.log10(np.mean(wav[idx] ** 2, axis=1) + 1e-10)
    return (e > e.max() - 35.0) & (e > -55.0)


def best_crop(wav: np.ndarray, seconds: float, rng: np.random.Generator, min_active: float = 0.7) -> tuple[np.ndarray, int]:
    n = int(round(seconds * SR))
    if wav.size <= n:
        return np.pad(wav, (0, n - wav.size)), 0
    act = frame_activity(wav)
    hop = 160
    win = max(1, n // hop)
    starts = np.arange(0, wav.size - n + 1, hop)
    csum = np.r_[0, np.cumsum(act.astype(np.int64))]
    ratios = np.array([(csum[min(s // hop + win, act.size)] - csum[min(s // hop, act.size)]) / win for s in starts])
    good = starts[ratios >= min_active]
    start = int(rng.choice(good)) if good.size else int(starts[int(np.argmax(ratios))])
    return wav[start : start + n].copy(), start


def active_rms(wav: np.ndarray) -> float:
    act = frame_activity(wav)
    hop = 160
    mask = np.repeat(act, hop)[: wav.size]
    if mask.size < wav.size:
        mask = np.pad(mask, (0, wav.size - mask.size))
    sel = wav[mask] if mask.any() else wav
    return float(np.sqrt(np.mean(sel**2) + 1e-12))


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(x**2) + 1e-12))


def tile_crop(wav: np.ndarray, n: int, rng: np.random.Generator) -> np.ndarray:
    if wav.size < n:
        wav = np.tile(wav, int(np.ceil(n / max(wav.size, 1))) + 1)
    start = int(rng.integers(0, wav.size - n + 1))
    return wav[start : start + n].copy()


def mix_at_snr(speech: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    s = active_rms(speech)
    nz = rms(noise)
    noise = noise * (s / nz) / (10 ** (snr_db / 20.0))
    out = speech + noise
    peak = float(np.max(np.abs(out)))
    if peak > 0.99:
        out = out * (0.99 / peak)
    return out


def to_pcm16(x: np.ndarray) -> np.ndarray:
    return np.clip(np.round(x * 32767.0), -32768, 32767).astype(np.int16)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--librispeech-dir", required=True, help="dir with clean/test/0000.parquet and other/test/0000.parquet")
    ap.add_argument("--speakers-txt", required=True)
    ap.add_argument("--musan-noise-dir", required=True)
    ap.add_argument("--musan-music-dir", default="")
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=20261007)
    ap.add_argument("--n-draws", type=int, default=2)
    ap.add_argument("--n-enroll", type=int, default=5)
    ap.add_argument("--enroll-max-seconds", type=float, default=4.0)
    ap.add_argument("--n-test-utts", type=int, default=20)
    ap.add_argument("--n-noisy-utts", type=int, default=10)
    ap.add_argument("--n-noise", type=int, default=300)
    ap.add_argument("--n-music", type=int, default=100)
    ap.add_argument("--babble-talkers", type=int, default=4)
    ap.add_argument("--dev-test-utts", type=int, default=10)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    rng_root = np.random.SeedSequence(args.seed)
    genders = read_speakers(args.speakers_txt)
    clean_pq = os.path.join(args.librispeech_dir, "clean/test/0000.parquet")
    other_pq = os.path.join(args.librispeech_dir, "other/test/0000.parquet")
    clean = load_split(clean_pq)
    other = load_split(other_pq)
    assert not set(clean) & set(other), "test-clean and test-other speakers must be disjoint"

    items: list[Item] = []
    chunks: list[np.ndarray] = []

    def add(item: Item, wav: np.ndarray) -> None:
        item.idx = len(items)
        item.seconds = round(wav.size / SR, 4)
        items.append(item)
        chunks.append(to_pcm16(wav))

    # ---- targets / impostors: LibriSpeech test-clean -------------------------------------
    n_enroll_total = args.n_draws * args.n_enroll
    test_utts_by_spk: dict[int, list[tuple[str, np.ndarray]]] = {}
    for spk, utts in clean.items():
        rng = np.random.default_rng(np.random.SeedSequence([args.seed, spk]))
        order = rng.permutation(len(utts))
        utts = [utts[i] for i in order]
        enroll_pool = [u for u in utts if u[1].size >= 2.0 * SR][:n_enroll_total]
        assert len(enroll_pool) == n_enroll_total, (spk, len(enroll_pool))
        used = {u[0] for u in enroll_pool}
        for k, (uid, wav) in enumerate(enroll_pool):
            seg, start = best_crop(wav, min(args.enroll_max_seconds, wav.size / SR), rng)
            add(Item(0, "enroll", "test-clean", spk, genders[spk], uid, draw=k // args.n_enroll, slot=k % args.n_enroll,
                     extra={"start": start}), seg)
        rest = [u for u in utts if u[0] not in used and u[1].size >= 4.0 * SR]
        if len(rest) < args.n_test_utts:
            have = {u[0] for u in rest}
            rest += [u for u in utts if u[0] not in used and u[0] not in have and u[1].size >= 2.0 * SR]
        test = rest[: args.n_test_utts]
        assert len(test) == args.n_test_utts, (spk, len(test))
        test_utts_by_spk[spk] = test
        for t, (uid, wav) in enumerate(test):
            for secs in SEGMENT_SECONDS:
                seg, start = best_crop(wav, secs, rng)
                add(Item(0, "test", "test-clean", spk, genders[spk], uid, test_slot=t, extra={"start": start, "len": secs}), seg)

    # ---- non-speech negatives: MUSAN noise (+ music) -------------------------------------
    noise_files = sorted(glob.glob(os.path.join(args.musan_noise_dir, "**", "*.wav"), recursive=True))
    rng = np.random.default_rng(np.random.SeedSequence([args.seed, 1]))
    perm = rng.permutation(len(noise_files))
    neg_noise = [noise_files[i] for i in perm[: args.n_noise]]
    mix_noise = [noise_files[i] for i in perm[args.n_noise :]]
    assert len(mix_noise) >= 100, len(mix_noise)

    def load_any(path: str) -> np.ndarray:
        wav, sr = sf.read(path, dtype="float32", always_2d=True)
        wav = wav.mean(axis=1)
        if sr != SR:
            import scipy.signal as ss

            wav = ss.resample_poly(wav, SR, sr).astype(np.float32)
        return wav

    def level(wav: np.ndarray, rng: np.random.Generator) -> np.ndarray:
        target_db = rng.uniform(-32.0, -20.0)  # typical speech-like loudness so level is not a cue
        return wav * (10 ** (target_db / 20.0) / rms(wav))

    for path in neg_noise:
        wav = load_any(path)
        for secs in SEGMENT_SECONDS:
            seg = level(tile_crop(wav, int(secs * SR), rng), rng)
            add(Item(0, "nonspeech", "musan", noise_type="noise", source=os.path.relpath(path, args.musan_noise_dir),
                     extra={"len": secs}), np.clip(seg, -0.99, 0.99))
    music_files = []
    if args.musan_music_dir:
        music_files = sorted(glob.glob(os.path.join(args.musan_music_dir, "**", "*.wav"), recursive=True))
        rng_m = np.random.default_rng(np.random.SeedSequence([args.seed, 2]))
        music_files = [music_files[i] for i in rng_m.permutation(len(music_files))[: args.n_music]]
        for path in music_files:
            wav = load_any(path)
            for secs in SEGMENT_SECONDS:
                seg = level(tile_crop(wav, int(secs * SR), rng_m), rng_m)
                add(Item(0, "nonspeech", "musan", noise_type="music", source=os.path.relpath(path, args.musan_music_dir),
                         extra={"len": secs}), np.clip(seg, -0.99, 0.99))

    # ---- noisy targets: user speech + MUSAN noise / test-other babble ---------------------
    other_utts = [(spk, uid, wav) for spk, us in other.items() for uid, wav in us]
    rng = np.random.default_rng(np.random.SeedSequence([args.seed, 3]))
    for spk, test in test_utts_by_spk.items():
        for t, (uid, wav) in enumerate(test[: args.n_noisy_utts]):
            for secs in SEGMENT_SECONDS:
                n = int(secs * SR)
                # reuse the exact clean test crop as the speech component
                clean_item = next(it for it in items if it.kind == "test" and it.utt == uid and it.extra["len"] == secs)
                speech = chunks[clean_item.idx].astype(np.float32) / 32767.0
                for snr in SNRS_DB:
                    npath = mix_noise[int(rng.integers(len(mix_noise)))]
                    noise = tile_crop(load_any(npath), n, rng)
                    add(Item(0, "noisy", "test-clean", spk, genders[spk], uid, test_slot=t, noise_type="noise",
                             snr_db=float(snr), source=os.path.relpath(npath, args.musan_noise_dir),
                             extra={"len": secs, "clean_idx": clean_item.idx}), mix_at_snr(speech, noise, snr))
                    picks = rng.choice(len(other_utts), size=args.babble_talkers * 3, replace=False)
                    talkers, seen = [], set()
                    for p in picks:
                        if other_utts[p][0] not in seen:
                            seen.add(other_utts[p][0])
                            talkers.append(other_utts[p])
                        if len(talkers) == args.babble_talkers:
                            break
                    babble = np.zeros(n, dtype=np.float32)
                    for _, _, bw in talkers:
                        piece = tile_crop(bw, n, rng)
                        babble += piece / rms(piece)
                    add(Item(0, "noisy", "test-clean", spk, genders[spk], uid, test_slot=t, noise_type="babble",
                             snr_db=float(snr), source="+".join(u for _, u, _ in talkers),
                             extra={"len": secs, "clean_idx": clean_item.idx}), mix_at_snr(speech, babble, snr))

    # ---- dev set (test-other): background mean + layer selection --------------------------
    for spk, utts in other.items():
        rng = np.random.default_rng(np.random.SeedSequence([args.seed, 7, spk]))
        utts = [utts[i] for i in rng.permutation(len(utts))]
        enroll = [u for u in utts if u[1].size >= 2.0 * SR][: args.n_enroll]
        used = {u[0] for u in enroll}
        for k, (uid, wav) in enumerate(enroll):
            seg, _ = best_crop(wav, min(args.enroll_max_seconds, wav.size / SR), rng)
            add(Item(0, "dev_enroll", "test-other", spk, genders.get(spk, ""), uid, draw=0, slot=k), seg)
        rest = [u for u in utts if u[0] not in used and u[1].size >= 2.5 * SR][: args.dev_test_utts]
        for t, (uid, wav) in enumerate(rest):
            seg, _ = best_crop(wav, 2.0, rng)
            add(Item(0, "dev_test", "test-other", spk, genders.get(spk, ""), uid, test_slot=t, extra={"len": 2.0}), seg)

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
    info = {
        "seed": args.seed,
        "args": vars(args),
        "counts": counts,
        "n_items": len(items),
        "total_seconds": float(offsets[-1] / SR),
        "speakers_test_clean": {str(s): genders[s] for s in clean},
        "speakers_test_other": {str(s): genders.get(s, "") for s in other},
        "sources": {
            "librispeech": {
                "hf_repo": "openslr/librispeech_asr",
                "revision": "71cacbfb7e2354c4226d01e70d77d5fca3d04ba1",
                "license": "CC BY 4.0",
                "clean_test_sha256": sha256_file(clean_pq),
                "other_test_sha256": sha256_file(other_pq),
                "speakers_txt_sha256": sha256_file(args.speakers_txt),
            },
            "musan_noise": {"dir": args.musan_noise_dir, "n_files": len(noise_files), "license": "CC BY 4.0 (MUSAN)"},
            "musan_music": {"dir": args.musan_music_dir, "n_files_used": len(music_files),
                            "license": "MUSAN music: per-file CC licences listed in MUSAN LICENSE files"},
        },
        "audio_pcm16_sha256": sha256_file(os.path.join(args.out, "audio_pcm16.npy")),
        "items_sha256": sha256_file(os.path.join(args.out, "items.jsonl")),
    }
    with open(os.path.join(args.out, "build_info.json"), "w", encoding="utf-8") as f:
        json.dump(info, f, indent=2)
    print(json.dumps({"n_items": len(items), "counts": counts, "total_seconds": info["total_seconds"]}, indent=2))


if __name__ == "__main__":
    main()
