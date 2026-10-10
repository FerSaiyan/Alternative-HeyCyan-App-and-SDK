#!/usr/bin/env python3
"""Unit tests for audio_speaker_metrics (pure NumPy).

Run: python3 tools/benchmarks/audio_speaker_test_metrics.py
"""

from __future__ import annotations

import os
import sys
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import audio_speaker_metrics as m  # noqa: E402


class EerTest(unittest.TestCase):
    def test_perfect_separation_is_zero(self):
        self.assertEqual(m.eer([0.9, 0.8, 0.7], [0.1, 0.2, 0.3]), 0.0)

    def test_fully_inverted_is_one(self):
        self.assertAlmostEqual(m.eer([0.1, 0.2], [0.8, 0.9]), 1.0)

    def test_identical_distributions_near_half(self):
        rng = np.random.default_rng(0)
        a = rng.normal(size=20000)
        b = rng.normal(size=20000)
        self.assertAlmostEqual(m.eer(a, b), 0.5, delta=0.01)

    def test_gaussian_matches_theory(self):
        # target ~ N(d, 1), nontarget ~ N(0, 1): EER = Phi(-d/2)
        from math import erf, sqrt

        d = 2.0
        rng = np.random.default_rng(1)
        a = rng.normal(d, 1.0, 200000)
        b = rng.normal(0.0, 1.0, 200000)
        theory = 0.5 * (1 + erf((-d / 2) / sqrt(2)))
        self.assertAlmostEqual(m.eer(a, b), theory, delta=0.005)

    def test_small_hand_example(self):
        # targets 0.9 0.6 0.4 ; nontargets 0.5 0.3 0.1
        # thr=0.6 -> fnr 1/3, fpr 0 ; thr=0.5 -> fnr 1/3 fpr 1/3 => EER 1/3
        self.assertAlmostEqual(m.eer([0.9, 0.6, 0.4], [0.5, 0.3, 0.1]), 1 / 3, places=6)

    def test_rejects_nan_and_empty(self):
        with self.assertRaises(ValueError):
            m.eer([0.1, float("nan")], [0.2])
        with self.assertRaises(ValueError):
            m.eer([], [0.2])


class ThresholdTest(unittest.TestCase):
    def test_threshold_at_fpr_respects_budget(self):
        non = np.arange(100, dtype=float)  # 0..99
        thr = m.threshold_at_fpr(non, 0.05)
        self.assertLessEqual(m.fpr_at_threshold(non, thr), 0.05)
        self.assertGreaterEqual(m.fpr_at_threshold(non, thr), 0.04)

    def test_threshold_zero_fpr(self):
        non = [0.1, 0.5, 0.3]
        thr = m.threshold_at_fpr(non, 0.0)
        self.assertEqual(m.fpr_at_threshold(non, thr), 0.0)
        self.assertGreater(thr, 0.5)

    def test_tpr_at_fpr(self):
        tgt = [0.95, 0.9, 0.85, 0.2]
        non = list(np.linspace(0.0, 0.8, 100))
        self.assertAlmostEqual(m.tpr_at_fpr(tgt, non, 0.01), 0.75)

    def test_summarize_keys(self):
        s = m.summarize([0.9, 0.8], [0.1, 0.2])
        self.assertEqual(set(s), {"eer", "tpr_at_fpr1", "tpr_at_fpr5", "n_target", "n_nontarget"})
        self.assertEqual(s["n_target"], 2)


class ScoringTest(unittest.TestCase):
    def test_template_is_unit_norm(self):
        rng = np.random.default_rng(2)
        e = rng.normal(size=(5, 16))
        t = m.enrollment_template(e)
        self.assertAlmostEqual(float(np.linalg.norm(t)), 1.0, places=9)

    def test_score_mean_and_max_bounds(self):
        rng = np.random.default_rng(3)
        enroll = rng.normal(size=(3, 8))
        test = np.vstack([enroll[0], -enroll[0]])
        smax = m.score_max(test, enroll)
        self.assertAlmostEqual(smax[0], 1.0, places=9)
        self.assertLess(smax[1], 1.0)
        smean = m.score_mean(test, enroll)
        self.assertTrue(np.all(np.abs(smean) <= 1.0 + 1e-9))
        self.assertGreater(smean[0], smean[1])

    def test_centering_changes_scores(self):
        enroll = np.array([[10.0, 1.0], [10.0, 1.1]])
        test = np.array([[10.0, -1.0]])
        raw = m.score_mean(test, enroll)[0]
        centered = m.score_mean(test, enroll, center=np.array([10.0, 0.0]))[0]
        self.assertGreater(raw, 0.9)
        self.assertLess(centered, -0.9)


if __name__ == "__main__":
    unittest.main(verbosity=2)
