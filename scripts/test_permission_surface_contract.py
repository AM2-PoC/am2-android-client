#!/usr/bin/env python3
"""Permissions the app asks for but does not use.

A permission is a promise about what the app might do, and the operator is
asked to accept it. Three of them buy nothing:

  READ_PHONE_STATE       -- its only consumer is NetworkManager.getOperatorName(),
                            which reads TelephonyManager.networkOperatorName. That
                            property needs no permission. Asking for it costs a
                            restricted permission and a runtime prompt at login,
                            for a string that was always free.
  USE_FULL_SCREEN_INTENT -- zero references in source. Restricted since Android 14
                            to calling and alarm apps.
  CHANGE_WIFI_STATE      -- zero references in source. ACCESS_WIFI_STATE is a
                            different permission, is used for the SSID, and stays.

DISABLE_KEYGUARD looks like a fourth and is not: BaseActivity calls
requestDismissKeyguard so an incoming transmission wakes the screen. It is
reached through an API call rather than a string constant, which is exactly how
a permission comes to look unused when it is not.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
LOGIN = ROOT / "app/src/main/java/com/am2/am2/LoginActivity.kt"
NETWORK = ROOT / "app/src/main/java/com/am2/am2/NetworkManager.kt"
BASE = ROOT / "app/src/main/java/com/am2/am2/BaseActivity.kt"

UNUSED = ("READ_PHONE_STATE", "USE_FULL_SCREEN_INTENT", "CHANGE_WIFI_STATE")


class PermissionSurfaceContractTest(unittest.TestCase):
    """Assertions are booleans so a failure prints its reason, not the file."""

    def setUp(self):
        self.manifest = MANIFEST.read_text()
        self.login = LOGIN.read_text()

    def test_the_manifest_does_not_request_them(self):
        for name in UNUSED:
            self.assertFalse(
                f"android.permission.{name}" in self.manifest,
                f"{name} is still requested but nothing in the app uses it",
            )

    def test_login_does_not_prompt_for_a_permission_it_does_not_need(self):
        self.assertFalse(
            "READ_PHONE_STATE" in self.login,
            "the login screen still asks the operator for READ_PHONE_STATE",
        )

    def test_the_operator_name_still_works_without_it(self):
        # The point of the removal: this call never needed the permission, so
        # it must still be here afterwards. Deleting the permission AND the
        # feature would satisfy the assertions above for the wrong reason.
        self.assertTrue(
            "networkOperatorName" in NETWORK.read_text(),
            "the operator name lookup was removed along with the permission",
        )

    def test_the_keyguard_permission_was_not_swept_up_with_them(self):
        self.assertTrue(
            "requestDismissKeyguard" in BASE.read_text(),
            "the keyguard dismissal was removed, so the permission's justification is gone",
        )
        self.assertTrue(
            "android.permission.DISABLE_KEYGUARD" in self.manifest,
            "DISABLE_KEYGUARD was removed, so an incoming call cannot wake the screen",
        )


if __name__ == "__main__":
    unittest.main()
