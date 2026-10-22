#!/usr/bin/env python3
"""Build a compact speaker-ID training pool from LibriSpeech train-clean-100/360 (CC BY 4.0).

Used to train lightweight speaker heads on the frozen Gemma 4 E2B / EmbeddingGemma 2 audio
encoder (``audio_speaker_head_train.py``). Speakers are disjoint from the benchmark's test
speakers (test-clean) and dev speakers (test-other) by LibriSpeech construction; this is
asserted against ``trials/build_info.json``.

Shards of the Hugging Face parquet mirror ``openslr/librispeech_asr`` are downloaded one at a
time, cropped and deleted, so peak disk use is about one shard (~0.5 GB) plus the pool.
For each speaker at most ``--per-speaker`` utterances are kept (first seen in shard order),
each as one energy-checked crop of ``--seconds`` seconds (int16, 16 kHz).

Output:
  pool_pcm16.npy  int16 (n_utts, seconds*16000)
  pool_meta.jsonl one line per row: {"row", "speaker", "utt", "split"}
  pool_info.json  sources, revision, licence, counts, sha256
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import time

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audio_speaker_build_trials import best_crop, to_pcm16  # noqa: E402

SR = 16000
REPO = "openslr/librispeech_asr"
REV = "71cacbfb7e2354c4226d01e70d77d5fca3d04ba1"


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True)
    ap.add_argument("--trials", required=True, help="benchmark trials dir (for the disjointness check)")
    ap.add_argument("--splits", nargs="+", default=["clean/train.100", "clean/train.360"])
    ap.add_argument("--per-speaker", type=int, default=36)
    ap.add_argument("--seconds", type=float, default=3.0)
    ap.add_argument("--seed", type=int, default=20261007)
    args = ap.parse_args()

    from huggingface_hub import HfApi, hf_hub_download

    info_trials = json.load(open(os.path.join(args.trials, "build_info.json")))
    excluded = {int(s) for s in info_trials["speakers_test_clean"]} | {int(s) for s in info_trials["speakers_test_other"]}

    os.makedirs(args.out, exist_ok=True)
    tmp = os.path.join(args.out, "_shard_tmp")
    files = sorted(
        f.path for f in HfApi().list_repo_tree(REPO, repo_type="dataset", revision=REV, recursive=True)
        if any(f.path.startswith(s + "/") for s in args.splits) and f.path.endswith(".parquet")
    )
    n = int(args.seconds * SR)
    rows: list[np.ndarray] = []
    meta: list[dict] = []
    counts: dict[int, int] = {}
    shard_hashes = {}
    rng = np.random.default_rng(args.seed)
    t0 = time.time()
    for k, path in enumerate(files):
        local = hf_hub_download(REPO, path, repo_type="dataset", revision=REV, local_dir=tmp)
        shard_hashes[path] = sha256_file(local)
        table = pq.read_table(local, columns=["audio", "speaker_id", "id"])
        split = path.rsplit("/", 1)[0]
        for i in range(table.num_rows):
            spk = int(table.column("speaker_id")[i].as_py())
            if spk in excluded:
                raise SystemExit(f"speaker {spk} overlaps the benchmark trials")
            if counts.get(spk, 0) >= args.per_speaker:
                continue
            wav, sr = sf.read(io.BytesIO(table.column("audio")[i].as_py()["bytes"]), dtype="float32")
            assert sr == SR
            if wav.size < int(1.5 * SR):
                continue
            seg, _ = best_crop(wav, min(args.seconds, wav.size / SR), rng)
            if seg.size < n:
                seg = np.pad(seg, (0, n - seg.size))  # padding is tracked via "seconds"
            rows.append(to_pcm16(seg))
            meta.append({"row": len(meta), "speaker": spk, "utt": table.column("id")[i].as_py(), "split": split,
                         "seconds": round(min(args.seconds, wav.size / SR), 3)})
            counts[spk] = counts.get(spk, 0) + 1
        del table
        shutil.rmtree(tmp, ignore_errors=True)
        print(f"[{k + 1}/{len(files)}] {path}: rows={len(rows)} speakers={len(counts)} {time.time() - t0:.0f}s", flush=True)

    pcm = np.stack(rows)
    np.save(os.path.join(args.out, "pool_pcm16.npy"), pcm)
    with open(os.path.join(args.out, "pool_meta.jsonl"), "w") as f:
        for m in meta:
            f.write(json.dumps(m) + "\n")
    info = {
        "source": {"hf_repo": REPO, "revision": REV, "splits": args.splits, "license": "CC BY 4.0",
                   "shard_sha256": shard_hashes},
        "per_speaker": args.per_speaker, "seconds": args.seconds, "seed": args.seed,
        "n_rows": int(pcm.shape[0]), "n_speakers": len(counts),
        "excluded_speakers_checked": len(excluded),
        "pool_pcm16_sha256": sha256_file(os.path.join(args.out, "pool_pcm16.npy")),
    }
    json.dump(info, open(os.path.join(args.out, "pool_info.json"), "w"), indent=2)
    print(json.dumps({k: v for k, v in info.items() if k != "source"}, indent=2))


if __name__ == "__main__":
    main()
