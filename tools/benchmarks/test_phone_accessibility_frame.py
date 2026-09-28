import unittest

from phone_accessibility_frame import phone_frame


class PhoneAccessibilityFrameTest(unittest.TestCase):
    GOAL = (
        "Open YouTube, search for Linus Tech Tips, open a result from "
        "Linus Tech Tips, start playback, and verify it."
    )

    def test_exact_suggestion_keeps_binding_without_exposing_coordinate_or_full_goal(self):
        record = {
            "goal": self.GOAL,
            "package": "com.google.android.youtube",
            "candidates": [
                {"label": "A", "key": "click_node", "description": 'Tap "Navigate up" (node 1)'},
                {"label": "B", "key": "click_node", "description": 'Tap "Edit suggestion linus tech tips" (node 9)'},
                {"label": "C", "key": "click_node", "description": 'Tap "linus tech tips" (node 8)'},
                {"label": "D", "key": "detailed_planner", "description": "Ask planner"},
            ],
        }
        frame = phone_frame(record)
        self.assertEqual("suggestion", frame["phase"])
        self.assertEqual(["choose_exact_suggestion", "edit_search_suggestion"],
                         [tool["name"] for tool in frame["tools"]])
        self.assertEqual("C", frame["tools"][0]["label"])
        self.assertTrue(all("node" not in tool["description"] for tool in frame["tools"]))
        self.assertNotIn("start playback", frame["request"])

    def test_unobserved_or_unrelated_side_effect_never_becomes_a_tool(self):
        for goal in ("Send this email from Gmail", self.GOAL):
            record = {"goal": goal, "package": "com.google.android.gm",
                      "candidates": [{"label": "A", "key": "click_node",
                                      "description": 'Tap "Send" (node 4)'}]}
            frame = phone_frame(record)
            self.assertEqual([], frame["tools"])


if __name__ == "__main__":
    unittest.main()
