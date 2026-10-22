#!/usr/bin/env python3
"""Score matched speaker-verification trials for every embedding representation.

Reads items.jsonl (audio_speaker_build_trials.py) and <emb-dir>/<model>.npz
(audio_speaker_embed.py) and writes results.json + summary.md.

Protocol (identical for every representation):
  * Enrollment: 2 independent draws x {3, 5} phrases (2-4 s crops) per LibriSpeech test-clean
    speaker (40 speakers). Template = mean of L2-normalised embeddings ("mean") or the max
    cosine over individual phrases ("max").
  * (a) other speakers: every other test-clean speaker's test segments; same-gender subset too.
  * (b) non-speech: MUSAN noise and MUSAN music crops, levelled to speech-like loudness.
  * (c) the enrolled user's own test segments mixed with MUSAN noise or 4-talker test-other
    babble at 0/5/10 dB SNR. Reported as acceptance (TPR) at the thresholds that give 1% / 5%
    false accepts on (a), plus EER versus (a).
  * Segment lengths 1.0 / 2.0 / 3.5 s. Scores are pooled over the two enrollment draws.
  * Choices that need data (best encoder layer, raw vs dev-mean-centred cosine) are made on a
    disjoint dev set (LibriSpeech test-other speakers), never on the test trials.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audio_speaker_metrics as M  # noqa: E402

LENGTHS = (1.0, 2.0, 3.5)
SNRS = (0.0, 5.0, 10.0)

# What a phone app could actually obtain today (see AUDIO_SPEAKER_FILTER.md).
DEPLOYABLE = {
    "ecapa_192": "ECAPA-TDNN (SpeechBrain) 192-d",
    "wespeaker_256": "WeSpeaker ResNet34-LM 256-d",
    "eg2_final_768": "EmbeddingGemma 2 final 768-d (LiteRT-LM EmbeddingEngine output)",
    "eg2_final_mrl256": "EmbeddingGemma 2 final, MRL 256-d",
    "eg2_final_mrl128": "EmbeddingGemma 2 final, MRL 128-d",
}


def norm(x: np.ndarray) -> np.ndarray:
    return M.l2_normalize(np.asarray(x, dtype=np.float64))


class Trials:
    def __init__(self, items: list[dict]):
        self.items = items
        self.enroll = defaultdict(lambda: defaultdict(list))  # spk -> draw -> [(slot, idx)]
        self.test = defaultdict(list)  # L -> [idx]
        self.nonspeech = defaultdict(lambda: defaultdict(list))  # type -> L -> [idx]
        self.noisy = defaultdict(list)  # (L, type, snr) -> [idx]
        self.dev_enroll = defaultdict(list)
        self.dev_test = defaultdict(list)
        self.gender = {}
        for it in items:
            k, i = it["kind"], it["idx"]
            if it["speaker"] >= 0:
                self.gender[it["speaker"]] = it["gender"]
            if k == "enroll":
                self.enroll[it["speaker"]][it["draw"]].append((it["slot"], i))
            elif k == "test":
                self.test[it["extra"]["len"]].append(i)
            elif k == "nonspeech":
                self.nonspeech[it["noise_type"]][it["extra"]["len"]].append(i)
            elif k == "noisy":
                self.noisy[(it["extra"]["len"], it["noise_type"], float(it["snr_db"]))].append(i)
            elif k == "dev_enroll":
                self.dev_enroll[it["speaker"]].append(i)
            elif k == "dev_test":
                self.dev_test[it["speaker"]].append(i)
        self.speakers = sorted(self.enroll)
        self.draws = sorted({d for s in self.enroll.values() for d in s})
        self.spk_of = np.array([it["speaker"] for it in items])
        self.dev_idx = sorted(i for v in self.dev_enroll.values() for i in v) + sorted(
            i for v in self.dev_test.values() for i in v)

    def enroll_idx(self, spk: int, draw: int, n: int) -> list[int]:
        return [i for _, i in sorted(self.enroll[spk][draw])[:n]]


def score_matrix(X: np.ndarray, test_idx, templates_idx: list[list[int]], mode: str, center) -> np.ndarray:
    """Scores (n_test, n_speakers) of test items against each speaker's enrollment set."""
    T = X[test_idx]
    if center is not None:
        T = T - center
    T = norm(T)
    if mode == "mean":
        tmpl = np.stack([M.enrollment_template(X[e], center) for e in templates_idx])
        return T @ tmpl.T
    out = np.empty((len(test_idx), len(templates_idx)))
    for j, e in enumerate(templates_idx):
        E = X[e] - center if center is not None else X[e]
        out[:, j] = (T @ norm(E).T).max(axis=1)
    return out


def dev_eer(X: np.ndarray, tr: Trials, center) -> float:
    spks = sorted(tr.dev_enroll)
    S = score_matrix(X, [i for s in spks for i in tr.dev_test[s]], [tr.dev_enroll[s] for s in spks], "mean", center)
    rows = np.array([s for s in spks for _ in tr.dev_test[s]])
    lab = rows[:, None] == np.array(spks)[None, :]
    return M.eer(S[lab], S[~lab])


def evaluate_rep(X: np.ndarray, tr: Trials, center, n_enroll: int, mode: str, bootstrap: int = 0, seed: int = 0) -> dict:
    spks = tr.speakers
    spk_arr = np.array(spks)
    gender = np.array([tr.gender[s] for s in spks])
    res = {}
    for L in LENGTHS:
        tgt, imp, imp_sg = [], [], []
        per_spk_tgt = defaultdict(list)
        per_spk_imp = defaultdict(list)
        ns = defaultdict(list)
        noisy = defaultdict(list)
        test_idx = tr.test[L]
        test_spk = tr.spk_of[test_idx]
        test_gender = np.array([tr.gender[s] for s in test_spk])
        for d in tr.draws:
            tmpl = [tr.enroll_idx(s, d, n_enroll) for s in spks]
            S = score_matrix(X, test_idx, tmpl, mode, center)
            same = test_spk[:, None] == spk_arr[None, :]
            sg = test_gender[:, None] == gender[None, :]
            tgt.append(S[same])
            imp.append(S[~same])
            imp_sg.append(S[~same & sg])
            for j, s in enumerate(spks):
                per_spk_tgt[s].append(S[test_spk == s, j])
                per_spk_imp[s].append(S[test_spk != s, j])
            for typ, by_len in tr.nonspeech.items():
                ns[typ].append(score_matrix(X, by_len[L], tmpl, mode, center).ravel())
            for (LL, typ, snr), idx in tr.noisy.items():
                if LL != L:
                    continue
                Sn = score_matrix(X, idx, tmpl, mode, center)
                col = np.searchsorted(spk_arr, tr.spk_of[idx])
                noisy[(typ, snr)].append(Sn[np.arange(len(idx)), col])
        tgt, imp, imp_sg = map(np.concatenate, (tgt, imp, imp_sg))
        thr1 = M.threshold_at_fpr(imp, 0.01)
        thr5 = M.threshold_at_fpr(imp, 0.05)
        r = {
            "a_all": M.summarize(tgt, imp),
            "a_same_gender": M.summarize(tgt, imp_sg),
            "thr_fpr1": thr1,
            "thr_fpr5": thr5,
        }
        for typ, v in ns.items():
            v = np.concatenate(v)
            r[f"b_{typ}"] = {"eer": M.eer(tgt, v), "far_at_thr_fpr1": M.fpr_at_threshold(v, thr1),
                             "far_at_thr_fpr5": M.fpr_at_threshold(v, thr5), "n": int(v.size)}
        allns = np.concatenate([np.concatenate(v) for v in ns.values()])
        r["b_all"] = {"eer": M.eer(tgt, allns), "far_at_thr_fpr1": M.fpr_at_threshold(allns, thr1),
                      "far_at_thr_fpr5": M.fpr_at_threshold(allns, thr5), "n": int(allns.size)}
        for (typ, snr), v in sorted(noisy.items()):
            v = np.concatenate(v)
            r[f"c_{typ}_{int(snr)}db"] = {"tpr_at_thr_fpr1": M.tpr_at_threshold(v, thr1),
                                         "tpr_at_thr_fpr5": M.tpr_at_threshold(v, thr5),
                                         "eer_vs_a": M.eer(v, imp), "n": int(v.size)}
        if bootstrap:
            rng = np.random.default_rng(seed)
            vals = []
            for _ in range(bootstrap):
                pick = rng.choice(spks, size=len(spks), replace=True)
                t = np.concatenate([np.concatenate(per_spk_tgt[s]) for s in pick])
                i = np.concatenate([np.concatenate(per_spk_imp[s]) for s in pick])
                vals.append(M.eer(t, i))
            r["a_all"]["eer_ci95"] = [float(np.percentile(vals, 2.5)), float(np.percentile(vals, 97.5))]
        res[str(L)] = r
    return res


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--trials", required=True)
    ap.add_argument("--emb-dir", required=True)
    ap.add_argument("--models", nargs="+", default=["ecapa", "wespeaker", "eg2", "gemma4"])
    ap.add_argument("--out", required=True)
    ap.add_argument("--bootstrap", type=int, default=200)
    args = ap.parse_args()

    items = [json.loads(line) for line in open(os.path.join(args.trials, "items.jsonl"), encoding="utf-8")]
    tr = Trials(items)
    reps: dict[str, np.ndarray] = {}
    for m in args.models:
        p = os.path.join(args.emb_dir, f"{m}.npz")
        if not os.path.exists(p):
            print(f"skip {m}: {p} missing")
            continue
        z = np.load(p)
        for k in z.files:
            reps[k] = z[k].astype(np.float64)
    if "eg2_final_768" in reps:
        reps["eg2_final_mrl256"] = reps["eg2_final_768"][:, :256]
        reps["eg2_final_mrl128"] = reps["eg2_final_768"][:, :128]

    # ---- dev-set selections: centering and best layer ------------------------------------
    dev = {}
    centers = {}
    for k, X in reps.items():
        c = norm(X[tr.dev_idx]).mean(axis=0)
        raw_e, cen_e = dev_eer(X, tr, None), dev_eer(X, tr, c)
        dev[k] = {"dev_eer_raw": raw_e, "dev_eer_centered": cen_e, "use_center": bool(cen_e < raw_e)}
        centers[k] = c
    selected = {k: k for k in reps if "_layer" not in k}
    for prefix in sorted({k.split("_layer")[0] for k in reps if "_layer" in k}):
        layers = [k for k in reps if k.startswith(prefix + "_layer")]
        best = min(layers, key=lambda k: min(dev[k]["dev_eer_raw"], dev[k]["dev_eer_centered"]))
        selected[prefix + "_bestlayer"] = best

    results = {"dev": dev, "selected": selected, "reps": {}}
    for name, key in sorted(selected.items()):
        X = reps[key]
        out = {"source_key": key, "deployable": name in DEPLOYABLE, "dim": int(X.shape[1])}
        for use_c in (False, True):
            ctag = "centered" if use_c else "raw"
            for n in (3, 5):
                for mode in ("mean", "max"):
                    boot = args.bootstrap if (n == 5 and mode == "mean") else 0
                    out[f"{ctag}/enroll{n}/{mode}"] = evaluate_rep(X, tr, centers[key] if use_c else None, n, mode, boot)
        out["dev_choice"] = "centered" if dev[key]["use_center"] else "raw"
        results["reps"][name] = out
        a = out[f"{out['dev_choice']}/enroll5/mean"]["2.0"]["a_all"]
        print(f"{name:28s} [{key}] dev={out['dev_choice']:8s} 2s/enroll5 EER={a['eer']:.4f} TPR@1%={a['tpr_at_fpr1']:.3f}", flush=True)

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "results.json"), "w") as f:
        json.dump(results, f, indent=1)
    with open(os.path.join(args.out, "summary.md"), "w") as f:
        f.write(render_summary(results))
    print("wrote", args.out)


def pct(x: float) -> str:
    return f"{100 * x:.1f}"


def render_summary(results: dict) -> str:
    lines = ["# Audio speaker-filter benchmark summary", "",
             "Cells use the dev-chosen scoring (raw vs dev-mean-centred cosine), mean-template scoring.",
             "EER / TPR / FAR in %. (c) columns = acceptance of the user's noisy speech at the threshold that",
             "gives 5% false accepts on other speakers. Pooled over 2 enrollment draws x 40 speakers.", ""]
    hdr = ("| rep | dev | enr | len | EER a | CI95 | TPR@1 | TPR@5 | EER a same-g | EER b noise | EER b music "
           "| FAR noise@thr5 | FAR music@thr5 | c noise 0/5/10 | c babble 0/5/10 |")
    lines += [hdr, "|" + "---|" * (hdr.count("|") - 1)]
    for name, r in results["reps"].items():
        for n in (3, 5):
            for L in ("1.0", "2.0", "3.5"):
                c = r[f"{r['dev_choice']}/enroll{n}/mean"][L]
                a = c["a_all"]
                ci = a.get("eer_ci95")
                cis = f"{pct(ci[0])}-{pct(ci[1])}" if ci else ""
                cn = "/".join(pct(c[f"c_noise_{s}db"]["tpr_at_thr_fpr5"]) for s in (0, 5, 10))
                cb = "/".join(pct(c[f"c_babble_{s}db"]["tpr_at_thr_fpr5"]) for s in (0, 5, 10))
                bm = c.get("b_music", {"eer": float("nan"), "far_at_thr_fpr5": float("nan")})
                lines.append(
                    f"| {name} | {r['dev_choice'][:3]} | {n} | {L} | {pct(a['eer'])} | {cis} | {pct(a['tpr_at_fpr1'])} | "
                    f"{pct(a['tpr_at_fpr5'])} | {pct(c['a_same_gender']['eer'])} | {pct(c['b_noise']['eer'])} | "
                    f"{pct(bm['eer'])} | {pct(c['b_noise']['far_at_thr_fpr5'])} | {pct(bm['far_at_thr_fpr5'])} | {cn} | {cb} |")
    lines += ["", "## Mean vs max enrollment scoring (EER a, %, 5 phrases)", "",
              "| rep | 1.0 s mean | 1.0 s max | 2.0 s mean | 2.0 s max | 3.5 s mean | 3.5 s max |", "|---|---|---|---|---|---|---|"]
    for name, r in results["reps"].items():
        cells = []
        for L in ("1.0", "2.0", "3.5"):
            for mode in ("mean", "max"):
                cells.append(pct(r[f"{r['dev_choice']}/enroll5/{mode}"][L]["a_all"]["eer"]))
        lines.append(f"| {name} | " + " | ".join(cells) + " |")
    lines += ["", "## Dev-set selections", "", "| rep | source key | dev EER raw | dev EER centred |", "|---|---|---|---|"]
    for name, key in sorted(results["selected"].items()):
        d = results["dev"][key]
        lines.append(f"| {name} | {key} | {pct(d['dev_eer_raw'])} | {pct(d['dev_eer_centered'])} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
