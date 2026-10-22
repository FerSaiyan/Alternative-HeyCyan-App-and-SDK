#!/usr/bin/env python3
"""Final publication rejects mixed, incomplete, or unverified campaign evidence."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from bounded_specialist_contract import choice_metrics
from summarize_bounded_campaign import summarize


class CampaignSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        (self.root/"reports").mkdir()
        self.model=self.root/"model.cact"
        self.model.write_bytes(b"fixture model")
        laya=self.root/"laya"
        laya.mkdir()
        (laya/"model.safetensors").write_bytes(self.model.read_bytes())
        for view in ("hierarchy","flat"):
            directory=self.root/f"bounded-{view}-v3"
            directory.mkdir()
            manifest={"contract":"bounded-specialist-v3","counts":{"development":1},
                      "episodes":{"development":1},"maxOptions":3,
                      "files":{f"{split}.episodes.jsonl":{"rows":1,"sha256":split}
                               for split in ("train","development","calibration","sealed")}}
            (directory/"manifest.json").write_text(json.dumps(manifest))
            for backend in ("laya","needle"):
                for label in ("base","tuned"):
                    row={"stage":"voice_route","selected":"option_0","expectedChoice":"option_0",
                         "expectedKey":"clarify","selectedKey":"clarify"}
                    report={"backend":backend,"flat":view=="flat","contract":"bounded-specialist-v3",
                            "model":str(laya if backend=="laya" else self.model),
                            "modelSha256":hashlib.sha256(self.model.read_bytes()).hexdigest(),
                            "oracleTurns":dict(choice_metrics([row]),results=[row]),
                            "rollouts":[{"correct":True,"proposedEffect":{"kind":"clarify"},
                                         "expectedEffect":{"kind":"clarify"},"trace":[{}]}],
                            "rolloutEpisodes":1,"rolloutCorrect":1,"rolloutAccuracy":1.0,
                            "wallSeconds":1,"calibrationOpened":False,"finalTestOpened":False,
                            "hostVerifiedMutation":False}
                    self.write(self.path(backend,view,label),report)

    def path(self,backend="needle",view="flat",label="tuned"):
        return self.root/"reports"/f"{backend}-bounded-{view}-v3-{label}-rollout.json"

    @staticmethod
    def write(path,report):
        path.write_text(json.dumps(report))

    def alter(self,**changes):
        path=self.path()
        report=json.loads(path.read_text())
        report.update(changes)
        self.write(path,report)

    def test_complete_summary_verifies_model_and_report_bytes(self):
        result=summarize(self.root,"v3")
        self.assertTrue(result["complete"])
        self.assertTrue(result["artifactHashesVerified"])
        self.assertEqual(len(result["results"]),8)
        entry=next(r for r in result["results"] if r["name"]==self.path().stem)
        self.assertEqual(entry["reportSha256"],hashlib.sha256(self.path().read_bytes()).hexdigest())

    def test_partial_requires_explicit_opt_in(self):
        self.path().unlink()
        with self.assertRaisesRegex(ValueError,"incomplete"):
            summarize(self.root,"v3")
        self.assertFalse(summarize(self.root,"v3",allow_partial=True)["complete"])

    def test_deliberate_single_model_ablation_requires_its_four_reports(self):
        reports=self.root/"needle-only"
        reports.mkdir()
        for path in (self.root/"reports").glob("needle-*"):
            (reports/path.name).write_bytes(path.read_bytes())
        result=summarize(self.root,"v3",reports=reports,backends=("needle",))
        self.assertTrue(result["complete"])
        self.assertEqual(set(result["budgets"]),{"needle"})
        self.assertEqual(len(result["results"]),4)
        (reports/self.path().name).unlink()
        with self.assertRaisesRegex(ValueError,"incomplete"):
            summarize(self.root,"v3",reports=reports,backends=("needle",))

    def test_eight_reports_with_wrong_variant_cannot_count_as_complete(self):
        self.path().rename(self.path(label="other"))
        with self.assertRaisesRegex(ValueError,"Unexpected"):
            summarize(self.root,"v3")

    def test_missing_or_modified_model_cannot_pass_hash_verification(self):
        self.model.unlink()
        with self.assertRaisesRegex(ValueError,"missing model"):
            summarize(self.root,"v3")
        self.model.write_bytes(b"different model")
        with self.assertRaisesRegex(ValueError,"hash mismatch"):
            summarize(self.root,"v3")

    def test_metadata_cannot_relabel_or_open_another_cohort(self):
        self.alter(flat=False)
        with self.assertRaisesRegex(ValueError,"mismatch"):
            summarize(self.root,"v3")
        self.alter(flat=True,finalTestOpened=True)
        with self.assertRaisesRegex(ValueError,"evaluation scope"):
            summarize(self.root,"v3")

    def test_v3_cannot_borrow_legacy_missing_metadata_exception(self):
        report=json.loads(self.path().read_text())
        report.pop("modelSha256")
        self.write(self.path(),report)
        with self.assertRaisesRegex(ValueError,"hash mismatch"):
            summarize(self.root,"v3")

    def test_reversed_report_cannot_enter_original_order_summary(self):
        self.alter(reversedOptions=True)
        with self.assertRaisesRegex(ValueError,"mismatch"):
            summarize(self.root,"v3")

    def test_oracle_metrics_recomputed_from_saved_predictions(self):
        report=json.loads(self.path().read_text())
        report["oracleTurns"]["results"][0]["selected"]=None
        self.write(self.path(),report)
        with self.assertRaisesRegex(ValueError,"Oracle metrics"):
            summarize(self.root,"v3")

    def test_episode_success_requires_matching_proposed_effect(self):
        report=json.loads(self.path().read_text())
        report["rollouts"][0]["proposedEffect"]={"kind":"set_local_model"}
        self.write(self.path(),report)
        with self.assertRaisesRegex(ValueError,"proposed effect"):
            summarize(self.root,"v3")


if __name__=="__main__":
    unittest.main()
