#!/usr/bin/env python3
"""Release signing is all four properties or none of them.

Release builds are produced unsigned on purpose: the key is deliberately not
in CI, and the production artifact is signed wherever signing actually
happens. An unconfigured build must therefore stay valid.

The dangerous state is the one in between. Give Gradle a keystore path and no
password and it attaches no signing config at all -- which means the release
artifact comes out signed by the *debug* key. It builds, it installs, and it
is not a release. Nothing about the output says so, which is the failure shape
this project keeps paying for.

So: all four, or none, and the middle stops the build naming what is absent.

Assertions are booleans so a failure prints its reason, not the whole file.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app/build.gradle.kts"
GITIGNORE = ROOT / ".gitignore"

PROPS = (
    "AM2_KEYSTORE_FILE",
    "AM2_KEYSTORE_PASSWORD",
    "AM2_KEY_ALIAS",
    "AM2_KEY_PASSWORD",
)


class ReleaseSigningContractTest(unittest.TestCase):
    def setUp(self):
        self.gradle = GRADLE.read_text()

    def has(self, pattern: str, text: str = None) -> bool:
        return re.search(pattern, text if text is not None else self.gradle, re.S) is not None

    def test_all_four_properties_are_read(self):
        for prop in PROPS:
            self.assertTrue(
                prop in self.gradle,
                f"{prop} is not read, so signing cannot be configured from outside the repo",
            )

    def test_a_half_configured_build_stops(self):
        self.assertTrue(
            "signingConfigured" in self.gradle,
            "there is no all-or-nothing decision, so a partial configuration "
            "silently falls back to the debug key",
        )
        self.assertTrue(
            self.has(r"require\(\s*signingConfigured"),
            "a partially configured signing setup does not fail the build",
        )

    def test_an_unconfigured_build_is_still_valid(self):
        # CI builds the production artifact unsigned by design. Requiring the
        # key unconditionally would break that on the very first run.
        self.assertTrue(
            self.has(r"signingProps\.values\.all\s*\{\s*it\s*==\s*null\s*\}"),
            "an unconfigured build is not recognised as legitimate",
        )

    def test_the_release_build_type_uses_it_when_present(self):
        release = self.gradle[self.gradle.index("buildTypes"):]
        self.assertTrue(
            "signingConfig =" in release,
            "the release build type never attaches the signing config",
        )

    def test_no_key_material_can_be_committed(self):
        ignored = GITIGNORE.read_text()
        for pattern in ("*.jks", "*.keystore", "keystore.properties"):
            self.assertTrue(
                pattern in ignored,
                f"{pattern} is not ignored, so a key can be committed by accident",
            )

    def test_no_key_material_is_present(self):
        found = [
            str(p.relative_to(ROOT))
            for p in ROOT.rglob("*")
            if p.is_file() and p.suffix in (".jks", ".keystore")
        ]
        self.assertEqual([], found, f"key material is in the repository: {found}")


if __name__ == "__main__":
    unittest.main()
