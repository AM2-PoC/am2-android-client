"""A setting is named for what it changes.

The checkbox in Settings read "Auto Login" and wrote `start_on_boot`, which is
read in exactly one place -- BootReceiver -- where it decides whether the app
starts when the handset powers on. It has never had anything to do with signing
in.

fc645bb already separated the two in code: a stored login is a session whether
or not the app was asked to start on boot, and WebSocketManager says so at
length. The label was left behind, so the screen went on claiming the opposite
of what the code does.

That is not cosmetic. An operator turning "Auto Login" off to stop the app
starting by itself has no reason to expect their session to survive, and one
looking for why the radio does not sign itself back in finds a switch that
appears to be the answer and is not.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LAYOUT = ROOT / "app/src/main/res/layout/activity_setting.xml"
SETTINGS = ROOT / "app/src/main/java/com/am2/am2/SettingActivity.kt"


class ASettingSaysWhatItDoesTest(unittest.TestCase):
    def setUp(self):
        self.layout = LAYOUT.read_text(encoding="utf-8")
        self.settings = SETTINGS.read_text(encoding="utf-8")

    def _control(self, control_id):
        start = self.layout.index('android:id="@+id/%s"' % control_id)
        end = self.layout.index("/>", start)
        return self.layout[start:end]

    def test_the_boot_control_does_not_claim_to_be_about_signing_in(self):
        control = self._control("cbStartOnBoot")
        self.assertNotRegex(
            control, r"(?i)auto.?login",
            "the control that writes start_on_boot still calls itself Auto "
            "Login, while signing in does not depend on it at all",
        )

    def _label(self, control_id):
        """The text the operator reads -- not the id, which contains 'Boot'."""
        control = self._control(control_id)
        match = re.search(r'android:text="([^"]*)"', control)
        self.assertIsNotNone(match, "the control has no label at all")
        label = match.group(1)
        if label.startswith("@string/"):
            strings = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8")
            name = label.split("/", 1)[1]
            resolved = re.search(r'<string name="%s">([^<]*)</string>' % re.escape(name), strings)
            self.assertIsNotNone(resolved, "%s is referenced and not defined" % label)
            return resolved.group(1)
        return label

    def test_it_says_what_it_actually_changes(self):
        # Against the label, resolved through strings.xml. Matching the control
        # block let the id satisfy this: cbStartOnBoot contains "Boot", so a
        # label reading "Sesuatu" passed.
        self.assertRegex(
            self._label("cbStartOnBoot"), r"(?i)(boot|menyala|dinyalakan|startup)",
            "the label does not say that it is about the handset starting up",
        )

    def test_nothing_else_on_the_screen_claims_to_control_signing_in(self):
        # The session is always kept now; a screen offering to change that
        # would be describing a choice that no longer exists.
        self.assertNotRegex(
            self.layout, r"(?i)auto.?login",
            "the settings screen still offers something called auto login",
        )

    def test_the_preference_key_is_untouched(self):
        # Renaming the label must not rename what BootReceiver reads.
        self.assertIn(
            '"start_on_boot"', self.settings,
            "the boot preference key changed, which would silently reset every "
            "handset's choice",
        )


if __name__ == "__main__":
    unittest.main()
