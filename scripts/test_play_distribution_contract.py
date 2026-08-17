#!/usr/bin/env python3
"""What the Play artifact must not be able to do.

Google's Device and Network Abuse policy does not permit an app distributed
through Play to update itself outside Play. This app's entire field
distribution model is exactly that: fetch a manifest, download an APK, install
it through REQUEST_INSTALL_PACKAGES.

SELF_UPDATE_ENABLED already gates the code path, and UpdateVerifier already
refuses when it is off. That is not enough. A reviewer reads the permission
list in the uploaded artifact, and a permission that is present but unused is
still a permission the app requested. So it is removed from this flavour's
merged manifest -- absent, not merely unreachable.

The flavour deliberately takes no applicationIdSuffix. It is the same
application as `production`, distributed differently. A suffix would make it a
separate app that could never upgrade an installation already in the field --
which is the whole reason the signing key is being settled early.

Assertions are booleans so a failure prints its reason, not the file.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app/build.gradle.kts"
PLAY_MANIFEST = ROOT / "app/src/play/AndroidManifest.xml"
WORKFLOW = ROOT / ".github/workflows/android-ci.yml"


class PlayDistributionContractTest(unittest.TestCase):
    def setUp(self):
        self.gradle = GRADLE.read_text()

    def flavour(self, name: str) -> str:
        """One flavour block. Flavours close at eight spaces; their contents do not."""
        start = self.gradle.index(f'create("{name}")')
        return self.gradle[start:self.gradle.index("\n        }", start)]

    def test_the_flavour_exists(self):
        self.assertTrue(
            'create("play")' in self.gradle,
            "there is no play flavour, so the Play artifact would be the sideload one",
        )

    def test_it_does_not_change_the_application_id(self):
        self.assertFalse(
            "applicationIdSuffix" in self.flavour("play"),
            "the play flavour changes the application id, so it could never "
            "upgrade an installation already in the field",
        )

    def test_self_update_is_off(self):
        self.assertTrue(
            re.search(
                r'buildConfigField\("Boolean",\s*"SELF_UPDATE_ENABLED",\s*"false"\)',
                self.flavour("play"),
            ) is not None,
            "the play flavour leaves self-update on, which Play policy forbids",
        )

    def test_it_points_at_production(self):
        self.assertTrue(
            "wss://apiapi.am2-poc.com" in self.flavour("play"),
            "the play flavour does not talk to production",
        )

    def test_the_install_permission_is_removed_from_the_artifact(self):
        self.assertTrue(
            PLAY_MANIFEST.is_file(),
            "app/src/play/AndroidManifest.xml does not exist, so the play "
            "artifact inherits REQUEST_INSTALL_PACKAGES from the main manifest",
        )
        self.assertTrue(
            re.search(
                r'REQUEST_INSTALL_PACKAGES[^/]*tools:node="remove"',
                PLAY_MANIFEST.read_text(), re.S,
            ) is not None,
            "the play manifest does not remove REQUEST_INSTALL_PACKAGES",
        )

    def test_the_sideload_flavour_still_updates_itself(self):
        # The transition needs both. Taking self-update out of production here
        # would strand every device already in the field.
        self.assertTrue(
            re.search(
                r'buildConfigField\("Boolean",\s*"SELF_UPDATE_ENABLED",\s*"true"\)',
                self.flavour("production"),
            ) is not None,
            "production lost self-update, stranding devices already installed",
        )


class PlayFlavourIsBuiltContractTest(unittest.TestCase):
    """A flavour nothing compiles is configuration that rots.

    There is no JDK on the development host, so CI is the only place this can
    be proven -- and the Actions budget rules out a new job. The existing
    policy-and-unit job already runs one assemble; this is a second one on the
    same command, which costs one build rather than a whole runner.
    """

    def setUp(self):
        self.workflow = WORKFLOW.read_text()

    def test_ci_assembles_the_play_flavour(self):
        self.assertTrue(
            ":app:assemblePlayDebug" in self.workflow,
            "nothing ever compiles the play flavour, so it can break silently",
        )

    def test_it_rides_the_existing_job_rather_than_a_new_one(self):
        # Adding a job would cost a runner on every push. The constraint is
        # deliberate, and the cheap fix is also the tempting one to undo.
        line = next(
            (l for l in self.workflow.splitlines() if ":app:assemblePlayDebug" in l),
            "",
        )
        self.assertTrue(
            ":app:testDevDebugUnitTest" in line,
            "the play assemble is not on the existing policy-and-unit command, "
            "so it has been given a job of its own",
        )


if __name__ == "__main__":
    unittest.main()
