import unittest

from audit_artemis_phone_episodes import audit


def frame(hash_, title):
    return {"provider": "ARTEMIS_UIAUTOMATOR2", "packageName": "com.android.settings",
            "hierarchySha256": hash_, "screenSignature": hash_, "pageTitle": title,
            "candidates": [{"id": f"n{i}"} for i in range(3)]}


def episode(name, root_hash="root"):
    root, sub, final = frame(root_hash, ""), frame(name + "sub", "Apps"), frame(name + "final", "Default apps")
    base = {"episodeId": name, "outcomeVerified": True}
    return [
        {**base, "step": 0, "observation": root, "postObservation": sub,
         "expected": {"operation": "TAP", "candidateId": "n1"}},
        {**base, "step": 1, "observation": sub, "postObservation": final,
         "expected": {"operation": "TAP", "candidateId": "n2"}},
        {**base, "step": 2, "observation": final, "postObservation": final,
         "expected": {"operation": "DONE"}, "verification": {"observedPageTitle": "Default apps"}},
    ]


class RealPhoneAuditTest(unittest.TestCase):
    def test_repeated_home_screen_cannot_be_split_by_episode(self):
        report = audit(episode("a") + episode("b"))
        self.assertEqual(report["componentCount"], 1)
        self.assertFalse(report["readyForIndependentSplit"])
        self.assertEqual(report["operations"], {"TAP": 4, "DONE": 2})

    def test_unverified_action_and_missing_gold_are_rejected(self):
        rows = episode("a")
        rows[0]["outcomeVerified"] = False
        with self.assertRaisesRegex(ValueError, "Unverified"):
            audit(rows)
        rows = episode("a")
        rows[0]["expected"]["candidateId"] = "n99"
        with self.assertRaisesRegex(ValueError, "not offered"):
            audit(rows)

    def test_exact_literal_requires_focused_field_and_postcondition(self):
        start, result = frame("search-start", "Search YouTube"), frame("search-result", "Search YouTube")
        start["focusedFieldCandidateId"] = "n1"
        result["focusedFieldText"] = "moon mission"
        rows = [{"episodeId": "query", "step": 0, "goal": "Enter moon mission", "outcomeVerified": True,
                 "observation": start, "postObservation": result,
                 "expected": {"operation": "TYPE_LITERAL", "candidateId": "n1", "literal": "moon mission"}},
                {"episodeId": "query", "step": 1, "goal": "Enter moon mission", "outcomeVerified": True,
                 "observation": result, "postObservation": result, "expected": {"operation": "DONE"},
                 "verification": {"observedFieldValue": "moon mission"}}]
        self.assertEqual(audit(rows)["operations"], {"TYPE_LITERAL": 1, "DONE": 1})
        rows[0]["postObservation"] = frame("wrong", "Search YouTube")
        with self.assertRaisesRegex(ValueError, "exact user-literal"):
            audit(rows)


if __name__ == "__main__":
    unittest.main()
