import unittest

from collect_artemis_settings import clickable_controls
from collect_artemis_youtube_query import focused_field


class YouTubeInputOracleTest(unittest.TestCase):
    def test_focused_field_must_bind_to_observed_clickable_node(self):
        xml = ('<hierarchy><node class="android.widget.EditText" clickable="true" enabled="true" '
               'focused="true" text="moon mission" hint="Search YouTube" bounds="[10,20][500,80]"/>'
               '<node class="android.widget.Button" clickable="true" enabled="true" '
               'content-desc="Voice search" bounds="[500,20][600,80]"/></hierarchy>')
        field = focused_field(xml, clickable_controls(xml))
        self.assertEqual(field, {"candidateId": "n0", "text": "moon mission", "hint": "Search YouTube"})
        self.assertIsNone(focused_field(xml.replace('focused="true"', 'focused="false"'), clickable_controls(xml)))


if __name__ == "__main__":
    unittest.main()
