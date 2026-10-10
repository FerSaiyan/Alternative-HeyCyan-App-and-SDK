#!/usr/bin/env python3
"""Contract tests for partition exclusion, grouped augmentation and raw metrics."""
import json
import tempfile
import unittest
from pathlib import Path

from make_specialist_training import bind_config, build_config, build_public, equivalent_names, paired, source_routes, write_dataset
from specialist_metrics import exact_calls, needle_proposals, rebind_calls, summarize


class SpecialistContractTest(unittest.TestCase):
    def test_official_eval_and_unknown_calls_excluded_before_conversion(self):
        tool = {"name": "open_settings", "description": "Open phone settings"}
        def mobile(partition, query):
            return {"metadata": partition, "tools": [{"function": tool}], "messages": [{"role": "user", "content": query}, {"role": "assistant", "tool_calls": [{"function": {"name": "open_settings", "arguments": {}}}]}]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            root.joinpath("mobile_actions.jsonl").write_text("\n".join(json.dumps(r) for r in (mobile("train", "Open settings"), mobile("eval", "Evaluation must not fit"))))
            root.joinpath("droidcall_train.jsonl").write_text(json.dumps({"query": "Open settings", "tools": [tool], "answers": [{"name": "undeclared", "arguments": {}}]}))
            rows, descriptions, exclusions = source_routes(root)
            self.assertEqual([r["query"] for r in rows], ["Open settings"])
            self.assertEqual(exclusions["official_mobile_eval"], 1)
            self.assertEqual(exclusions["droidcall_undeclared_answer"], 1)

    def test_delexicalized_goals_from_both_sources_share_group(self):
        tool = {"name": "show_map", "description": "Show a map"}
        def row(place):
            return {"query": f"Show a map of {place}", "tools": [tool], "answers": [{"name": "show_map", "arguments": {"query": place}}]}
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            root.joinpath("mobile_actions.jsonl").write_text("")
            root.joinpath("droidcall_train.jsonl").write_text("\n".join(json.dumps(row(p)) for p in ("Paris", "Madrid")))
            rows, _, _ = source_routes(root)
            self.assertEqual(rows[0]["groupId"], rows[1]["groupId"])
            self.assertEqual(rows[0]["split"], rows[1]["split"])

    def test_counterfactual_stays_in_original_group_and_removes_gold(self):
        original = {"source": "fixture", "sourceRow": 0, "query": "Open settings", "operation": "settings", "groupId": "g1", "split": "train"}
        rows = build_public([original], {"settings": "Settings", "a": "A", "b": "B", "c": "C"}, 1)
        self.assertEqual(len(rows), 3)
        self.assertEqual({r["groupId"] for r in rows}, {"g1"})
        missing = next(r for r in rows if r["stage"] == "unavailable")
        self.assertNotIn("settings", {t["name"] for t in missing["tools"]})
        self.assertEqual(missing["answers"], [])

    def test_config_values_valid_and_enable_start_separate(self):
        self.assertEqual(bind_config("Set Visual Diary to every 20 minutes", "set_diary_interval"), {"minutes": 20})
        self.assertIsNone(bind_config("Set Visual Diary to every 500 minutes", "set_diary_interval"))
        rows = build_config()
        positive = [r for r in rows if r["answers"]]
        self.assertIn("enable_plugin", {r["answers"][0]["name"] for r in positive})
        self.assertIn("start_plugin", {r["answers"][0]["name"] for r in positive})
        for row in positive:
            call = row["answers"][0]
            self.assertIn(call["name"], {t["name"] for t in row["tools"]})
            self.assertEqual(bind_config(row["query"], call["name"]), call["arguments"])
            laya, needle = paired(row)
            self.assertEqual(laya["expectedCalls"], needle["answers"])

    def test_equivalent_capability_not_used_as_unavailable_distractor(self):
        original = {"source": "fixture", "sourceRow": 0, "query": "Open Wi-Fi settings", "operation": "open_wifi_settings", "groupId": "g1", "split": "train"}
        rows = build_public([original], {"open_wifi_settings": "Wi-Fi", "open_settings": "Settings", "a": "A", "b": "B", "c": "C"}, 1)
        for row in rows:
            siblings = {t["name"] for t in row["tools"]} & equivalent_names(original["operation"])
            self.assertEqual(siblings, set() if row["stage"] == "unavailable" else {original["operation"]})

    def test_exact_arguments_preserve_types_spelling_and_call_order(self):
        call = [{"name": "set", "arguments": {"value": 1}}]
        self.assertFalse(exact_calls([{"name": "set", "arguments": {"value": True}}], call))
        self.assertFalse(exact_calls([{"name": "SET", "arguments": {"value": 1}}], call))
        other = [{"name": "next", "arguments": {}}]
        self.assertFalse(exact_calls(other + call, call + other))

    def test_opaque_binding_does_not_accept_undeclared_original_name(self):
        expected = [{"name": "open_settings", "arguments": {}}]
        binding = {"action_0_0": "open_settings"}
        self.assertTrue(exact_calls(rebind_calls([{"name": "action_0_0", "arguments": {}}], binding), expected))
        self.assertFalse(exact_calls(rebind_calls(expected, binding), expected))

    def test_native_suppression_is_not_a_correct_model_no_call(self):
        call = {"name": "change_setting", "arguments": {}}
        envelope = {"success": True, "function_calls": [], "suppressed_calls": [call]}
        self.assertFalse(exact_calls(needle_proposals(envelope), []))
        self.assertTrue(exact_calls(needle_proposals(envelope, include_suppressed=False), []))
        self.assertIsNone(needle_proposals({"success": False, "function_calls": []}))
        self.assertIsNone(needle_proposals([]))

    def test_writer_rejects_cross_cohort_group(self):
        rows = build_config()[:1]
        rows.append(dict(rows[0], split="development", caseId="leaked"))
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(AssertionError):
                write_dataset(Path(tmp), rows)

    def test_invalid_and_wrong_arguments_are_not_successful_no_calls(self):
        gold = [{"name": "interval", "arguments": {"minutes": 20}}]
        rows = [{"stage": "positive", "expected": gold, "calls": [{"name": "interval", "arguments": {"minutes": 21}}]}, {"stage": "negative", "expected": [], "calls": None}, {"stage": "negative", "expected": [], "calls": gold}]
        result = summarize(rows)
        self.assertEqual(result["correct"], 0)
        self.assertEqual(result["negativeFalseActionRate"], .5)
        self.assertEqual(result["negativeInvalidRate"], .5)


if __name__ == "__main__":
    unittest.main()
