#!/usr/bin/env python3
"""Write a tiny public-data fixture for the on-device AudioSpeakerLiteRtProbeTest.

Takes 2 LibriSpeech test-clean speakers (one F, one M) from the benchmark trial set and
writes 2-second clips as raw little-endian int16 mono 16 kHz files (``<name>.pcm16``) plus
``manifest.json`` with each clip's role and the host LiteRT embedding (computed with the
same .tflite through ai_edge_litert), so the device test can check numeric parity.

Roles: enroll (3 per speaker, first 2 s of each enrollment phrase), target (clean 2 s test
crops), babble10 (the speaker's test crop + 4-talker babble at 10 dB), nonspeech (MUSAN noise).
No personal audio is used; LibriSpeech/MUSAN are CC BY 4.0.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os

import numpy as np

N = 32000


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trials", required=True)
    ap.add_argument("--tflite", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--n-target", type=int, default=3)
    args = ap.parse_args()

    from ai_edge_litert.interpreter import Interpreter

    itp = Interpreter(model_path=args.tflite, num_threads=4)
    itp.allocate_tensors()
    inp = itp.get_input_details()[0]
    out = itp.get_output_details()[0]
    assert list(inp["shape"]) == [1, N], inp["shape"]

    def embed(w: np.ndarray) -> np.ndarray:
        itp.set_tensor(inp["index"], w[None].astype(np.float32))
        itp.invoke()
        return itp.get_tensor(out["index"])[0].astype(np.float64)

    items = [json.loads(line) for line in open(os.path.join(args.trials, "items.jsonl"), encoding="utf-8")]
    pcm = np.load(os.path.join(args.trials, "audio_pcm16.npy"), mmap_mode="r")
    offsets = np.load(os.path.join(args.trials, "offsets.npy"))
    clip = lambda i: np.asarray(pcm[offsets[i]: offsets[i + 1]], dtype=np.int16)

    spk_gender = {it["speaker"]: it["gender"] for it in items if it["kind"] == "test"}
    speakers = [sorted(s for s, g in spk_gender.items() if g == "F")[0], sorted(s for s, g in spk_gender.items() if g == "M")[0]]

    os.makedirs(args.out, exist_ok=True)
    entries = []

    def add(name: str, role: str, spk: int, x: np.ndarray, source_idx: int) -> None:
        x = x[:N] if x.size >= N else np.pad(x, (0, N - x.size))
        path = os.path.join(args.out, name + ".pcm16")
        x.astype("<i2").tofile(path)
        e = embed(x.astype(np.float32) / 32767.0)
        entries.append({"name": name, "file": name + ".pcm16", "role": role, "speaker": spk, "source_item": source_idx,
                        "host_embedding": [round(float(v), 6) for v in e]})

    for spk in speakers:
        enr = sorted((it for it in items if it["kind"] == "enroll" and it["speaker"] == spk and it["draw"] == 0),
                     key=lambda it: it["slot"])[:3]
        for k, it in enumerate(enr):
            add(f"spk{spk}_enroll{k}", "enroll", spk, clip(it["idx"]), it["idx"])
        tests = [it for it in items if it["kind"] == "test" and it["speaker"] == spk and it["extra"]["len"] == 2.0]
        for k, it in enumerate(tests[: args.n_target]):
            add(f"spk{spk}_target{k}", "target", spk, clip(it["idx"]), it["idx"])
        bab = [it for it in items if it["kind"] == "noisy" and it["speaker"] == spk and it["extra"]["len"] == 2.0
               and it["noise_type"] == "babble" and it["snr_db"] == 10.0]
        add(f"spk{spk}_babble10", "babble10", spk, clip(bab[0]["idx"]), bab[0]["idx"])
    noise = [it for it in items if it["kind"] == "nonspeech" and it["extra"]["len"] == 2.0]
    for k, it in enumerate(noise[:2]):
        add(f"nonspeech{k}", "nonspeech", -1, clip(it["idx"]), it["idx"])

    h = hashlib.sha256(open(args.tflite, "rb").read()).hexdigest()
    manifest = {"sample_rate": 16000, "samples": N, "format": "pcm_s16le_mono", "tflite_sha256": h,
                "speakers": speakers, "clips": entries,
                "note": "Public LibriSpeech test-clean / MUSAN audio only (CC BY 4.0)."}
    with open(os.path.join(args.out, "manifest.json"), "w") as f:
        json.dump(manifest, f)

    # host-side sanity summary
    def unit(v):
        v = np.asarray(v)
        return v / np.linalg.norm(v)

    for spk in speakers:
        tmpl = unit(np.mean([unit(e["host_embedding"]) for e in entries if e["role"] == "enroll" and e["speaker"] == spk], 0))
        for e in entries:
            if e["role"] != "enroll":
                print(f"template {spk} vs {e['name']:22s} cos={float(unit(e['host_embedding']) @ tmpl):.3f}")
    print(json.dumps({"out": args.out, "clips": len(entries), "tflite_sha256": h}))


if __name__ == "__main__":
    main()
