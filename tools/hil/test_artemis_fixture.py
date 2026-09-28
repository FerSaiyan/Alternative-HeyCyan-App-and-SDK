"""Model-free tests for the external tester's device boundary and UI oracle."""

import unittest
from unittest.mock import patch

import run_artemis_fixture as fixture


class ArtemisFixtureTest(unittest.TestCase):
    def test_rejects_paid_tasker_avd_before_install(self):
        def fake_adb(_serial, *args, **_kwargs):
            if args == ("get-state",):
                return "device\n"
            if args == ("shell", "getprop", "sys.boot_completed"):
                return "1\n"
            if args == ("shell", "pm", "list", "packages"):
                return "package:com.fersaiyan.cyanbridge\npackage:net.dinglisch.android.taskerm\n"
            self.fail(f"Unexpected adb call: {args}")

        with patch.object(fixture, "adb", side_effect=fake_adb):
            with self.assertRaisesRegex(RuntimeError, "Tasker/AutoInput"):
                fixture.preflight("emulator-5554", require_app=False)

    def test_exact_count_and_literal_are_required(self):
        xml = (
            '<hierarchy><node resource-id="com.fersaiyan.cyanbridge:id/hil_status" '
            'text="HIL_CLICK_COUNT=1"/><node resource-id="com.fersaiyan.cyanbridge:id/hil_input" '
            'text="CB_ARTEMIS_FIXTURE_72941"/></hierarchy>'
        )
        fixture.verify_fixture(xml)
        with self.assertRaises(AssertionError):
            fixture.verify_fixture(xml.replace("HIL_CLICK_COUNT=1", "HIL_CLICK_COUNT=2"))
        with self.assertRaises(AssertionError):
            fixture.verify_fixture(xml.replace(fixture.TEXT, "WRONG_LITERAL"))


if __name__ == "__main__":
    unittest.main()
