import unittest

from collect_artemis_settings import choose, clickable_controls, merge_verified_rows, page_title, preflight


class ArtemisSettingsCollectionTest(unittest.TestCase):
    def test_parent_child_binding_and_duplicate_targets(self):
        xml = ('<hierarchy><node resource-id="com.android.settings:id/collapsing_toolbar" content-desc="Display"/>'
               '<node clickable="true" enabled="true" bounds="[0,0][200,100]" class="android.widget.LinearLayout">'
               '<node text="Dark theme"/><node text="Off"/></node>'
               '<node clickable="true" enabled="true" bounds="[0,100][200,200]" class="android.widget.Switch" text="Dark theme"/>'
               '</hierarchy>')
        controls = clickable_controls(xml)
        self.assertEqual([c["label"] for c in controls], ["Dark theme", "Dark theme"])
        self.assertIsNone(choose(controls, "Dark theme"))
        self.assertEqual(page_title(xml), "Display")

    def test_rejects_authenticated_or_wrong_avd_before_any_action(self):
        def fake(serial, *args):
            if args == ("emu", "avd", "name"):
                return "Pixel_9a\nOK"
            self.fail(f"Unexpected action {serial} {args}")

        with self.assertRaisesRegex(ValueError, "Refusing Pixel_9a"):
            preflight("emulator-5554", adb_call=fake)

        def paid(_serial, *args):
            if args == ("emu", "avd", "name"):
                return "CyanBridge_Artemis_Data\nOK"
            if args == ("shell", "getprop", "sys.boot_completed"):
                return "1"
            if args == ("shell", "pm", "list", "packages"):
                return "package:net.dinglisch.android.taskerm"
            self.fail(f"Unexpected action {args}")

        with self.assertRaisesRegex(ValueError, "Tasker/AutoInput"):
            preflight("emulator-5560", adb_call=paid)

    def test_failed_retry_does_not_erase_verified_prior_episode(self):
        prior = [{"episodeId": "previous", "step": 0,
                  "observation": {"screenSignature": "from-device"}, "outcomeVerified": True}]
        self.assertEqual(merge_verified_rows(prior, []), prior)


if __name__ == "__main__":
    unittest.main()
