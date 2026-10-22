import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def case(case_id, expected):
    options = {"option_0": {"key": "route:image", "label": "Image"}, "option_1": {"key": "no_change", "label": "No change"}}
    return {"caseId": case_id, "episodeId": case_id, "stage": "flat_state_matched", "expectedChoice": expected,
            "turn": {"query": "{}", "options": options}}


def device(case_id, selected, margin, option_ids=("option_0", "option_1")):
    return {"kind": "case", "caseId": case_id, "optionIds": list(option_ids), "selected": selected,
            "probabilities": [0.5, 0.5], "margin": margin, "raw": "", "decisionMs": 10}


class ScoreLiteRtBoundedEvalTest(unittest.TestCase):
    def run_score(self, cases, rows):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "c.jsonl").write_text("".join(json.dumps(c) + "\n" for c in cases))
            (tmp / "d.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
            done = subprocess.run([sys.executable, str(HERE / "score_litert_bounded_eval.py"), "--canonical", str(tmp / "c.jsonl"),
                                   "--device-results", str(tmp / "d.jsonl"), "--report", str(tmp / "r.json")],
                                  cwd=HERE, capture_output=True, text=True)
            return done, json.loads((tmp / "r.json").read_text()) if done.returncode == 0 else None

    def test_scores_control_false_actions_and_margin_coverage(self):
        rows = [{"kind": "header"}, device("a", "option_0", 0.9), device("b", "option_0", 0.05), {"kind": "footer", "cases": 2}]
        done, report = self.run_score([case("a", "option_0"), case("b", "option_1")], rows)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertTrue(report["complete"])
        self.assertEqual(report["oracleTurns"]["correct"], 1)
        self.assertEqual(report["oracleTurns"]["controlFalseActionRate"], 1.0)
        gated = next(c for c in report["marginCoverage"] if c["minMargin"] == 0.1)
        self.assertEqual((gated["accepted"], gated["acceptedPrecision"]), (1, 1.0))

    def test_rejects_device_option_order_mismatch(self):
        rows = [{"kind": "header"}, device("a", "option_0", 0.9, ("option_1", "option_0"))]
        done, _ = self.run_score([case("a", "option_0")], rows)
        self.assertNotEqual(done.returncode, 0)


if __name__ == "__main__":
    unittest.main()
