import json
import unittest

from bounded_embedding import DOCUMENT_PREFIX, QUERY_PREFIX, menu_texts

TURN = {
    "query": json.dumps({"status": {"wakeRoute": "voice"}, "request": "Use image questions"}),
    "options": {"option_0": {"key": "route:image", "label": "Set wake route to image"},
                "option_1": {"key": "no_change", "label": "No change"}},
}


class BoundedEmbeddingTest(unittest.TestCase):
    def test_full_query_keeps_host_status_and_option_order(self):
        query, documents = menu_texts(TURN)
        self.assertEqual(query, QUERY_PREFIX + TURN["query"])
        self.assertEqual(documents, [DOCUMENT_PREFIX + "Set wake route to image", DOCUMENT_PREFIX + "No change"])

    def test_request_only_drops_status(self):
        query, _ = menu_texts(TURN, request_only=True)
        self.assertEqual(query, QUERY_PREFIX + "Use image questions")
        self.assertNotIn("wakeRoute", query)

    def test_presented_options_override_turn_order(self):
        reversed_options = dict(reversed(list(TURN["options"].items())))
        _, documents = menu_texts(TURN, reversed_options)
        self.assertEqual(documents[0], DOCUMENT_PREFIX + "No change")


if __name__ == "__main__":
    unittest.main()
