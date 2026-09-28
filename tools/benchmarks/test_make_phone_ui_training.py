import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path

from make_phone_ui_training import generate


class SyntheticPhoneTrainingDataTest(unittest.TestCase):
    def test_split_isolation_negative_labels_and_model_agreement(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            first = generate(output)
            second = generate(output)
            self.assertEqual(first, second)
            all_episodes = set()
            all_queries = set()
            for split in ("train", "validation", "test"):
                laya = [json.loads(s) for s in (output / f"{split}.laya.jsonl").read_text().splitlines()]
                needle = [json.loads(s) for s in (output / f"{split}.needle.jsonl").read_text().splitlines()]
                self.assertEqual(len(laya), len(needle))
                episodes = {x["episodeId"] for x in needle}
                self.assertTrue(all_episodes.isdisjoint(episodes))
                all_episodes.update(episodes)
                self.assertFalse(all_queries.intersection(x["query"] for x in needle))
                all_queries.update(x["query"] for x in needle)
                choices = Counter()
                for a, b in zip(laya, needle):
                    self.assertEqual(a["caseId"], b["caseId"])
                    self.assertEqual(a["state"]["current_step"], b["query"])
                    answer = a["expected"]["next_action"]
                    criteria = a["questions"]["next_action"]["criteria"]
                    self.assertIn(answer, criteria)
                    self.assertEqual(answer == "defer", b["answers"] == [])
                    self.assertNotIn("Send", " ".join(criteria.values()))
                    self.assertNotIn("Linus Tech Tips", b["query"])
                    self.assertNotIn("gmail", b["query"].casefold())
                    self.assertLessEqual(len(b["tools"]), 3)
                    choices[answer] += 1
                self.assertGreater(choices["defer"], 0)
                self.assertGreater(len([answer for answer in choices if answer != "defer"]), 1)
                for stage in ("launch", "suggestion", "results", "video"):
                    names = {row["answers"][0]["name"] for row in needle if row["stage"] == stage}
                    self.assertGreater(len(names), 1, f"{stage} must not leak gold in the tool ID")


if __name__ == "__main__":
    unittest.main()
