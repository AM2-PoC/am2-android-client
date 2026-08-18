#!/usr/bin/env python3
"""Staging builds must be signed by one key, not a fresh one per runner.

Android permits an install over an existing app only when the new package is
signed by the *same* key. It does not care whether that key is called debug or
release -- a debug keystore holds a real private key and a real certificate.
What matters is continuity, not the label.

That continuity has never existed here. Every staging APK is built on a
GitHub Actions runner, which generates a debug key and discards it with the
runner. So 1.1.119 could not be overwritten by 1.1.124: two different keys,
both already gone. Each round of field testing costs an uninstall, and an
uninstall costs the operator their local state.

Staging is a product flavour on the *debug* build type -- assembleStagingDebug
-- so the release signingConfig added for the Play work does not reach it. It
needs its own, on `debug`, fed from CI secrets.

The keystore itself must never be committed. This repository is public: a
committed key would let anyone build a package signed as com.am2.tik.staging,
which talks to the real staging relay with real accounts. Base64 in a
repository secret is the only route.

Assertions are booleans so a failure prints its reason, not the file.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app/build.gradle.kts"
WORKFLOW = ROOT / ".github/workflows/android-ci.yml"

PROPS = (
    "AM2_STAGING_KEYSTORE_FILE",
    "AM2_STAGING_KEYSTORE_PASSWORD",
    "AM2_STAGING_KEY_ALIAS",
    "AM2_STAGING_KEY_PASSWORD",
)


class StagingSigningContractTest(unittest.TestCase):
    def setUp(self):
        self.gradle = GRADLE.read_text()

    def has(self, pattern: str, text: str = None) -> bool:
        return re.search(pattern, text if text is not None else self.gradle, re.S) is not None

    def test_all_four_properties_are_read(self):
        for prop in PROPS:
            self.assertTrue(
                prop in self.gradle,
                f"{prop} is not read, so staging cannot be given a persistent key",
            )

    def test_it_signs_the_debug_build_type_not_a_new_one(self):
        # staging is a product flavour on the debug build type. Inventing a
        # `staging` build type would create a fourth nobody assembles.
        self.assertTrue(
            self.has(r'getByName\("debug"\)'),
            "the staging key is not applied to the debug build type, so "
            "assembleStagingDebug still uses the runner's throwaway key",
        )
        self.assertFalse(
            self.has(r'buildTypes\s*\{[^}]*create\("staging"\)'),
            "a staging build type was invented; staging is a product flavour",
        )

    def test_a_half_configured_staging_key_stops_the_build(self):
        self.assertTrue(
            "stagingSigningConfigured" in self.gradle,
            "there is no all-or-nothing decision for the staging key",
        )
        self.assertTrue(
            self.has(r"require\(\s*stagingSigningConfigured"),
            "a partially configured staging key does not fail the build",
        )

    def test_an_unconfigured_staging_build_still_works(self):
        # A developer without the key must still be able to build and run.
        # Falling back to the runner's own debug key is the correct behaviour
        # there; it is only in CI that continuity matters.
        self.assertTrue(
            self.has(r"stagingSigningProps\.values\.all\s*\{\s*it\s*==\s*null\s*\}"),
            "an unconfigured staging build is not recognised as legitimate",
        )

    def test_the_release_key_is_still_separate(self):
        # Two keys on purpose: the staging key must live in CI, and the upload
        # key must not. Collapsing them would put the upload key on every
        # runner that builds a staging APK.
        self.assertTrue(
            "AM2_KEYSTORE_FILE" in self.gradle and "AM2_STAGING_KEYSTORE_FILE" in self.gradle,
            "the staging key and the release key are no longer distinct",
        )


class StagingKeyReachesCiContractTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def test_the_staging_build_receives_the_key(self):
        self.assertTrue(
            "AM2_STAGING_KEYSTORE_BASE64" in self.workflow,
            "the staging build job is never given a keystore, so every APK it "
            "produces is signed by a key that dies with the runner",
        )

    def test_the_keystore_is_written_from_a_secret_not_the_repository(self):
        self.assertTrue(
            "secrets.AM2_STAGING_KEYSTORE_BASE64" in self.workflow,
            "the keystore does not come from a repository secret",
        )
        self.assertTrue(
            "base64 -d" in self.workflow or "base64 --decode" in self.workflow,
            "the base64 secret is never decoded back into a keystore file",
        )

    def test_no_keystore_is_committed(self):
        # This repository is public. A committed key would let anyone sign a
        # package as com.am2.tik.staging and talk to the real staging relay.
        found = [
            str(p.relative_to(ROOT))
            for p in ROOT.rglob("*")
            if p.is_file() and p.suffix in (".jks", ".keystore")
        ]
        self.assertEqual([], found, f"key material is in the repository: {found}")


if __name__ == "__main__":
    unittest.main()
