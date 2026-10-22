#!/usr/bin/env python3
"""Pure NumPy speaker-verification metrics used by the audio speaker-filter benchmark.

Everything here is deterministic and free of torch/model dependencies so it can be unit
tested on any host (see ``audio_speaker_test_metrics.py``).

Conventions
-----------
* Scores are similarities: higher means "more likely the enrolled user".
* ``target`` scores are genuine trials (the enrolled user speaking).
* ``nontarget`` scores are rejections we want (other speakers, noise, ...).
* The decision rule is ``accept if score >= threshold``.
"""

from __future__ import annotations

import math
from typing import Iterable, Sequence

import numpy as np


def l2_normalize(x: np.ndarray, axis: int = -1, eps: float = 1e-12) -> np.ndarray:
    x = np.asarray(x, dtype=np.float64)
    norm = np.linalg.norm(x, axis=axis, keepdims=True)
    return x / np.maximum(norm, eps)


def enrollment_template(enroll: np.ndarray, center: np.ndarray | None = None) -> np.ndarray:
    """Mean of L2-normalised enrollment embeddings, re-normalised (the stored template).

    ``enroll`` has shape (n_enroll, dim). ``center`` (dim,) is subtracted before
    normalisation when given (simple mean-centering with a background mean).
    """
    e = np.asarray(enroll, dtype=np.float64)
    if center is not None:
        e = e - center
    return l2_normalize(l2_normalize(e).mean(axis=0))


def score_mean(test: np.ndarray, enroll: np.ndarray, center: np.ndarray | None = None) -> np.ndarray:
    """Cosine between each test embedding (n, dim) and the mean enrollment template."""
    t = np.asarray(test, dtype=np.float64)
    if center is not None:
        t = t - center
    return l2_normalize(t) @ enrollment_template(enroll, center)


def score_max(test: np.ndarray, enroll: np.ndarray, center: np.ndarray | None = None) -> np.ndarray:
    """Max cosine over individual enrollment embeddings for each test embedding."""
    t = np.asarray(test, dtype=np.float64)
    e = np.asarray(enroll, dtype=np.float64)
    if center is not None:
        t = t - center
        e = e - center
    return (l2_normalize(t) @ l2_normalize(e).T).max(axis=1)


def _as_1d(x: Iterable[float]) -> np.ndarray:
    a = np.asarray(list(x) if not isinstance(x, np.ndarray) else x, dtype=np.float64).ravel()
    if a.size == 0:
        raise ValueError("empty score array")
    if not np.all(np.isfinite(a)):
        raise ValueError("non-finite score")
    return a


def roc_points(target: Sequence[float], nontarget: Sequence[float]):
    """Return (thresholds, fpr, fnr) over every distinct score, rule ``score >= thr``.

    The first point is threshold=+inf (accept nothing: fpr=0, fnr=1).
    """
    tgt = _as_1d(target)
    non = _as_1d(nontarget)
    scores = np.concatenate([tgt, non])
    labels = np.concatenate([np.ones_like(tgt), np.zeros_like(non)])
    order = np.argsort(-scores, kind="mergesort")
    scores = scores[order]
    labels = labels[order]
    tp = np.cumsum(labels)
    fp = np.cumsum(1.0 - labels)
    # keep only the last index of each run of equal scores (ties accepted together)
    distinct = np.r_[np.diff(scores) != 0, True]
    thr = np.r_[np.inf, scores[distinct]]
    tp = np.r_[0.0, tp[distinct]]
    fp = np.r_[0.0, fp[distinct]]
    fpr = fp / non.size
    fnr = 1.0 - tp / tgt.size
    return thr, fpr, fnr


def eer(target: Sequence[float], nontarget: Sequence[float]) -> float:
    """Equal error rate via linear interpolation of the (fpr, fnr) curve where they cross."""
    _, fpr, fnr = roc_points(target, nontarget)
    diff = fnr - fpr  # starts at +1, ends <= 0
    idx = int(np.argmax(diff <= 0))
    if idx == 0:
        return 0.0
    d0, d1 = diff[idx - 1], diff[idx]
    w = d0 / (d0 - d1) if d0 != d1 else 0.0
    f0 = fpr[idx - 1] + w * (fpr[idx] - fpr[idx - 1])
    n0 = fnr[idx - 1] + w * (fnr[idx] - fnr[idx - 1])
    return float((f0 + n0) / 2.0)


def threshold_at_fpr(nontarget: Sequence[float], max_fpr: float) -> float:
    """Smallest threshold whose false-accept rate on ``nontarget`` is <= ``max_fpr``.

    With rule ``score >= thr``, choose thr just above the k-th largest non-target score
    where k = floor(max_fpr * n).
    """
    non = np.sort(_as_1d(nontarget))[::-1]
    k = int(math.floor(max_fpr * non.size + 1e-9))
    if k >= non.size:
        return float(-np.inf)
    return float(np.nextafter(non[k], np.inf))


def tpr_at_threshold(target: Sequence[float], thr: float) -> float:
    tgt = _as_1d(target)
    return float(np.mean(tgt >= thr))


def fpr_at_threshold(nontarget: Sequence[float], thr: float) -> float:
    non = _as_1d(nontarget)
    return float(np.mean(non >= thr))


def tpr_at_fpr(target: Sequence[float], nontarget: Sequence[float], max_fpr: float) -> float:
    return tpr_at_threshold(target, threshold_at_fpr(nontarget, max_fpr))


def summarize(target: Sequence[float], nontarget: Sequence[float]) -> dict:
    return {
        "eer": eer(target, nontarget),
        "tpr_at_fpr1": tpr_at_fpr(target, nontarget, 0.01),
        "tpr_at_fpr5": tpr_at_fpr(target, nontarget, 0.05),
        "n_target": int(np.size(target)),
        "n_nontarget": int(np.size(nontarget)),
    }


__all__ = [
    "l2_normalize",
    "enrollment_template",
    "score_mean",
    "score_max",
    "roc_points",
    "eer",
    "threshold_at_fpr",
    "tpr_at_threshold",
    "fpr_at_threshold",
    "tpr_at_fpr",
    "summarize",
]
