import unittest

from collect_artemis_clock import current_tab


class ClockTabOracleTest(unittest.TestCase):
    def test_requires_one_selected_tab_not_just_a_label(self):
        xml = ('<hierarchy>'
               '<node text="Stopwatch" resource-id="com.google.android.deskclock:id/action_bar_title"/>'
               '<node resource-id="com.google.android.deskclock:id/tab_menu_alarm" selected="false"/>'
               '<node resource-id="com.google.android.deskclock:id/tab_menu_stopwatch" selected="true"/>'
               '</hierarchy>')
        self.assertEqual(current_tab(xml), "Stopwatch")
        self.assertIsNone(current_tab(xml.replace('selected="true"', 'selected="false"')))
        self.assertIsNone(current_tab(xml.replace('tab_menu_alarm" selected="false"',
                                                   'tab_menu_alarm" selected="true"')))


if __name__ == "__main__":
    unittest.main()
