"""Decision-gate regressions; runs without model weights or Laya dependencies."""

import unittest

from benchmark_laya_mobile_actions import evaluate, fit


class LayaGateTest(unittest.TestCase):
    def test_confident_wrong_action_cannot_pass_precision_gate(self):
        rows = [
            dict(id="wrong", expected="tap_search", selected="tap_home",
                 topScore=0.9999, margin=0.9998, policyBoundary=False),
            dict(id="right", expected="type_query", selected="type_query",
                 topScore=0.7, margin=0.4, policyBoundary=False),
        ]
        top, margin = fit(rows, 0.98)
        self.assertEqual(0, evaluate(rows, top, margin)["autoActions"])

    def test_policy_block_is_never_inferred_or_counted(self):
        rows = [
            dict(id="blocked", expected="policy_block", policyBoundary=True),
            dict(id="right", expected="type_query", selected="type_query",
                 topScore=0.9, margin=0.8, policyBoundary=False),
        ]
        top, margin = fit(rows, 0.98)
        result = evaluate(rows, top, margin)
        self.assertEqual(1, result["cases"])
        self.assertEqual(1, result["autoActions"])
        self.assertEqual(1.0, result["autoActionPrecision"])


if __name__ == "__main__":
    unittest.main()
