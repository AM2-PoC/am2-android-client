#!/usr/bin/env python3
"""The release lane must produce what Play actually accepts, signed.

Two gaps this closes.

First, nothing ever built an App Bundle. The `play` flavour exists and Play
has required AAB for new apps since 2021, but no lane ran bundlePlayRelease --
so the artifact the store needs was never produced by anything. A flavour that
compiles is not a deliverable.

Second, the release lane built unsigned on purpose, because the key was
deliberately kept out of CI. That decision was reversed: the upload key now
lives in repository secrets like the staging key, so the lane signs what it
builds and the operator uploads rather than re-signs.

Signed is not cosmetic here. An unsigned artifact cannot be uploaded to Play
at all, and an artifact signed with the runner's own debug key looks exactly
like a real one until Play refuses it.

Assertions are booleans so a failure prints its reason, not the file.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/android-ci.yml"


class ReleasePipelineContractTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()
        start = self.workflow.index("name: release-artifact")
        self.job = self.workflow[start:]

    def has(self, pattern: str, text: str = None) -> bool:
        return re.search(pattern, text if text is not None else self.job, re.S) is not None

    def test_an_app_bundle_is_built(self):
        self.assertTrue(
            "bundlePlayRelease" in self.job,
            "nothing builds an App Bundle, so the one artifact Play accepts "
            "is never produced",
        )

    def test_the_upload_key_reaches_the_release_lane(self):
        self.assertTrue(
            "AM2_UPLOAD_KEYSTORE_BASE64" in self.job,
            "the release lane is never given the upload key, so it still "
            "produces something nobody can publish",
        )
        self.assertTrue(
            self.has(r"secrets\.AM2_UPLOAD_KEYSTORE_BASE64"),
            "the keystore does not come from a repository secret",
        )
        self.assertTrue(
            self.has(r"base64 -d|base64 --decode"),
            "the base64 secret is never decoded back into a keystore file",
        )

    def test_the_passwords_do_not_travel_on_the_command_line(self):
        # -P puts them in the process list. Gradle reads ORG_GRADLE_PROJECT_*
        # environment variables as project properties instead.
        self.assertTrue(
            self.has(r"ORG_GRADLE_PROJECT_AM2_KEYSTORE_PASSWORD"),
            "signing material is not passed through the environment",
        )
        self.assertFalse(
            self.has(r"-PAM2_KEYSTORE_PASSWORD|-PAM2_KEY_PASSWORD"),
            "a signing password is passed on the command line, where the "
            "process list exposes it",
        )

    def test_the_keystore_is_written_outside_the_workspace(self):
        # An artifact-upload step globs the workspace. A key inside it would
        # be published with the build.
        self.assertTrue(
            self.has(r"RUNNER_TEMP"),
            "the keystore is written into the workspace, where an upload step "
            "can sweep it into a published artifact",
        )

    def test_the_published_artifact_is_no_longer_called_unsigned(self):
        # The name is a claim. Leaving it would describe the artifact wrongly
        # to whoever downloads it.
        self.assertFalse(
            "am2-client-production-unsigned" in self.job,
            "the artifact is still named unsigned although the lane signs it",
        )

    def test_the_signer_is_recorded_rather_than_assumed(self):
        # apksigner already runs; the point is that a failure to verify must
        # not pass silently as it did when unsigned was the expected state.
        self.assertTrue(
            "apksigner" in self.job,
            "nothing records which key actually signed the artifact",
        )


if __name__ == "__main__":
    unittest.main()
