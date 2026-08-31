#!/usr/bin/env python3
"""Nothing secret is compiled into the field APK.

The update channel is deliberately unauthenticated. A handset that cannot sign
in must still be able to update, because the update is how the reason it cannot
sign in gets fixed -- a channel behind a session would have left the whole fleet
unrecoverable on the morning the token login turned out never to have been
wired up.

The price of that decision is that anyone can download the APK and read it. So
the decision only holds while the APK carries nothing worth reading, and that
has to be something the build refuses rather than something anyone remembers to
check. A keyword scan of a built APK cannot prove absence; what can be proven
is that the build takes no secret as input.

Two things are deliberately in there and are not secrets:

  APPROVED_UPDATE_SIGNER_SHA256   a public certificate digest. It is the value
                                  an attacker must match and cannot forge, and
                                  publishing it costs nothing.
  UPDATE_MANIFEST_URL / _APK_URL  addresses, already visible in any capture.

The signing keystore is an input to signing, never to BuildConfig, and this
holds it that way.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app/build.gradle.kts"

# Names that would carry a credential. SIGNER is excluded by exact name below,
# not by pattern, so a field called SIGNER_KEY still fails.
SECRET_NAME = re.compile(
    r"(KEY|SECRET|PASSWORD|PASSPHRASE|TOKEN|CREDENTIAL|PRIVATE|KEYSTORE|ALIAS)",
    re.I,
)
ALLOWED = {"APPROVED_UPDATE_SIGNER_SHA256"}


class NoSecretReachesTheApkTest(unittest.TestCase):
    def setUp(self):
        self.gradle = GRADLE.read_text(encoding="utf-8")

    def _fields(self):
        return re.findall(r'buildConfigField\(\s*"[^"]+"\s*,\s*"([A-Z0-9_]+)"', self.gradle)

    def test_the_build_declares_the_fields_this_test_expects(self):
        # A rename or a refactor that stops this matching would silence every
        # assertion below without failing anything.
        fields = self._fields()
        self.assertGreaterEqual(len(fields), 8, f"only found {fields}")
        self.assertIn("APPROVED_UPDATE_SIGNER_SHA256", fields)

    def test_no_build_config_field_is_named_like_a_credential(self):
        offenders = [f for f in self._fields()
                     if SECRET_NAME.search(f) and f not in ALLOWED]
        self.assertEqual(
            [], offenders,
            "these are compiled into an APK anyone may download: %s" % offenders,
        )

    def test_the_signing_material_never_becomes_a_build_config_field(self):
        for gradle_property in ("KEYSTORE_PASSWORD", "KEY_PASSWORD", "KEY_ALIAS",
                                "KEYSTORE_FILE"):
            for match in re.finditer(re.escape(gradle_property), self.gradle):
                line_start = self.gradle.rfind("\n", 0, match.start()) + 1
                line_end = self.gradle.find("\n", match.start())
                line = self.gradle[line_start:line_end if line_end > 0 else None]
                self.assertNotIn(
                    "buildConfigField", line,
                    "%s is written into the APK: %s" % (gradle_property, line.strip()),
                )

    def test_the_approved_signer_is_a_digest_and_not_a_key(self):
        # It is published on purpose. A digest is safe to publish; anything with
        # a private half is not, and a field of this name must stay the former.
        self.assertRegex(
            self.gradle,
            r'"APPROVED_UPDATE_SIGNER_SHA256"[\s\S]{0,120}?approvedSigner',
            "the approved signer field no longer comes from the signer digest",
        )
        self.assertRegex(
            self.gradle,
            r'AM2_APPROVED_SIGNER_SHA256',
            "the digest is not sourced from the property CI supplies",
        )


if __name__ == "__main__":
    unittest.main()
