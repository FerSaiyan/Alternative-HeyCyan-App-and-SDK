import copy
import unittest

from audit_tasker_settings_episode import audit


def row():
    def frame(when, sha, labels):
        return {"provider": "TASKER_AUTOINPUT", "packageName": "com.android.settings",
                "createdAtMs": when, "snapshotSha256": sha * 64,
                "allowlistedNodes": [{"nodeIndex": i, "label": label} for i, label in enumerate(labels)]}
    return {"source": "TASKER_AUTOINPUT", "device": "authenticated_Pixel_9a",
            "episodeId": "tasker_settings_network_1234",
            "operation": "TAP", "outcomeVerified": True, "targetLabel": "Network & internet",
            "targetNodeIndex": 1, "candidateCount": 7, "observationToVerificationMs": 3087,
            "actionToVerificationMs": 1700,
            "observedDestinationMarkers": ["Internet", "SIMs"],
            "pre": frame(1234, "a", ["Search Settings", "Network & internet", "Apps"]),
            "post": frame(4321, "b", ["Internet", "SIMs", "VPN"])}


class TaskerSettingsAuditTest(unittest.TestCase):
    def test_distinct_observed_screens_are_accepted_as_diagnostic(self):
        self.assertTrue(audit(row())["outcomeVerifiedByTasker"])
        self.assertFalse(audit(row())["readyForTraining"])

    def test_already_at_destination_is_not_a_successful_action(self):
        stale = row()
        stale["pre"]["allowlistedNodes"].extend([{"nodeIndex": 3, "label": "Internet"},
                                                    {"nodeIndex": 4, "label": "SIMs"}])
        with self.assertRaisesRegex(ValueError, "already the destination"):
            audit(stale)

    def test_raw_or_unverified_observation_is_rejected(self):
        unsafe = row()
        unsafe["pre"]["screenText"] = "account data"
        with self.assertRaisesRegex(ValueError, "raw observation"):
            audit(unsafe)
        bad = copy.deepcopy(row())
        bad["post"]["snapshotSha256"] = bad["pre"]["snapshotSha256"]
        with self.assertRaisesRegex(ValueError, "unchanged"):
            audit(bad)


if __name__ == "__main__":
    unittest.main()
